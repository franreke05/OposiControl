#!/usr/bin/env bash
# Prueba de verify-ci-jvmtest-coverage.sh: el repo pasa; quitar un modulo de cualquiera de las tres listas, un modulo nuevo con
# pruebas sin CI, una entrada de allowlist sin justificacion u obsoleta -> FAIL; una excepcion justificada pasa.
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-ci-jvmtest-coverage.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
fresh() {
  n=$((n + 1)); w="$tmp/w$n"; mkdir -p "$w/.github/workflows" "$w/docs/engineering" "$w/scripts"
  cp .github/workflows/ci.yml "$w/.github/workflows/"; cp docs/engineering/DEPLOYMENT.md "$w/docs/engineering/"
  cp scripts/ci-jvmtest-allowlist.tsv "$w/scripts/"
  while read -r d; do mkdir -p "$w/$d"; done < <(find composeApp core domain data features -maxdepth 4 -name build -prune -o -type d \( -path '*/src/jvmTest' -o -path '*/src/commonTest' \) -print)
}
ko() { # ko <descripcion> -- el arbol $w debe FALLAR con FAIL
  CI_ROOT="$w" bash scripts/verify-ci-jvmtest-coverage.sh >"$tmp/out" 2>&1 && { echo "TEST FAIL: $1 paso"; cat "$tmp/out"; exit 1; }
  grep -q '^FAIL:' "$tmp/out" || { echo "TEST FAIL: $1 sin FAIL"; exit 1; }
}
ok() { CI_ROOT="$w" bash scripts/verify-ci-jvmtest-coverage.sh >"$tmp/out" 2>&1 || { echo "TEST FAIL: $1 deberia pasar"; cat "$tmp/out"; exit 1; }; }

fresh; ok "copia limpia"
# 1) Quitar :core:common:jvmTest solo de la linea de pruebas (sigue en las otras dos).
fresh; sed -i '/--rerun-tasks/ s# :core:common:jvmTest##' "$w/.github/workflows/ci.yml" && ko "core:common fuera de la linea de pruebas"
grep -q ':core:common' "$tmp/out" || { echo "TEST FAIL: no nombra el modulo"; exit 1; }
# 2) Solo de la lista write-verification-metadata.
fresh; sed -i '/--write-verification-metadata/ s# :core:common:jvmTest##' "$w/.github/workflows/ci.yml" && ko "core:common fuera de la lista de verificacion"
# 3) Solo de DEPLOYMENT.md.
fresh; sed -i '/--write-verification-metadata/ s# :core:common:jvmTest##' "$w/docs/engineering/DEPLOYMENT.md" && ko "core:common fuera de DEPLOYMENT.md"
# 4) Modulo nuevo con pruebas que nadie anadio a CI.
fresh; mkdir -p "$w/features/newmod/src/commonTest" && ko "modulo nuevo sin CI"
# 5) Un modulo que solo tiene sufijo coincidente (:features:users:jvmTestX) no cuenta.
fresh; sed -i '/--rerun-tasks/ s#:core:common:jvmTest#:core:common:jvmTestX#' "$w/.github/workflows/ci.yml" && ko "sufijo no exacto"
# 6) Excepcion con justificacion: pasa; sin justificacion u obsoleta: falla.
fresh; sed -i '/--rerun-tasks/ s# :core:common:jvmTest##' "$w/.github/workflows/ci.yml"; printf ':core:common\tfixture: justificada\n' >> "$w/scripts/ci-jvmtest-allowlist.tsv"; ok "excepcion justificada"
fresh; sed -i '/--rerun-tasks/ s# :core:common:jvmTest##' "$w/.github/workflows/ci.yml"; printf ':core:common\t \n' >> "$w/scripts/ci-jvmtest-allowlist.tsv"; ko "excepcion sin justificacion"
fresh; printf ':core:common\tobsoleta: ya esta en CI\n' >> "$w/scripts/ci-jvmtest-allowlist.tsv"; ko "excepcion obsoleta"
fresh; printf ':features:ghost\tno existe\n' >> "$w/scripts/ci-jvmtest-allowlist.tsv"; ko "excepcion de modulo inexistente"
# 7) RC-13: una cuarentena con comodin (modulo o clase entera) falla; la exacta (la real del repo) pasa.
fresh; mkdir -p "$w/features/exams"; cp features/exams/build.gradle.kts "$w/features/exams/"; ok "cuarentena exacta de un test"
fresh; mkdir -p "$w/features/exams"; printf 'tasks.withType<Test>().configureEach { filter { excludeTestsMatching("com.x.*") } }
' > "$w/features/exams/build.gradle.kts"; ko "cuarentena con comodin de paquete"
grep -q 'comodin' "$tmp/out" || { echo "TEST FAIL: no nombra el comodin"; exit 1; }
fresh; mkdir -p "$w/features/exams"; printf 'filter {
  excludeTestsMatching(
    "com.x.Clase." +
      "*",
  )
}
' > "$w/features/exams/build.gradle.kts"; ko "cuarentena con comodin en cadena multilinea"
echo "verify-ci-jvmtest-coverage.test OK"
