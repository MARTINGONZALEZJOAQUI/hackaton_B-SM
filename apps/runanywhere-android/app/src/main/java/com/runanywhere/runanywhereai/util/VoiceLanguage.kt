package com.runanywhere.runanywhereai.util

import java.util.Locale

/**
 * Idioma de reconocimiento de voz para teléfonos en español (prototipo Unicauca).
 *
 * El backend Sherpa del SDK solo acepta un idioma por llamada en Whisper y Canary; los demás
 * reconocedores rechazan cualquier idioma distinto del que tienen cargado ("en"). Por eso el
 * idioma original ("en") se conserva para todos los modelos, salvo Canary 180M (en/es/de/fr)
 * cuando el teléfono está en español. Parakeet v3 detecta el idioma solo y se deja en "en".
 */
object VoiceLanguage {
    const val CANARY_180M_ID = "sherpa-nemo-canary-180m-flash-int8"
    const val PARAKEET_V3_ID = "sherpa-nemo-parakeet-tdt-0.6b-v3-int8"

    private val SPANISH_CAPABLE_STT = setOf(CANARY_180M_ID)

    fun isDeviceSpanish(): Boolean = Locale.getDefault().language == "es"

    /** Idioma para SttOptions: "es" solo con Canary en un teléfono en español; si no, [original]. */
    fun sttLanguage(modelId: String?, original: String = "en"): String =
        if (isDeviceSpanish() && modelId in SPANISH_CAPABLE_STT) "es" else original
}
