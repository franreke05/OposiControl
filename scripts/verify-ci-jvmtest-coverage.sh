#!/usr/bin/env bash
# CI-JVMTEST-COVERAGE: todo modulo Gradle con pruebas (src/jvmTest o src/commonTest bajo composeApp, core, domain, data o features)
# debe figurar como `:modulo:jvmTest` en la linea de pruebas del job `tests` de ci.yml (la que lleva --rerun-tasks) Y en la
# lista de --write-verification-metadata de ci.yml y de docs/engineering/DEPLOYMENT.md. Un modulo con pruebas que no corre
# en CI falla. Excepciones solo en scripts/ci-jvmtest-allowlist.tsv: `modulo<TAB>justificacion` (justificacion obligatoria;
# una entrada que ya esta en CI o que ya no tiene pruebas es obsoleta y tambien falla). CI_ROOT apunta a otro arbol (lo usa
# verify-ci-jvmtest-coverage.test.sh). Texto plano: sin parser de Kotlin ni de YAML.
set -uo pipefail
cd "${CI_ROOT:-$(dirname "$0")/..}" || exit 2
fail=0
bad() { echo "FAIL: $*"; fail=1; }
ci=.github/workflows/ci.yml
doc=docs/engineering/DEPLOYMENT.md
allow=scripts/ci-jvmtest-allowlist.tsv
[ -f "$ci" ] || { echo "FAIL: falta $ci"; exit 1; }

test_line="$(grep -E '^[[:space:]]*-[[:space:]]+run:[[:space:]]+\./gradlew[[:space:]].*--rerun-tasks' "$ci" | grep -v -- '--write-verification-metadata' || true)"
write_line="$(grep -E '^[[:space:]]*-[[:space:]]+run:[[:space:]]+\./gradlew[[:space:]].*--write-verification-metadata' "$ci" || true)"
doc_line="$(grep -F -- '--write-verification-metadata sha256' "$doc" 2>/dev/null | grep -F ':jvmTest' || true)"
[ "$(printf '%s\n' "$test_line" | grep -c .)" = 1 ] || bad "se esperaba exactamente 1 linea de pruebas gradle (con --rerun-tasks) en $ci"
[ "$(printf '%s\n' "$write_line" | grep -c .)" = 1 ] || bad "se esperaba exactamente 1 linea --write-verification-metadata en $ci"
[ -n "$doc_line" ] || bad "falta la lista --write-verification-metadata ... :jvmTest en $doc"

in_line() { printf '%s\n' "$2" | grep -Eq "(^|[[:space:]])$1:jvmTest([[:space:]]|\$|[\`])"; }
allowed() { [ -f "$allow" ] && grep -v '^#' "$allow" | awk -F'\t' -v m="$1" '$1==m' | grep -q .; }

modules="$(find composeApp core domain data features -maxdepth 4 -name build -prune -o -type d \( -path '*/src/jvmTest' -o -path '*/src/commonTest' \) -print 2>/dev/null \
  | sed 's#/src/.*##' | sort -u | tr / :)"
[ -n "$modules" ] || bad "no se encontro ningun modulo con pruebas (arbol inesperado)"
for m in $modules; do
  g=":$m"
  if allowed "$g"; then continue; fi
  in_line "$g" "$test_line"  || bad "$g tiene pruebas pero no figura como $g:jvmTest en la linea de pruebas de $ci"
  in_line "$g" "$write_line" || bad "$g:jvmTest falta en la linea --write-verification-metadata de $ci"
  in_line "$g" "$doc_line"   || bad "$g:jvmTest falta en la lista de $doc"
done

if [ -f "$allow" ]; then
  while IFS=$'\t' read -r mod why; do
    case "$mod" in ''|'#'*) continue ;; esac
    [ -n "${why// /}" ] || bad "allowlist: $mod sin justificacion"
    printf '%s\n' "$modules" | tr ' ' '\n' | grep -qx "${mod#:}" || bad "allowlist obsoleta: $mod ya no tiene pruebas"
    in_line "$mod" "$test_line" && bad "allowlist obsoleta: $mod ya figura en $ci"
  done < "$allow"
fi

[ "$fail" = 0 ] && echo "ok: todos los modulos con pruebas figuran en CI ($(printf '%s\n' "$modules" | wc -w) modulos)"
exit "$fail"
