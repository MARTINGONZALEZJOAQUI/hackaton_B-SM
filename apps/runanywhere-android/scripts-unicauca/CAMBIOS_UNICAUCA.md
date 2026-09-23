# Cambios del prototipo Unicauca sobre RunAnywhere AI

Esta carpeta es una versión modificada de RunAnywhere AI para Android
(Copyright (c) 2025 RunAnywhere, Inc.), distribuida bajo la RunAnywhere License que está en
`LICENSE`. Este archivo describe las modificaciones, como pide la sección 4 de esa licencia.

Base: RunanywhereAI/runanywhere-android, commit 263cec9 (SDK 0.20.24, AGP 9.2.1, Kotlin 2.4.0).
Rama de trabajo original: `unicauca/test-voz-es`.

## Qué se agregó (no altera las pantallas originales)

- `ui/screens/voicetest/` (nuevo):
  - `VoiceTestContent.kt`: textos oficiales del GAD-7 en español (Pfizer, sin permiso requerido),
    escala SNRS-11 de estrés momentáneo, interpretación determinista de respuestas, puntaje,
    bandas (0-4, 5-9, 10-14, 15-21), corte ≥ 10, detección de frases de riesgo.
  - `VoiceTestViewModel.kt`: flujo aviso → SNRS-11 → GAD-7 → resultado → oferta de respiración
    → respiración 4 s / 6 s por 3 min (estrategia 3.1) → despedida. Protocolo de crisis
    (Línea 192 opción 4 y 123). Detección de fin de habla por nivel de audio.
  - `SpanishSpeaker.kt`: voz de Android (TextToSpeech) en español, preferiblemente sin conexión.
  - `VoiceTestScreen.kt`: interfaz con respaldo táctil, círculo de respiración con anillos guía.
- `ui/navigation/Destinations.kt`, `AppNavHost.kt`, `ui/screens/system_ui/AppTopBar.kt`:
  ruta `VoiceTest`, entrada "Test por voz" en el menú lateral y título de la barra.
- `util/VoiceLanguage.kt` + `ui/screens/stt/SttViewModel.kt`: en Transcripción, si el teléfono
  está en español y el modelo es Canary 180M, se pide "es"; en cualquier otro caso queda "en"
  como en el original.
- `gradle.properties`: verificación de dependencias en modo `lenient` y tiempos de espera de red
  de 180 s (ver "Compilación").
- `scripts-unicauca/` (nuevo): este archivo, el script `poner_prompt.py` y la carpeta
  `contenido/`.

## Por qué no se cambió Talk ni Read aloud

En el SDK 0.20.24:
- Talk (sesión de voz del SDK en teléfonos sin NPU) usa un prompt fijo en inglés dentro del
  núcleo C++ y no recibe el prompt de Ajustes ni el idioma.
- Las voces Sherpa (Piper, Supertonic, Kokoro) no reciben el idioma en la síntesis, y la voz del
  sistema vuelve a en-US en cada llamada. Por eso el test usa TextToSpeech de Android directo.

## Contenido para configurar la app (`scripts-unicauca/contenido/`)

- `prompt_sistema_asistente.txt`: prompt de sistema en español (trato de usted). Se pega en
  Settings → System prompt, o se escribe con `python scripts-unicauca/poner_prompt.py`
  (teléfono conectado por USB, build debug).
- `GAD7_puntuacion_lineas_de_ayuda.txt` y `SNRS11_escala_estres_momentaneo.txt`: documentos
  para la pantalla Documents (RAG). Se copian al teléfono, por ejemplo a `Download/Unicauca/`,
  y se agregan con "+ Add document". Documents los guarda solo en memoria: hay que volver a
  agregarlos cada vez que se abre la app.

## Compilación

- Android Studio con soporte para AGP 9.2 y Gradle 9.6 (Panda 4, 2025.3.4, o más reciente; se
  usó Quail 4, 2026.1.4). Gradle descarga por su cuenta el JDK 21 que usa el daemon
  (`gradle/gradle-daemon-jvm.properties`).
- `gradle.properties` usa `org.gradle.dependency.verification=lenient` porque
  `gradle/verification-metadata.xml` solo trae aapt2 para Linux y macOS y en Windows la
  verificación estricta falla con `aapt2-*-windows.jar`. En modo lenient Gradle anota la
  diferencia y sigue; no cambia nada de la app.
- La ruta del proyecto no debe tener tildes ni caracteres como `&` (el plugin de Android lo
  rechaza en Windows).
- Línea de comandos: `gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Limitación conocida

En el Test por voz el micrófono puede captar el final de la voz de la app y tomarlo como
respuesta. Si pasa, conviene bajar un poco el volumen o responder con los botones.
