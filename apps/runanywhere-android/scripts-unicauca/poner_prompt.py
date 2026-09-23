"""Escribe el prompt de sistema en español en los ajustes de RunAnywhere (build debug).

Uso, desde la carpeta del proyecto y con el teléfono conectado por USB:
    python scripts-unicauca/poner_prompt.py
    python scripts-unicauca/poner_prompt.py <otro_prompt.txt>

Sin argumento usa scripts-unicauca/contenido/prompt_sistema_asistente.txt.
La app guarda el prompt en SharedPreferences (app_settings.xml, clave system_prompt).
Solo funciona con la versión de depuración (run-as). Detiene la app antes de escribir.
adb se busca en ANDROID_HOME, ANDROID_SDK_ROOT, la ruta por defecto de Android Studio
y por último en el PATH.
"""
import html
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

PKG = "com.runanywhere.runanywhereai"
PREFS = "shared_prefs/app_settings.xml"
DEFAULT_PROMPT = Path(__file__).resolve().parent / "contenido" / "prompt_sistema_asistente.txt"


def find_adb() -> str:
    exe = "adb.exe" if os.name == "nt" else "adb"
    home = Path.home()
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        home / "AppData" / "Local" / "Android" / "Sdk",  # Windows
        home / "Library" / "Android" / "sdk",  # macOS
        home / "Android" / "Sdk",  # Linux
    ]
    for root in filter(None, roots):
        candidate = Path(root) / "platform-tools" / exe
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("adb")
    if found:
        return found
    sys.exit("No se encontro adb. Defina ANDROID_HOME con la ruta del Android SDK.")


def main() -> None:
    adb = find_adb()
    prompt_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PROMPT
    prompt = prompt_path.read_text(encoding="utf-8").strip()
    subprocess.run([adb, "shell", "am", "force-stop", PKG], check=True)
    current = subprocess.run(
        [adb, "exec-out", f"run-as {PKG} cat {PREFS}"], capture_output=True
    ).stdout.decode("utf-8", errors="replace")
    if "<map" not in current:
        current = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    current = re.sub(r'[ \t]*<string name="system_prompt">.*?</string>\n?', "", current, flags=re.S)
    entry = f'    <string name="system_prompt">{html.escape(prompt, quote=True)}</string>\n'
    updated = current.replace("</map>", entry + "</map>")
    subprocess.run(
        [adb, "exec-in", f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > {PREFS}'"],
        input=updated.encode("utf-8"),
        check=True,
    )
    check = subprocess.run(
        [adb, "exec-out", f"run-as {PKG} cat {PREFS}"], capture_output=True
    ).stdout.decode("utf-8", errors="replace")
    print("OK: prompt escrito" if "system_prompt" in check else "NO SE ESCRIBIO")


if __name__ == "__main__":
    main()
