#!/usr/bin/env bash
# CI-OC-RELEASE: el runtime embebido (jlink) de la distribucion de escritorio debe contener los modulos que el cliente usa.
# Sin java.sql la app empaquetada no encuentra DriverManager (SQLite) y falla al arrancar; sin jdk.crypto.ec fallan los
# handshakes TLS ECDHE. Lee la linea MODULES="..." del fichero `release` que jlink escribe en el propio runtime (jpackage
# aplica --strip-native-commands: el runtime NO trae bin/java, asi que `java --list-modules` no es posible); no lee Gradle.
# Uso: verify-desktop-runtime-modules.sh [<directorio app-image>]  (por defecto la salida de :composeApp:createDistributable)
set -uo pipefail
cd "$(dirname "$0")/.."
app="${1:-composeApp/build/compose/binaries/main/app/OposiControlAdmin}"
REQUIRED="java.sql java.management java.instrument jdk.unsupported jdk.crypto.ec"
rel=""
for c in "$app/lib/runtime/release" "$app/runtime/release" "$app/Contents/runtime/Contents/Home/release"; do # Linux, Windows, macOS
  [ -f "$c" ] && { rel="$c"; break; }
done
[ -n "$rel" ] || { echo "FAIL: no hay runtime embebido en $app (createDistributable no se ejecuto o cambio el layout)"; exit 1; }
line="$(tr -d '\r' < "$rel" | grep -E '^MODULES="[^"]*"$')"
[ "$(echo "$line" | grep -c .)" = 1 ] || { echo "FAIL: $rel sin una unica linea MODULES=\"...\" reconocible"; exit 1; }
mods="$(echo "$line" | sed -E 's/^MODULES="(.*)"$/\1/' | tr ' ' '\n')"
echo "$mods" | grep -qx 'java.base' || { echo "FAIL: MODULES de $rel no reconocible (sin java.base)"; exit 1; }
missing=""
for m in $REQUIRED; do echo "$mods" | grep -qx "$m" || missing="$missing $m"; done
[ -z "$missing" ] || { echo "FAIL: al runtime embebido le faltan modulos:$missing"; exit 1; }
echo "ok:   runtime embebido ($rel) con $REQUIRED ($(echo "$mods" | wc -l | tr -d ' ') modulos)"
