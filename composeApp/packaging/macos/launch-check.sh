#!/bin/bash
# Arranca la app con un entorno vacío (sin JAVA_HOME ni java en PATH), comprueba que
# sigue viva a los 40 s, que usa su runtime embebido y que dyld/AMFI no han
# rechazado ninguna librería por firma. Falla con código distinto de 0 si no.
#
# Uso: launch-check.sh /ruta/OposiControl.app /dir/salida etiqueta
set -uo pipefail
APP="${1:?Uso: $0 App.app dir-salida etiqueta}"
OUT="${2:?Falta el directorio de salida}"
LABEL="${3:?Falta la etiqueta}"
mkdir -p "$OUT"
LOG="$OUT/launch-$LABEL.log"
MAIN="$APP/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"
SINCE="$(date '+%Y-%m-%d %H:%M:%S')"

env -i HOME="$HOME" PATH=/usr/bin:/bin TMPDIR="${TMPDIR:-/tmp}" "$MAIN" > "$LOG" 2>&1 &
PID=$!
sleep 40
screencapture -x "$OUT/launch-$LABEL.png" 2>/dev/null || true

FAIL=0
if kill -0 "$PID" 2>/dev/null; then
  echo "[$LABEL] proceso vivo tras 40 s (pid $PID)"
  JVM="$(lsof -p "$PID" 2>/dev/null | grep -m1 libjvm.dylib | awk '{print $NF}')"
  echo "[$LABEL] libjvm cargada desde: $JVM"
  case "$JVM" in "$APP"/*) ;; *) echo "::error::[$LABEL] no usa el runtime embebido"; FAIL=1 ;; esac
  if lsof -a -p "$PID" -i TCP 2>/dev/null | grep -qE 'localhost:8080|127\.0\.0\.1:8080'; then
    echo "::error::[$LABEL] intenta hablar con un Ktor local"; FAIL=1
  fi
  kill "$PID"; sleep 3; kill -9 "$PID" 2>/dev/null || true
else
  wait "$PID"; echo "::error::[$LABEL] la app terminó al arrancar (código $?)"; FAIL=1
fi

# Los rechazos de firma los escribe dyld en la salida del proceso y AMFI en el log del sistema.
log show --start "$SINCE" --style compact --predicate 'process == "kernel" OR sender == "AppleMobileFileIntegrity" OR sender == "dyld"' 2>/dev/null \
  | grep -iE 'OposiControl|libjli|Team ID' > "$OUT/system-$LABEL.log" || true
for pattern in "different Team IDs" "code signature not valid for use in process"; do
  if grep -q "$pattern" "$LOG" "$OUT/system-$LABEL.log"; then
    echo "::error::[$LABEL] aparece «$pattern»"; FAIL=1
  else
    echo "[$LABEL] sin «$pattern»"
  fi
done
if grep -E 'Exception|Error:' "$LOG" | grep -v '^\s*at ' | head -20; then
  echo "::warning::[$LABEL] el arranque escribió excepciones (ver diagnostics)"
fi
exit "$FAIL"
