package com.runanywhere.runanywhereai.ui.screens.voicetest

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runanywhere.runanywhereai.download.DownloadProgressInfo
import com.runanywhere.runanywhereai.download.DownloadUpdate
import com.runanywhere.runanywhereai.download.ModelDownloadService
import com.runanywhere.runanywhereai.ui.screens.stt.AudioRecorder
import com.runanywhere.runanywhereai.ui.screens.voicetest.VoiceTestContent.YesNo
import com.runanywhere.runanywhereai.util.RACLog
import com.runanywhere.runanywhereai.util.VoiceLanguage
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.api.AudioInput
import com.runanywhere.sdk.public.api.SttOptions
import com.runanywhere.sdk.public.api.models
import com.runanywhere.sdk.public.api.stt
import com.runanywhere.sdk.public.extensions.Models.isDownloadedOnDisk
import com.runanywhere.sdk.public.types.RAModelInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.log10
import kotlin.math.sqrt

enum class VoiceTestPhase { SETUP, RUNNING, RESULT, BREATHING, DONE, CRISIS }

enum class VoiceTestActivity { IDLE, LOADING, SPEAKING, LISTENING, TRANSCRIBING }

enum class VoiceTestStep { NONE, SNRS, GAD7, BREATHING_CHOICE }

private class CrisisDetected : Exception()

/**
 * Flujo del "Test por voz": aviso, escala 0-10 de estrés momentáneo, GAD-7 ítem por ítem,
 * puntaje calculado por código, oferta de respiración guiada 4-6 y protocolo de crisis.
 *
 * Todo corre en el teléfono: la voz sale del TextToSpeech de Android ([SpanishSpeaker]) y las
 * respuestas se transcriben con el STT del SDK (Canary 180M en español, o Parakeet v3). No usa
 * modelo de lenguaje: las respuestas se interpretan con reglas fijas ([VoiceTestContent]).
 */
class VoiceTestViewModel(app: Application) : AndroidViewModel(app) {

    private val speaker = SpanishSpeaker(app)
    private val recorder = AudioRecorder()

    var phase by mutableStateOf(VoiceTestPhase.SETUP)
        private set
    var activity by mutableStateOf(VoiceTestActivity.IDLE)
        private set
    var step by mutableStateOf(VoiceTestStep.NONE)
        private set
    var assistantText by mutableStateOf("")
        private set
    var heardText by mutableStateOf<String?>(null)
        private set
    var inputLevel by mutableStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var ttsReady by mutableStateOf<Boolean?>(null)
        private set
    var ttsDescription by mutableStateOf<String?>(null)
        private set
    var sttModel by mutableStateOf<RAModelInfo?>(null)
        private set
    var sttDownloaded by mutableStateOf(false)
        private set
    var catalogSearching by mutableStateOf(true)
        private set
    var downloading by mutableStateOf(false)
        private set
    var downloadPercent by mutableStateOf<Int?>(null)
        private set

    var snrs by mutableStateOf<Int?>(null)
        private set
    val gad7Answers = mutableStateListOf<Int?>().apply { repeat(VoiceTestContent.GAD7_ITEMS.size) { add(null) } }
    var currentItem by mutableStateOf(0)
        private set
    var score by mutableStateOf<Int?>(null)
        private set
    var recommendBreathing by mutableStateOf(false)
        private set

    var breathElapsedMs by mutableStateOf(0L)
        private set
    var isInhale by mutableStateOf(true)
        private set
    var phaseFraction by mutableStateOf(0f)
        private set
    var paused by mutableStateOf(false)
        private set
    var voiceCues by mutableStateOf(true)

    private var job: Job? = null
    private var touchAnswer: CompletableDeferred<Int>? = null
    private var pauseStartedAt = 0L
    private var pausedTotalMs = 0L

    val canStart: Boolean
        get() = sttDownloaded && !downloading && ttsReady == true

    init {
        viewModelScope.launch {
            val ok = runCatching { speaker.prepare() }.getOrDefault(false)
            ttsReady = ok
            ttsDescription = if (ok) {
                "Voz del sistema en ${speaker.locale?.toLanguageTag()} (${speaker.voiceName ?: "predeterminada"})"
            } else {
                null
            }
        }
        refreshModels()
    }

    /** El catálogo se registra en segundo plano al abrir la app; se reintenta hasta 20 s. */
    fun refreshModels() {
        viewModelScope.launch {
            catalogSearching = true
            repeat(20) {
                val found = runCatching { findSttModel() }.getOrNull()
                if (found != null) {
                    sttModel = found
                    sttDownloaded = found.isDownloadedOnDisk
                    catalogSearching = false
                    return@launch
                }
                delay(1_000)
            }
            catalogSearching = false
        }
    }

    private suspend fun findSttModel(): RAModelInfo? {
        val all = withContext(Dispatchers.IO) { RunAnywhere.models.list() }
        val canary = all.firstOrNull { it.id == VoiceLanguage.CANARY_180M_ID }
        val parakeet = all.firstOrNull { it.id == VoiceLanguage.PARAKEET_V3_ID }
        return listOfNotNull(canary, parakeet).firstOrNull { it.isDownloadedOnDisk } ?: canary
    }

    /** Canary acepta "es"; Parakeet v3 detecta el idioma y debe quedar en su idioma de carga. */
    private fun sttLanguageFor(model: RAModelInfo): String =
        if (model.id == VoiceLanguage.CANARY_180M_ID) "es" else "en"

    fun downloadSttModel() {
        val model = sttModel ?: return
        if (downloading || sttDownloaded) return
        viewModelScope.launch {
            downloading = true
            error = null
            downloadPercent = 0
            try {
                if (ModelDownloadService.start(model)) {
                    val watcher = launch {
                        ModelDownloadService.active.collect { active ->
                            if (active?.modelId == model.id) downloadPercent = active.progress.percent
                        }
                    }
                    val outcome = ModelDownloadService.awaitFinish(model.id)
                    watcher.cancel()
                    if (outcome is ModelDownloadService.Outcome.Stopped) {
                        error = outcome.record.message ?: "La descarga se detuvo antes de terminar."
                    }
                } else {
                    var latest = DownloadProgressInfo()
                    RunAnywhere.models.download(model.id).collect { event ->
                        when (val update = DownloadProgressInfo.advance(latest, event)) {
                            is DownloadUpdate.Advanced -> {
                                latest = update.info
                                downloadPercent = update.info.percent
                            }
                            is DownloadUpdate.Stopped -> error = update.message
                            else -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RACLog.e("voice test model download failed", e)
                error = e.message ?: "No se pudo descargar el modelo."
            } finally {
                downloading = false
                downloadPercent = null
                val found = runCatching { findSttModel() }.getOrNull()
                if (found != null) {
                    sttModel = found
                    sttDownloaded = found.isDownloadedOnDisk
                }
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        val model = sttModel ?: return
        resetAnswers()
        error = null
        job = viewModelScope.launch {
            try {
                phase = VoiceTestPhase.RUNNING
                activity = VoiceTestActivity.LOADING
                assistantText = "Cargando el modelo de voz a texto…"
                withContext(Dispatchers.IO) { RunAnywhere.models.load(model.id) }
                val language = sttLanguageFor(model)

                say(VoiceTestContent.DISCLAIMER)

                step = VoiceTestStep.SNRS
                snrs = ask(
                    question = VoiceTestContent.SNRS_QUESTION,
                    reprompt = "No le entendí bien. Por favor diga un número del 0 al 10.",
                    language = language,
                ) { VoiceTestContent.parseSnrs(it) }

                step = VoiceTestStep.GAD7
                say("Ahora le haré 7 preguntas. ${VoiceTestContent.GAD7_STEM}")
                for (i in VoiceTestContent.GAD7_ITEMS.indices) {
                    currentItem = i
                    gad7Answers[i] = ask(
                        question = "Pregunta ${i + 1} de 7. ${VoiceTestContent.spokenItem(i)}. " +
                            VoiceTestContent.OPTIONS_SPOKEN,
                        reprompt = "No le entendí bien. ${VoiceTestContent.OPTIONS_SPOKEN}",
                        language = language,
                    ) { VoiceTestContent.parseGad7(it) }
                }

                val total = gad7Answers.sumOf { it ?: 0 }
                score = total
                recommendBreathing = VoiceTestContent.shouldRecommendBreathing(total, snrs)
                step = VoiceTestStep.NONE
                phase = VoiceTestPhase.RESULT
                say(VoiceTestContent.resultMessage(total))

                step = VoiceTestStep.BREATHING_CHOICE
                val wantsBreathing = ask(
                    question = if (recommendBreathing) {
                        VoiceTestContent.BREATHING_OFFER
                    } else {
                        VoiceTestContent.BREATHING_OPTIONAL
                    },
                    reprompt = "Diga: sí, iniciemos la respiración, o en otro momento.",
                    language = language,
                ) { heard ->
                    when (VoiceTestContent.parseYesNo(heard)) {
                        YesNo.YES -> 1
                        YesNo.NO -> 0
                        null -> null
                    }
                } == 1
                step = VoiceTestStep.NONE

                if (wantsBreathing) {
                    runBreathing()
                } else {
                    say(VoiceTestContent.FAREWELL)
                    phase = VoiceTestPhase.DONE
                }
            } catch (e: CrisisDetected) {
                enterCrisis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RACLog.e("voice test failed", e)
                error = e.message ?: "Algo falló durante el test."
                activity = VoiceTestActivity.IDLE
            }
        }
    }

    /** Respiración guiada sin pasar por el test (útil para la demostración). */
    fun startBreathingOnly() {
        if (job?.isActive == true) return
        error = null
        job = viewModelScope.launch {
            try {
                runBreathing()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RACLog.e("breathing failed", e)
                error = e.message ?: "Algo falló durante la respiración."
            }
        }
    }

    /** Respuesta tocada en pantalla: 0..3 en GAD-7, 0..10 en la escala de estrés, 1/0 sí/no. */
    fun onTouchAnswer(value: Int) {
        touchAnswer?.complete(value)
    }

    fun togglePause() {
        if (phase != VoiceTestPhase.BREATHING) return
        if (!paused) {
            pauseStartedAt = SystemClock.elapsedRealtime()
            paused = true
            speaker.stop()
        } else {
            pausedTotalMs += SystemClock.elapsedRealtime() - pauseStartedAt
            paused = false
        }
    }

    /** "Gracias, detener": corta lo que esté pasando y se despide. */
    fun stop() {
        val previous = job
        touchAnswer = null
        speaker.stop()
        stopRecorderAsync()
        step = VoiceTestStep.NONE
        paused = false
        job = viewModelScope.launch {
            // Esperar a que el trabajo anterior termine su limpieza (que detiene la voz) antes
            // de despedirse; si no, esa limpieza cortaría la despedida.
            previous?.cancelAndJoin()
            activity = VoiceTestActivity.IDLE
            if (phase == VoiceTestPhase.CRISIS) return@launch
            phase = VoiceTestPhase.DONE
            say(VoiceTestContent.FAREWELL)
        }
    }

    fun reset() {
        job?.cancel()
        touchAnswer = null
        speaker.stop()
        stopRecorderAsync()
        resetAnswers()
        activity = VoiceTestActivity.IDLE
        step = VoiceTestStep.NONE
        phase = VoiceTestPhase.SETUP
        error = null
    }

    fun clearError() {
        error = null
    }

    private fun resetAnswers() {
        snrs = null
        for (i in gad7Answers.indices) gad7Answers[i] = null
        currentItem = 0
        score = null
        recommendBreathing = false
        heardText = null
        assistantText = ""
        breathElapsedMs = 0L
        paused = false
        pausedTotalMs = 0L
    }

    private suspend fun say(text: String) {
        activity = VoiceTestActivity.SPEAKING
        assistantText = text
        speaker.speak(text)
        activity = VoiceTestActivity.IDLE
    }

    /** Pregunta por voz; la primera respuesta válida (hablada o tocada) gana. */
    private suspend fun ask(
        question: String,
        reprompt: String,
        language: String,
        parse: (String) -> Int?,
    ): Int = coroutineScope {
        val touch = CompletableDeferred<Int>()
        touchAnswer = touch
        heardText = null
        val voice = async { answerByVoice(question, reprompt, language, parse) }
        try {
            select<Int> {
                touch.onAwait { it }
                voice.onAwait { it }
            }
        } finally {
            voice.cancel()
            touchAnswer = null
            speaker.stop()
            activity = VoiceTestActivity.IDLE
        }
    }

    private suspend fun answerByVoice(
        question: String,
        reprompt: String,
        language: String,
        parse: (String) -> Int?,
    ): Int {
        say(question)
        var attempts = 0
        while (true) {
            val heard = listen(language)
            if (!heard.isNullOrBlank()) {
                heardText = heard
                if (VoiceTestContent.detectRisk(heard)) throw CrisisDetected()
                parse(heard)?.let { return it }
            }
            attempts++
            if (attempts >= MAX_VOICE_ATTEMPTS) {
                say("Puede tocar su respuesta en la pantalla.")
                awaitCancellation()
            }
            say(reprompt)
        }
    }

    /** Graba hasta que la persona deja de hablar y transcribe. Null si no se oyó voz. */
    private suspend fun listen(language: String): String? {
        delay(POST_SPEECH_GAP_MS)
        val pcm = ByteArrayOutputStream()
        val finished = CompletableDeferred<Unit>()
        val detector = EndpointDetector(chunkMs = AudioRecorder.CHUNK_MS.toLong())
        activity = VoiceTestActivity.LISTENING
        try {
            recorder.start(
                onChunk = { bytes, _ ->
                    synchronized(pcm) { pcm.write(bytes) }
                    val db = dbfs(bytes)
                    inputLevel = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
                    if (detector.onChunk(db)) finished.complete(Unit)
                },
                onError = { finished.complete(Unit) },
            )
            withTimeoutOrNull(EndpointDetector.MAX_LISTEN_MS + 2_000) { finished.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { recorder.stop() }
            inputLevel = 0f
        }
        if (!detector.heardSpeech) {
            activity = VoiceTestActivity.IDLE
            return null
        }
        val audio = synchronized(pcm) { pcm.toByteArray() }
        activity = VoiceTestActivity.TRANSCRIBING
        val text = withContext(Dispatchers.IO) {
            RunAnywhere.stt.transcribe(
                AudioInput.pcm16(audio, AudioRecorder.SAMPLE_RATE),
                SttOptions(language = language, punctuation = true),
            ).text.trim()
        }
        activity = VoiceTestActivity.IDLE
        return text
    }

    /** Estrategia 3.1: 4 s inhalar, 6 s exhalar, 3 minutos. El tiempo sale del reloj, no se acumula. */
    private suspend fun runBreathing() {
        phase = VoiceTestPhase.BREATHING
        step = VoiceTestStep.NONE
        paused = false
        pausedTotalMs = 0L
        breathElapsedMs = 0L
        isInhale = true
        phaseFraction = 0f
        say(VoiceTestContent.BREATHING_INTRO)
        val start = SystemClock.elapsedRealtime()
        var lastPhaseKey = -1L
        while (true) {
            if (paused) {
                delay(100)
                continue
            }
            val elapsed = SystemClock.elapsedRealtime() - start - pausedTotalMs
            if (elapsed >= VoiceTestContent.BREATHING_TOTAL_MS) break
            val inCycle = elapsed % VoiceTestContent.CYCLE_MS
            val inhale = inCycle < VoiceTestContent.INHALE_MS
            breathElapsedMs = elapsed
            isInhale = inhale
            phaseFraction = if (inhale) {
                inCycle.toFloat() / VoiceTestContent.INHALE_MS
            } else {
                (inCycle - VoiceTestContent.INHALE_MS).toFloat() / VoiceTestContent.EXHALE_MS
            }
            val phaseKey = (elapsed / VoiceTestContent.CYCLE_MS) * 2 + if (inhale) 0 else 1
            if (phaseKey != lastPhaseKey) {
                lastPhaseKey = phaseKey
                if (voiceCues) speaker.say(if (inhale) "Inhale" else "Exhale")
            }
            delay(40)
        }
        breathElapsedMs = VoiceTestContent.BREATHING_TOTAL_MS
        say(VoiceTestContent.BREATHING_END)
        say(VoiceTestContent.FAREWELL)
        phase = VoiceTestPhase.DONE
    }

    private suspend fun enterCrisis() {
        withContext(NonCancellable) {
            touchAnswer = null
            step = VoiceTestStep.NONE
            phase = VoiceTestPhase.CRISIS
            speaker.stop()
            say(VoiceTestContent.CRISIS_MESSAGE)
        }
    }

    private fun stopRecorderAsync() {
        Thread { runCatching { recorder.stop() } }.start()
    }

    override fun onCleared() {
        job?.cancel()
        stopRecorderAsync()
        speaker.shutdown()
        super.onCleared()
    }

    private companion object {
        const val MAX_VOICE_ATTEMPTS = 3
        const val POST_SPEECH_GAP_MS = 300L

        /** Nivel en dBFS de un bloque PCM 16-bit little-endian. */
        fun dbfs(bytes: ByteArray): Double {
            val samples = bytes.size / 2
            if (samples == 0) return -90.0
            var sum = 0.0
            var i = 0
            while (i + 1 < bytes.size) {
                val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                val value = sample.toShort().toDouble()
                sum += value * value
                i += 2
            }
            val rms = sqrt(sum / samples)
            return 20.0 * log10(rms / 32768.0 + 1e-9)
        }
    }
}

/**
 * Detecta el inicio y el fin de una respuesta hablada con el nivel de cada bloque de 100 ms.
 * Calibra el ruido de fondo en los primeros 300 ms; exige 200 ms seguidos de voz para empezar y
 * 1.2 s de silencio para terminar. Corta a los 7 s si nadie habla y a los 10 s en total.
 */
internal class EndpointDetector(private val chunkMs: Long) {
    var heardSpeech = false
        private set

    private var elapsedMs = 0L
    private var floorDb = -55.0
    private var calibrationChunks = 0
    private var speechRun = 0
    private var silenceMs = 0L

    /** Devuelve true cuando hay que dejar de grabar. */
    fun onChunk(db: Double): Boolean {
        elapsedMs += chunkMs
        if (!heardSpeech && calibrationChunks < CALIBRATION_CHUNKS) {
            floorDb = if (calibrationChunks == 0) db else (floorDb * calibrationChunks + db) / (calibrationChunks + 1)
            calibrationChunks++
            // Si la persona habla de inmediato, el piso no puede subir tanto que tape la voz.
            floorDb = minOf(floorDb, -35.0)
            return elapsedMs >= MAX_LISTEN_MS
        }
        val speechThreshold = maxOf(floorDb + 12.0, -45.0)
        val silenceThreshold = maxOf(floorDb + 6.0, -50.0)
        if (!heardSpeech) {
            speechRun = if (db > speechThreshold) speechRun + 1 else 0
            if (speechRun >= 2) {
                heardSpeech = true
            } else if (elapsedMs >= NO_SPEECH_TIMEOUT_MS) {
                return true
            }
        } else {
            silenceMs = if (db < silenceThreshold) silenceMs + chunkMs else 0L
            if (silenceMs >= TRAILING_SILENCE_MS) return true
        }
        return elapsedMs >= MAX_LISTEN_MS
    }

    companion object {
        const val MAX_LISTEN_MS = 10_000L
        const val NO_SPEECH_TIMEOUT_MS = 7_000L
        const val TRAILING_SILENCE_MS = 1_200L
        private const val CALIBRATION_CHUNKS = 3
    }
}
