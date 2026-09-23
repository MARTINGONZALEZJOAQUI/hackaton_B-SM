package com.runanywhere.runanywhereai.ui.screens.voicetest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

/**
 * "Test por voz" (prototipo Unicauca): GAD-7 aplicado por voz con respaldo táctil, y
 * respiración guiada 4-6 (estrategia 3.1 de la especificación). Pantalla adicional: no cambia
 * ninguna de las pantallas originales de la app.
 */
@Composable
fun VoiceTestScreen(viewModel: VoiceTestViewModel = viewModel()) {
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    // La pantalla no se apaga durante el test ni durante los 3 minutos de respiración.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        viewModel.error?.let { message ->
            InfoCard(title = "Aviso", body = message, isError = true) {
                TextButton(onClick = viewModel::clearError) { Text("Cerrar") }
            }
        }

        when (viewModel.phase) {
            VoiceTestPhase.SETUP -> SetupSection(
                viewModel = viewModel,
                micGranted = micGranted,
                onRequestMic = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            VoiceTestPhase.RUNNING -> RunningSection(viewModel)
            VoiceTestPhase.RESULT -> {
                ResultSection(viewModel)
                RunningSection(viewModel)
            }
            VoiceTestPhase.BREATHING -> BreathingSection(viewModel)
            VoiceTestPhase.CRISIS -> CrisisSection(
                onDial = { number ->
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                },
                onBack = viewModel::reset,
            )
            VoiceTestPhase.DONE -> DoneSection(viewModel)
        }
    }
}

@Composable
private fun SetupSection(viewModel: VoiceTestViewModel, micGranted: Boolean, onRequestMic: () -> Unit) {
    Text(
        "Test por voz",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "Escala de estrés 0 a 10 y cuestionario GAD-7 leídos en voz alta. Puede responder " +
            "hablando o tocando la opción. Después se ofrece una respiración guiada de 3 minutos.",
        style = MaterialTheme.typography.bodyMedium,
    )

    InfoCard(title = "Antes de empezar", body = VoiceTestContent.DISCLAIMER)

    val tts = viewModel.ttsReady
    InfoCard(
        title = "Voz en español",
        body = when (tts) {
            null -> "Preparando la voz del teléfono…"
            true -> viewModel.ttsDescription ?: "Lista."
            false -> "No hay una voz en español instalada. Vaya a Ajustes > Accesibilidad > " +
                "Salida de texto a voz, elija el motor de Google e instale Español."
        },
        isError = tts == false,
    )

    val model = viewModel.sttModel
    InfoCard(
        title = "Modelo para escuchar (en el teléfono)",
        body = when {
            viewModel.catalogSearching && model == null -> "Buscando el modelo en el catálogo…"
            model == null -> "No se encontró Canary 180M en el catálogo. Toque Reintentar."
            viewModel.downloading -> "Descargando ${model.name}… ${viewModel.downloadPercent ?: 0} %"
            viewModel.sttDownloaded -> "${model.name}: descargado."
            else -> "${model.name}: falta descargarlo (unos 207 MB, una sola vez)."
        },
        isError = model == null && !viewModel.catalogSearching,
    ) {
        when {
            model == null && !viewModel.catalogSearching ->
                OutlinedButton(onClick = viewModel::refreshModels) { Text("Reintentar") }
            model != null && !viewModel.sttDownloaded && !viewModel.downloading ->
                Button(onClick = viewModel::downloadSttModel) { Text("Descargar modelo") }
        }
        if (viewModel.downloading) {
            val percent = viewModel.downloadPercent
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (!micGranted) {
        InfoCard(
            title = "Micrófono",
            body = "El test necesita el micrófono para escuchar sus respuestas.",
        ) {
            Button(onClick = onRequestMic) { Text("Permitir micrófono") }
        }
    }

    Button(
        onClick = viewModel::start,
        enabled = viewModel.canStart && micGranted,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Comenzar test") }

    OutlinedButton(
        onClick = viewModel::startBreathingOnly,
        enabled = tts == true,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Ir directo a la respiración guiada") }
}

@Composable
private fun RunningSection(viewModel: VoiceTestViewModel) {
    val status = when (viewModel.activity) {
        VoiceTestActivity.IDLE -> "Esperando su respuesta"
        VoiceTestActivity.LOADING -> "Cargando…"
        VoiceTestActivity.SPEAKING -> "Hablando…"
        VoiceTestActivity.LISTENING -> "Escuchando: hable ahora"
        VoiceTestActivity.TRANSCRIBING -> "Transcribiendo…"
    }
    InfoCard(title = status, body = viewModel.assistantText) {
        if (viewModel.activity == VoiceTestActivity.LISTENING) {
            LinearProgressIndicator(
                progress = { viewModel.inputLevel },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        viewModel.heardText?.let {
            Text("Escuché: \"$it\"", style = MaterialTheme.typography.bodyMedium)
        }
    }

    when (viewModel.step) {
        VoiceTestStep.SNRS -> {
            Text(VoiceTestContent.SNRS_QUESTION, style = MaterialTheme.typography.titleMedium)
            NumberRow(0..5, viewModel::onTouchAnswer)
            NumberRow(6..10, viewModel::onTouchAnswer)
        }
        VoiceTestStep.GAD7 -> {
            val i = viewModel.currentItem
            Text(
                "Pregunta ${i + 1} de 7",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(VoiceTestContent.GAD7_STEM, style = MaterialTheme.typography.bodyMedium)
            Text(
                VoiceTestContent.GAD7_ITEMS[i],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            VoiceTestContent.GAD7_OPTIONS.forEachIndexed { value, label ->
                FilledTonalButton(
                    onClick = { viewModel.onTouchAnswer(value) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("$label ($value)") }
            }
        }
        VoiceTestStep.BREATHING_CHOICE -> {
            Button(onClick = { viewModel.onTouchAnswer(1) }, modifier = Modifier.fillMaxWidth()) {
                Text("Sí, iniciemos la respiración")
            }
            OutlinedButton(onClick = { viewModel.onTouchAnswer(0) }, modifier = Modifier.fillMaxWidth()) {
                Text("En otro momento")
            }
        }
        VoiceTestStep.NONE -> Unit
    }

    AnswersSummary(viewModel)

    TextButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
        Text("Gracias, detener")
    }
}

@Composable
private fun NumberRow(range: IntRange, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        range.forEach { n ->
            OutlinedButton(onClick = { onPick(n) }, modifier = Modifier.weight(1f)) { Text("$n") }
        }
    }
}

@Composable
private fun AnswersSummary(viewModel: VoiceTestViewModel) {
    val answered = viewModel.gad7Answers.withIndex().filter { it.value != null }
    if (viewModel.snrs == null && answered.isEmpty()) return
    val lines = buildString {
        viewModel.snrs?.let { append("Estrés en este momento (0-10): $it\n") }
        answered.forEach { (index, value) ->
            append("GAD-7 ítem ${index + 1}: ${VoiceTestContent.GAD7_OPTIONS[value!!]} ($value)\n")
        }
    }.trimEnd()
    InfoCard(title = "Respuestas registradas", body = lines)
}

@Composable
private fun ResultSection(viewModel: VoiceTestViewModel) {
    val score = viewModel.score ?: return
    InfoCard(
        title = "Resultado GAD-7: $score de 21 (ansiedad ${VoiceTestContent.gad7Band(score)})",
        body = VoiceTestContent.resultMessage(score) +
            (viewModel.snrs?.let { "\nEstrés reportado ahora: $it de 10." } ?: "") +
            if (viewModel.recommendBreathing) "\nSe recomienda la respiración guiada." else "",
    )
}

@Composable
private fun BreathingSection(viewModel: VoiceTestViewModel) {
    val colors = MaterialTheme.colorScheme
    val total = VoiceTestContent.BREATHING_TOTAL_MS
    val remainingMs = (total - viewModel.breathElapsedMs).coerceAtLeast(0L)
    val phaseMs = if (viewModel.isInhale) VoiceTestContent.INHALE_MS else VoiceTestContent.EXHALE_MS
    val secondsLeftInPhase = ceil((1f - viewModel.phaseFraction) * phaseMs / 1000f).toInt().coerceAtLeast(1)

    Text(
        "Respiración guiada",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "Inhale 4 segundos por la nariz hacia el abdomen y exhale 6 segundos por la boca. " +
            "Unas 6 respiraciones por minuto durante 3 minutos.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(280.dp)) {
            val maxRadius = size.minDimension / 2f * 0.95f
            val minRadius = maxRadius * 0.42f
            val eased = (0.5f - 0.5f * cos(PI.toFloat() * viewModel.phaseFraction))
            val expansion = if (viewModel.isInhale) eased else 1f - eased
            val radius = minRadius + (maxRadius - minRadius) * expansion
            val dashed = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f)),
            )
            // Anillos guía: pulmones vacíos (interior) y llenos (exterior).
            drawCircle(color = colors.outline, radius = minRadius, style = dashed)
            drawCircle(color = colors.outline, radius = maxRadius, style = dashed)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.85f), colors.primary.copy(alpha = 0.25f)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
                radius = radius,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (viewModel.paused) "En pausa" else if (viewModel.isInhale) "Inhale" else "Exhale",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimary,
            )
            if (!viewModel.paused) {
                Text("$secondsLeftInPhase", style = MaterialTheme.typography.titleLarge, color = colors.onPrimary)
            }
        }
    }

    LinearProgressIndicator(
        progress = { (viewModel.breathElapsedMs.toFloat() / total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Tiempo restante: %d:%02d".format(remainingMs / 60_000, (remainingMs / 1000) % 60),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Voz guía (Inhale / Exhale)", modifier = Modifier.weight(1f))
        Switch(checked = viewModel.voiceCues, onCheckedChange = { viewModel.voiceCues = it })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = viewModel::togglePause, modifier = Modifier.weight(1f)) {
            Text(if (viewModel.paused) "Continuar" else "Pausar un momento")
        }
        Button(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
            Text("Gracias, detener")
        }
    }
}

@Composable
private fun CrisisSection(onDial: (String) -> Unit, onBack: () -> Unit) {
    InfoCard(title = "Busque apoyo ahora", body = VoiceTestContent.CRISIS_MESSAGE, isError = true) {
        Button(onClick = { onDial("192") }, modifier = Modifier.fillMaxWidth()) {
            Text("Llamar a la Línea 192 (luego marque la opción 4)")
        }
        Button(onClick = { onDial("123") }, modifier = Modifier.fillMaxWidth()) {
            Text("Llamar al 123 (emergencias)")
        }
        TextButton(onClick = onBack) { Text("Volver al inicio") }
    }
}

@Composable
private fun DoneSection(viewModel: VoiceTestViewModel) {
    viewModel.score?.let { ResultSection(viewModel) }
    InfoCard(
        title = "Sesión terminada",
        body = "Gracias por participar. Recuerde que esta autoevaluación no es un diagnóstico.",
    ) {
        Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Volver al inicio") }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    isError: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) colors.errorContainer else colors.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
