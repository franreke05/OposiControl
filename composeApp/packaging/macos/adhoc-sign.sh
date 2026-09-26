#!/bin/bash
# Firma ad-hoc COHERENTE de la build interna sin Developer ID (UNSIGNED_INTERNAL_BUILD).
#
# Problema que resuelve: jpackage deja el lanzador firmado ad-hoc (sin Team ID)
# mientras el runtime embebido conserva la firma del proveedor del JDK (con su
# Team ID). En macOS eso termina en
#   "libjli.dylib: code signature not valid for use in process ...
#    mapping process and mapped file (non-platform) have different Team IDs"
# Aquí se vuelve a firmar TODO ad-hoc, de dentro hacia fuera como recomienda Apple
# para código anidado (no con --deep): primero cada Mach-O suelto, del más
# profundo al menos profundo; después el runtime como bundle; al final la app.
# Todos quedan sin Team ID, así que ninguno es "de otro equipo".
#
# Sin Hardened Runtime a propósito: sólo lo exige la notarización, y con él macOS
# aplica "library validation", que rechaza las librerías que la JVM extrae en
# tiempo de ejecución (Skiko, SQLite) porque vienen firmadas por sus autores.
# La build profesional será Developer ID + Hardened Runtime + notarización.
#
# Uso: adhoc-sign.sh /ruta/OposiControl.app /ruta/entitlements.plist
set -euo pipefail
APP="${1:?Uso: $0 App.app entitlements.plist}"
ENTITLEMENTS="${2:?Falta el fichero de entitlements}"
MAIN="$APP/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"

# 1. Mach-O sueltos (dylibs del runtime, libjli, jspawnhelper, ...), el más profundo primero.
find "$APP/Contents" -type f ! -path "$MAIN" -print0 |
  while IFS= read -r -d '' f; do
    if file -b "$f" | grep -q 'Mach-O'; then printf '%d\t%s\n' "$(tr -cd '/' <<<"$f" | wc -c)" "$f"; fi
  done |
  sort -rn | cut -f2- |
  while IFS= read -r f; do
    codesign --force --sign - --timestamp=none "$f"
  done

# 2. El runtime Java es un bundle propio (Contents/runtime/Contents/Info.plist).
if [ -f "$APP/Contents/runtime/Contents/Info.plist" ]; then
  codesign --force --sign - --timestamp=none "$APP/Contents/runtime"
fi

# 3. La app al final: firma el lanzador principal y sella el bundle.
codesign --force --sign - --timestamp=none --entitlements "$ENTITLEMENTS" "$APP"
