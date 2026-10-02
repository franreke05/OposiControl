#!/usr/bin/env bash
# INV-NETWORK-COMPLETE: los flavors Android staging/prod y la distribucion de escritorio de PRODUCCION hornean SUPABASE_URL desde el
# entorno y el BUILD falla (con el mensaje ESPECIFICO del guard) si falta. Ejecuta de verdad :composeApp:verifyNonDevSupabaseConfig y
# comprueba el cableado (dry-run) de las 4 variantes Android y de createDistributable -PoposiProduction=true.
# Coste: reutiliza el daemon de Gradle del job (sin --no-daemon; en CI el job `tests` ya lo calento) y la task guard es trivial.
set -u
cd "$(dirname "$0")/.."
fail=0; ok() { echo "ok   $1"; }; ko() { echo "FAIL $1"; fail=1; }
G=(./gradlew -q --no-configuration-cache)
# El guard solo puede leer el entorno: un .env local no debe enmascarar el caso "ausente".
if [ -f .env ] && grep -q '^SUPABASE_URL=' .env; then echo "SKIP: .env define SUPABASE_URL; ejecuta en un checkout limpio (CI)"; exit 0; fi
MSG_EMPTY='SUPABASE_URL es obligatorio para los flavors staging/prod'
MSG_HTTPS='SUPABASE_URL debe usar HTTPS en los flavors staging/prod'
MSG_LOCAL='SUPABASE_URL no puede apuntar a localhost en los flavors staging/prod'
# expect_fail <mensaje> <env...> -- <args gradle...>: falla de verdad Y con ese mensaje (no cualquier fallo de Gradle).
expect_fail() { local msg=$1; shift; local envs=(); while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift
  local out; out=$(env "${envs[@]}" "${G[@]}" "$@" 2>&1); local rc=$?
  [ $rc -ne 0 ] && grep -qF "$msg" <<<"$out"; }
expect_pass() { local envs=(); while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift; env "${envs[@]}" "${G[@]}" "$@" >/dev/null 2>&1; }
T=:composeApp:verifyNonDevSupabaseConfig
D=(:composeApp:createDistributable -PoposiProduction=true)
# Control negativo del propio comprobador: un fallo de Gradle AJENO (tarea inexistente) NO debe contar como el fallo esperado.
expect_fail "$MSG_EMPTY" SUPABASE_URL= -- :composeApp:noSuchTaskForNegativeControl && ko "el comprobador acepta un fallo ajeno" || ok "fallo ajeno no se acepta como el mensaje del guard"
expect_fail "$MSG_EMPTY" SUPABASE_URL= -- $T && ok "SUPABASE_URL vacio => build falla con su mensaje" || ko "SUPABASE_URL vacio no falla con el mensaje esperado"
expect_fail "$MSG_HTTPS" SUPABASE_URL=http://proj.supabase.co -- $T && ok "http => build falla con su mensaje" || ko "http no falla con el mensaje esperado"
expect_fail "$MSG_LOCAL" SUPABASE_URL=https://localhost:54321 -- $T && ok "localhost => build falla con su mensaje" || ko "localhost no falla con el mensaje esperado"
expect_pass SUPABASE_URL=https://proj.supabase.co -- $T && ok "https valido => build pasa" || ko "https valido falla"
# Distribucion de escritorio PROD: sin URL no se empaqueta (falla ANTES de jlink/jpackage, en la task guard).
expect_fail "$MSG_EMPTY" SUPABASE_URL= -- "${D[@]}" && ok "createDistributable prod sin SUPABASE_URL => build falla con su mensaje" || ko "createDistributable prod sin URL no falla con el mensaje esperado"
expect_fail "$MSG_HTTPS" SUPABASE_URL=http://proj.supabase.co -- "${D[@]}" && ok "createDistributable prod con http => falla" || ko "createDistributable prod con http no falla con el mensaje esperado"
for v in StagingDebug StagingRelease ProdDebug ProdRelease; do
  "${G[@]}" :composeApp:pre${v}Build --dry-run 2>&1 | grep -q 'verifyNonDevSupabaseConfig' && ok "pre${v}Build depende del guard" || ko "pre${v}Build sin guard"
done
"${G[@]}" :composeApp:preDevDebugBuild --dry-run 2>&1 | grep -q 'verifyNonDevSupabaseConfig' && ko "dev no debe depender del guard" || ok "dev sin guard"
"${G[@]}" "${D[@]}" --dry-run 2>&1 | grep -q 'verifyNonDevSupabaseConfig' && ok "createDistributable prod depende del guard" || ko "createDistributable prod sin guard"
"${G[@]}" :composeApp:createDistributable --dry-run 2>&1 | grep -q 'verifyNonDevSupabaseConfig' && ko "escritorio sin -PoposiProduction no debe depender del guard" || ok "escritorio dev sin guard"
exit $fail
