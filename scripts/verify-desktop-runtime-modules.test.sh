#!/usr/bin/env bash
# Prueba de verify-desktop-runtime-modules.sh (CI-OC-RELEASE) con fichero `release` de jlink simulado (sin JDK ni Gradle):
# runtime completo (layout Linux y Windows) -> exit 0; sin java.sql / sin jdk.crypto.ec -> FAIL con el modulo; sin runtime,
# sin linea MODULES, linea duplicada o sin java.base -> FAIL. Un modulo con prefijo (java.sql.rowset) no cuenta como java.sql.
set -uo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
app() { # $1 = contenido de release (vacio = sin runtime); $2 = subdirectorio del runtime (por defecto lib/runtime, Linux)
  n=$((n + 1)); a="$tmp/app$n"; mkdir -p "$a"
  [ -z "$1" ] && return 0
  mkdir -p "$a/${2:-lib/runtime}"; printf '%s\n' "$1" > "$a/${2:-lib/runtime}/release"
}
ok() { bash scripts/verify-desktop-runtime-modules.sh "$a" >"$tmp/out" 2>&1 || { cat "$tmp/out"; echo "TEST FAIL: $1 deberia pasar"; exit 1; }; }
ko() { bash scripts/verify-desktop-runtime-modules.sh "$a" >"$tmp/out" 2>&1 && { echo "TEST FAIL: $1 paso"; exit 1; }
  grep -q "^FAIL: .*$2" "$tmp/out" || { cat "$tmp/out"; echo "TEST FAIL: $1 sin el mensaje '$2'"; exit 1; }; }
full="java.base java.desktop java.instrument java.logging java.management java.sql jdk.crypto.ec jdk.unsupported"
app "$(printf 'JAVA_VERSION="21.0.8"\nMODULES="%s"' "$full")"; ok "runtime completo (Linux)"
app "$(printf 'JAVA_VERSION="21.0.8"\r\nMODULES="%s"\r' "$full")" runtime; ok "runtime completo (Windows, CRLF)"
app "MODULES=\"${full/java.sql /java.sql.rowset }\""; ko "sin java.sql (el defecto de la MSI)" "faltan modulos: java.sql"
app "MODULES=\"${full/jdk.crypto.ec /}\""; ko "sin jdk.crypto.ec" "faltan modulos: jdk.crypto.ec"
app ""; ko "sin runtime embebido" "no hay runtime embebido"
app 'JAVA_VERSION="21.0.8"'; ko "release sin MODULES" "sin una unica linea MODULES"
app "$(printf 'MODULES="%s"\nMODULES="%s"' "$full" "$full")"; ko "MODULES duplicada" "sin una unica linea MODULES"
app 'MODULES="java.sql jdk.crypto.ec"'; ko "sin java.base" "no reconocible"
echo "verify-desktop-runtime-modules.test OK ($n escenarios)"
