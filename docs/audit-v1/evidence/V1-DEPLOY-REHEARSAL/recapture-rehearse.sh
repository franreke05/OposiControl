#!/usr/bin/env bash
# F3.6 (V1_PRODUCTION_DEPLOY_PLAN): re-ensayo sobre la RECAPTURA de MAIN y diff de la huella de partida. Local, sin MAIN ni red:
# solo lee el capture.json que deja `lanzar-v1.sh recaptura` (catalogo de solo lectura, sin filas ni secretos).
#   1. capture.json -> baseline_main.sql (scripts/db-main-catalog-assemble.mjs) y su sha256;
#   2. rehearse-v1-deploy.sh con esa captura: las 4 ya aplicadas fuera del historial siguen fuera del orden pero NO se recargan
#      (PREAPPLIED_IN_BASELINE=1: se exige su VERIFY = 0 sobre la captura); 100100 no se ejecuta; borrado manual de RB-16;
#   3. diff de fingerprint_start.txt contra la referencia ensayada (run-2026-10-22b/fingerprint_start.txt, captura 2026-10-14 +
#      las 4 + RB-16, mismo arbol de migraciones).
# Uso (Git Bash, raiz del worktree del SHA aprobado): bash docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/recapture-rehearse.sh <dir con capture.json>
# Salida: RECAPTURE-REHEARSAL OK y FINGERPRINT IDENTICO, o el diff (< referencia, > recaptura) para explicar linea a linea antes de GO-4.
set -uo pipefail
D="${1:?directorio de la recaptura (con capture.json)}"
cd "$(dirname "$0")/../../../.." || exit 2
HERE=docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL
[ -s "$D/capture.json" ] || { echo "FAIL: falta $D/capture.json"; exit 1; }
node scripts/db-main-catalog-assemble.mjs "$D/capture.json" > "$D/baseline_main.sql" || { echo "FAIL: assemble"; exit 1; }
SHA="$(sha256sum "$D/baseline_main.sql" | cut -c1-64)"
echo "baseline_main.sql sha256 $SHA"
OUT="${OUT:-/tmp/v1-rehearsal-recapture}"
MAIN_BASELINE="$D/baseline_main.sql" MAIN_BASELINE_SHA="$SHA" \
PREAPPLIED_ON_MAIN="20261023100000 20261023100300 20261023100400 20261023100500" PREAPPLIED_IN_BASELINE=1 \
NEVER_RUN="20261023100100" MANUAL_STORAGE_DROP=1 \
  bash "$HERE/rehearse-v1-deploy.sh" "$OUT" || { echo "FAIL: re-ensayo sobre la recaptura (ver $OUT/rehearsal.log)"; exit 1; }
echo "RECAPTURE-REHEARSAL OK"
if diff --strip-trailing-cr "$HERE/run-2026-10-22b/fingerprint_start.txt" "$OUT/fingerprint_start.txt" > "$OUT/fingerprint_diff.txt"; then
  echo "FINGERPRINT IDENTICO a la referencia ensayada"
else
  echo "FINGERPRINT DIFIERE ($(grep -c '^[<>]' "$OUT/fingerprint_diff.txt") lineas, < referencia, > recaptura): $OUT/fingerprint_diff.txt"
  echo "Cada linea se explica antes de GO-4 (deriva sin explicar = NO-GO, F3)."
  exit 3
fi
