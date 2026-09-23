package com.runanywhere.runanywhereai.ui.screens.voicetest

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Voz del sistema Android en español para el "Test por voz".
 *
 * Se usa TextToSpeech directamente y no a través del SDK porque, en la versión 0.20.24 del SDK,
 * la voz del sistema vuelve a en-US en cada llamada y las voces Sherpa (Piper, Supertonic) no
 * reciben el idioma. Con la voz de Google en español instalada funciona sin conexión y no
 * necesita descargar ningún modelo, así que sirve también en teléfonos modestos.
 */
class SpanishSpeaker(context: Context) {

    private val initStatus = CompletableDeferred<Int>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts = TextToSpeech(context.applicationContext) { status -> initStatus.complete(status) }

    /** Idioma que quedó configurado, o null si no hay voz en español disponible. */
    var locale: Locale? = null
        private set

    /** Nombre de la voz elegida (preferiblemente una que no necesite internet). */
    var voiceName: String? = null
        private set

    suspend fun prepare(): Boolean {
        if (initStatus.await() != TextToSpeech.SUCCESS) return false
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })

        val device = Locale.getDefault()
        val candidates = buildList {
            if (device.language == "es") add(device)
            add(Locale.forLanguageTag("es-CO"))
            add(Locale.forLanguageTag("es-US"))
            add(Locale.forLanguageTag("es-MX"))
            add(Locale.forLanguageTag("es-ES"))
            add(Locale.forLanguageTag("es"))
        }
        val chosen = candidates.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
            ?: return false
        if (tts.setLanguage(chosen) < TextToSpeech.LANG_AVAILABLE) return false
        locale = chosen
        offlineSpanishVoice(chosen)?.let { tts.voice = it }
        voiceName = tts.voice?.name
        return true
    }

    private fun offlineSpanishVoice(preferred: Locale): Voice? {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { voice ->
            voice.locale.language == "es" &&
                !voice.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
        }
        return voices.firstOrNull { it.locale.country == preferred.country } ?: voices.firstOrNull()
    }

    private fun finish(utteranceId: String?) {
        utteranceId?.let { pending.remove(it)?.complete(Unit) }
    }

    /** Habla y espera a que termine el audio. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        }
    }

    /** Dice una palabra corta sin esperar (señales de la respiración guiada). */
    fun say(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
