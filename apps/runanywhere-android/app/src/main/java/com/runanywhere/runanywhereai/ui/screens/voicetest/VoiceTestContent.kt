package com.runanywhere.runanywhereai.ui.screens.voicetest

import java.text.Normalizer
import java.util.Locale

/**
 * Contenido fijo del "Test por voz" (prototipo Unicauca, Reto 3).
 *
 * Los ítems del GAD-7 son el texto oficial en español (versión "Spanish for the USA" de
 * phqscreeners.com, Spitzer, Williams, Kroenke y colegas, subvención educativa de Pfizer;
 * "No se requiere permiso para reproducir, traducir, presentar o distribuir"). La pantalla los
 * muestra textuales; la voz solo cambia "(a)" por la forma femenina completa para que el
 * sintetizador no lea el paréntesis. El puntaje lo calcula este código, nunca un modelo de
 * lenguaje, para no alterar la validez de la escala.
 */
object VoiceTestContent {

    const val DISCLAIMER =
        "Esta es una autoevaluación de tamizaje con el cuestionario GAD-7. No es un diagnóstico " +
            "y no reemplaza la valoración de un profesional. Sus respuestas se procesan solo en " +
            "este teléfono. Si en algún momento se siente en peligro, llame a la Línea 192, " +
            "opción 4, o al 123."

    // Escala numérica de estrés momentáneo (SNRS-11, Karvounides et al., 2016). Anclas 0 y 10
    // traducidas del original ("No stress" / "Highest stress possible"); la traducción al
    // español no tiene validación formal propia.
    const val SNRS_QUESTION =
        "En una escala de 0 a 10, donde 0 es sin estrés y 10 es el mayor estrés posible, " +
            "¿cuánto estrés siente en este momento?"

    const val GAD7_STEM =
        "Durante las últimas 2 semanas, ¿qué tan seguido ha tenido molestias debido a los " +
            "siguientes problemas?"

    /** Texto oficial, tal como se muestra en pantalla. */
    val GAD7_ITEMS = listOf(
        "Se ha sentido nervioso(a), ansioso(a) o con los nervios de punta",
        "No ha sido capaz de parar o controlar su preocupación",
        "Se ha preocupado demasiado por motivos diferentes",
        "Ha tenido dificultad para relajarse",
        "Se ha sentido tan inquieto(a) que no ha podido quedarse quieto(a)",
        "Se ha molestado o irritado fácilmente",
        "Ha tenido miedo de que algo terrible fuera a pasar",
    )

    /** Opciones oficiales, en orden de puntaje 0..3. */
    val GAD7_OPTIONS = listOf(
        "Ningún día",
        "Varios días",
        "Más de la mitad de los días",
        "Casi todos los días",
    )

    val OPTIONS_SPOKEN =
        "Responda: ningún día, varios días, más de la mitad de los días, o casi todos los días."

    /** Forma hablada del ítem: el mismo texto sin los paréntesis de género. */
    fun spokenItem(index: Int): String =
        GAD7_ITEMS[index]
            .replace("nervioso(a)", "nervioso o nerviosa")
            .replace("ansioso(a)", "ansioso o ansiosa")
            .replace("inquieto(a)", "inquieto o inquieta")
            .replace("quieto(a)", "quieto o quieta")

    const val BREATHING_OFFER =
        "Le sugiero realizar una breve sesión de respiración guiada de 3 minutos para ayudarle " +
            "a recuperar la calma. ¿Le gustaría comenzar la guía de respiración en este momento? " +
            "Puede decir: sí, iniciemos la respiración, o en otro momento."

    const val BREATHING_OPTIONAL =
        "Si lo desea, puede hacer una breve sesión de respiración guiada de 3 minutos. " +
            "¿Le gustaría comenzarla ahora? Puede decir: sí, iniciemos la respiración, o en otro momento."

    // Estrategia 3.1 de la especificación: inhalar 4 s por la nariz hacia el abdomen, exhalar
    // 6 s por la boca, unas 6 respiraciones por minuto, durante 3 minutos.
    const val BREATHING_INTRO =
        "Iniciando respiración pausada. Tome aire suavemente por la nariz, llevándolo hacia el " +
            "abdomen, durante 4 segundos, y suéltelo despacio por la boca durante 6 segundos. " +
            "Respire sin forzar. Siga el círculo."

    const val BREATHING_END =
        "Muy bien. Terminamos la respiración guiada. Si lo desea, puede medir de nuevo su " +
            "variabilidad cardíaca para ver el efecto."

    const val FAREWELL = "Con mucho gusto. Finalizamos por ahora, que tenga un excelente día."

    const val CRISIS_MESSAGE =
        "Gracias por contármelo. Lo que siente es importante y no tiene que enfrentarlo solo. " +
            "Por favor comuníquese ahora con la Línea 192, opción 4, de atención en salud mental, " +
            "gratuita las 24 horas, o con la línea de emergencias 123."

    const val INHALE_MS = 4_000L
    const val EXHALE_MS = 6_000L
    const val CYCLE_MS = INHALE_MS + EXHALE_MS
    const val BREATHING_TOTAL_MS = 3 * 60_000L

    // Umbrales. GAD-7 >= 10 es el punto de corte validado (Spitzer et al., 2006). SNRS-11 no
    // tiene corte clínico validado: 7 es una decisión del equipo solo para recomendar (no
    // imponer) la respiración, que es de bajo riesgo.
    const val GAD7_RECOMMEND_CUTOFF = 10
    const val SNRS_RECOMMEND_CUTOFF = 7

    fun gad7Band(score: Int): String = when {
        score <= 4 -> "mínima"
        score <= 9 -> "leve"
        score <= 14 -> "moderada"
        else -> "grave"
    }

    fun resultMessage(score: Int): String {
        val base = "Su puntaje en el GAD-7 es $score de 21, que corresponde a ansiedad " +
            "${gad7Band(score)}. Recuerde que es una autoevaluación y no un diagnóstico."
        return if (score >= GAD7_RECOMMEND_CUTOFF) {
            "$base Con este puntaje se recomienda conversar con un profesional de salud mental, " +
                "por ejemplo en Bienestar Universitario."
        } else {
            base
        }
    }

    fun shouldRecommendBreathing(gad7: Int?, snrs: Int?): Boolean =
        (gad7 ?: 0) >= GAD7_RECOMMEND_CUTOFF || (snrs ?: 0) >= SNRS_RECOMMEND_CUTOFF

    // ---------------------------------------------------------------------------------------
    // Interpretación de respuestas habladas (determinista, sin modelo de lenguaje)
    // ---------------------------------------------------------------------------------------

    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val noAccents = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents.replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun hasWord(norm: String, word: String): Boolean =
        Regex("(^| )${Regex.escape(word)}( |$)").containsMatchIn(norm)

    private fun hasPhrase(norm: String, phrase: String): Boolean =
        Regex("(^| )${Regex.escape(phrase)}( |$)").containsMatchIn(norm)

    /** Devuelve 0..3 o null si la respuesta no corresponde claramente a una opción. */
    fun parseGad7(text: String): Int? {
        val n = normalize(text)
        if (n.isEmpty()) return null
        // El orden importa: frases largas antes que palabras sueltas ("casi todos" antes que
        // "todos", "ninguno" antes que "uno").
        if (hasPhrase(n, "casi todos") || hasPhrase(n, "todos los dias") ||
            hasPhrase(n, "casi siempre") || hasWord(n, "siempre")
        ) return 3
        if (hasPhrase(n, "mas de la mitad") || hasPhrase(n, "la mitad") ||
            hasPhrase(n, "la mayoria")
        ) return 2
        if (hasWord(n, "ningun") || hasWord(n, "ninguno") || hasWord(n, "ninguna") ||
            hasWord(n, "nunca") || hasPhrase(n, "para nada")
        ) return 0
        if (hasWord(n, "varios") || hasPhrase(n, "algunos dias") || hasPhrase(n, "a veces") ||
            hasPhrase(n, "pocos dias")
        ) return 1
        // Números sueltos 0..3 (dígitos o palabra).
        return when {
            hasWord(n, "cero") || hasWord(n, "0") -> 0
            hasWord(n, "uno") || hasWord(n, "1") -> 1
            hasWord(n, "dos") || hasWord(n, "2") -> 2
            hasWord(n, "tres") || hasWord(n, "3") -> 3
            else -> null
        }
    }

    // Sin "un"/"una": en "un siete" el artículo no es la respuesta.
    private val NUMBER_WORDS = mapOf(
        "cero" to 0, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
    )

    /**
     * Número de 0 a 10 de la respuesta, o null. Si la persona repite las anclas ("de cero a
     * diez, un siete"), se descartan 0 y 10 y se toma el último número restante.
     */
    fun parseSnrs(text: String): Int? {
        val numbers = normalize(text).split(" ").mapNotNull { token ->
            token.toIntOrNull()?.takeIf { it in 0..10 } ?: NUMBER_WORDS[token]
        }
        if (numbers.isEmpty()) return null
        if (numbers.size == 1) return numbers.first()
        val withoutAnchors = numbers.filter { it != 0 && it != 10 }
        return (withoutAnchors.ifEmpty { numbers }).last()
    }

    enum class YesNo { YES, NO }

    fun parseYesNo(text: String): YesNo? {
        val n = normalize(text)
        if (n.isEmpty()) return null
        if (hasPhrase(n, "otro momento") || hasWord(n, "despues") || hasWord(n, "luego") ||
            hasPhrase(n, "mas tarde") || hasPhrase(n, "ahora no") || hasWord(n, "no")
        ) return YesNo.NO
        if (hasWord(n, "si") || hasWord(n, "claro") || hasWord(n, "iniciemos") ||
            hasWord(n, "comencemos") || hasWord(n, "empecemos") || hasWord(n, "comenzar") ||
            hasWord(n, "iniciar") || hasPhrase(n, "de acuerdo") || hasWord(n, "dale") ||
            hasWord(n, "bueno") || hasWord(n, "ok")
        ) return YesNo.YES
        return null
    }

    private val RISK_PATTERNS = listOf(
        "suicid", "matarme", "quitarme la vida", "no quiero vivir", "hacerme dano",
        "lastimarme", "me quiero morir", "quiero morirme", "acabar con mi vida", "cortarme",
        "no vale la pena vivir", "mejor muerto", "mejor muerta",
    )

    /** Detección simple de frases de riesgo en cualquier respuesta hablada. */
    fun detectRisk(text: String): Boolean {
        val n = normalize(text)
        return RISK_PATTERNS.any { n.contains(it) }
    }
}
