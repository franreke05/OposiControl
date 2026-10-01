#!/usr/bin/env bash
# SEC-001: una prueba protegida con Assume (p. ej. las de permisos POSIX 0700/0600) se SALTA en silencio si el host no la admite;
# Gradle lo cuenta como verde. Este paso exige que cada prueba nombrada se haya EJECUTADO y PASADO en el informe JUnit XML de
# Gradle: <testcase name="..."/> autocerrado (= pasada). Ausente, <skipped/>, <failure> o <error> -> FAIL. Varias
# coincidencias del mismo nombre -> FAIL (ambiguo). El nombre es LITERAL (Kotlin MPP anade "[jvm]"): index(), nunca regex.
# Uso: assert-junit-executed.sh <TEST-...xml> <nombreDePrueba>...
set -uo pipefail
xml="${1:-}"; shift || true
[ -f "$xml" ] || { echo "FAIL: informe JUnit ausente: $xml"; exit 1; }
[ "$#" -gt 0 ] || { echo "FAIL: sin pruebas que comprobar"; exit 1; }
rc=0
for t in "$@"; do
  r="$(tr -d '\r' < "$xml" | tr '\n' ' ' | awk -v t="$t" 'BEGIN { RS = "<testcase " }
    index($0, "name=\"" t "\"") == 1 { n++; h = substr($0, 1, index($0, ">")); if (h ~ /\/>$/) ok++ }
    END { print n + 0, ok + 0 }')"
  if [ "${r% *}" != 1 ]; then echo "FAIL: $t aparece ${r% *} veces en $xml"; rc=1; continue; fi
  if [ "${r#* }" = 1 ]; then echo "ok:   $t ejecutada y pasada"
  else echo "FAIL: $t no se ejecuto (saltada por Assume) o no paso"; rc=1; fi
done
exit $rc
