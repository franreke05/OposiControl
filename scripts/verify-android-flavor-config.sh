#!/usr/bin/env bash
# INV-NETWORK-COMPLETE: los flavors Android staging/prod hornean SUPABASE_URL desde el entorno y el BUILD falla si falta.
# Ejecuta de verdad la tarea guard (:composeApp:verifyNonDevSupabaseConfig) y comprueba el cableado (dry-run) de las 4 variantes.
set -u
cd "$(dirname "$0")/.."
fail=0; ok() { echo "ok   $1"; }; ko() { echo "FAIL $1"; fail=1; }
G=(./gradlew -q --no-daemon --no-configuration-cache)
# El guard solo puede leer el entorno: un .env local no debe enmascarar el caso "ausente".
if [ -f .env ] && grep -q '^SUPABASE_URL=' .env; then echo "SKIP: .env define SUPABASE_URL; ejecuta en un checkout limpio (CI)"; exit 0; fi
run() { env "$@" "${G[@]}" :composeApp:verifyNonDevSupabaseConfig >/dev/null 2>&1; }
run SUPABASE_URL=; [ $? -ne 0 ] && ok "SUPABASE_URL vacio => build falla" || ko "SUPABASE_URL vacio no falla"
run SUPABASE_URL=http://proj.supabase.co; [ $? -ne 0 ] && ok "http => build falla" || ko "http no falla"
run SUPABASE_URL=https://localhost:54321; [ $? -ne 0 ] && ok "localhost => build falla" || ko "localhost no falla"
run SUPABASE_URL=https://proj.supabase.co; [ $? -eq 0 ] && ok "https valido => build pasa" || ko "https valido falla"
for v in StagingDebug StagingRelease ProdDebug ProdRelease; do
  "${G[@]}" :composeApp:pre${v}Build --dry-run 2>&1 | grep -q 'verifyNonDevSupabaseConfig' && ok "pre${v}Build depende del guard" || ko "pre${v}Build sin guard"
done
"${G[@]}" :composeApp:preDevDebugBuild --dry-run 2>&1 | grep -q 'verifyNonDevSupabaseConfig' && ko "dev no debe depender del guard" || ok "dev sin guard"
exit $fail
