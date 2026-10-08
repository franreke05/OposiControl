#!/usr/bin/env bash
# Control negativo de db-pg17-gate.sh (DB-CI-PG17), con PostgreSQL 17 REAL (Docker): una copia sin tocar del arbol PASA y cada
# mutacion sembrada FALLA por SU motivo (mensaje FAIL concreto), nunca por otro:
#   manifiesto: una pendiente borrada de PENDING_MIGRATIONS.txt / un VERIFY ausente  (sin contenedor, inmediato)
#   (a) grant select de solicitudes_eliminacion_cuenta a authenticated en la ULTIMA migracion -> frontera de assertions.sql
#   (a2) grant execute de idem_begin (sin VERIFY propio) a authenticated -> la matriz grants/RLS de assertions.sql
#   (b) el mismo grant al final de 20260930100300 -> el VERIFY de esa migracion (contador != 0)
#   (c) error de sintaxis en una pendiente -> ON_ERROR_STOP
#   (d) ROLLBACK de 102000 sin su drop function -> la huella tras el rollback no es la del bootstrap
#   (e) grant execute de una RPC de SLICE 3 a authenticated -> el VERIFY de 20261008100100
#   (f) el helper de congelado de SLICE 3 deja de excluir las anuladas -> la asercion de comportamiento de assertions.sql
#   (g) el endurecimiento de SLICE 3 (20261008100200) pierde la guarda marcador/origen de los comandos -> su VERIFY
#   (h) el trigger de inmutabilidad del marcador deja de rechazar -> la asercion de comportamiento de assertions.sql
set -uo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
fresh() { n=$((n + 1)); w="$tmp/w$n"; mkdir -p "$w/scripts"; cp -R supabase "$w/"; cp scripts/db-pg17-gate.sh "$w/scripts/"; }
gate() { MIG_ROOT="$w" bash "$w/scripts/db-pg17-gate.sh" >"$tmp/out" 2>&1; }
expect_fail() { # $1 = escenario, $2 = mensaje FAIL esperado
  gate && { cat "$tmp/out"; echo "TEST FAIL: $1 paso"; exit 1; }
  grep -q "^FAIL: .*$2" "$tmp/out" || { tail -20 "$tmp/out"; echo "TEST FAIL: $1 fallo por otro motivo (se esperaba: $2)"; exit 1; }
  echo "ok:   $1 -> $(grep '^FAIL:' "$tmp/out" | head -1)"
}
mutated() { cmp -s "$1" "${1#$w/}" && { echo "TEST FAIL: mutacion vacua en $1"; exit 1; }; return 0; }
last=supabase/migrations/20260930102000_slice1_report_action_once.sql
del=supabase/migrations/20260930100300_slice1_account_deletion_requests.sql
leak='grant select on table public.solicitudes_eliminacion_cuenta to authenticated;'

fresh; sed -i '/^20260930101600_/d' "$w/supabase/baseline/PENDING_MIGRATIONS.txt"; mutated "$w/supabase/baseline/PENDING_MIGRATIONS.txt"
expect_fail "pendiente borrada del manifiesto" "PENDING_MIGRATIONS.txt y supabase/migrations difieren"
fresh; sed -i '/^-- VERIFY-BEGIN/,/^-- VERIFY-END/d' "$w/$del"; mutated "$w/$del"
expect_fail "bloque VERIFY ausente" "20260930100300_slice1_account_deletion_requests: bloque VERIFY ausente"

fresh; gate || { cat "$tmp/out"; echo "TEST FAIL: la copia sin mutar deberia pasar"; exit 1; }; echo "ok:   copia sin mutar pasa"
fresh; echo "$leak" >> "$w/$last"; mutated "$w/$last"
expect_fail "(a) grant de cliente en la ultima migracion" "assertions.sql"
grep -q 'ASSERT FAILED: select \* from public.solicitudes_eliminacion_cuenta -> expected 42501' "$tmp/out" \
  || { echo "TEST FAIL: (a) sin la asercion de frontera anon/authenticated"; exit 1; }
fresh; echo 'grant execute on function public.idem_begin(uuid, text, text, text, interval, interval) to authenticated;' >> "$w/$last"; mutated "$w/$last"
expect_fail "(a2) EXECUTE de cliente sobre una RPC SECURITY DEFINER" "assertions.sql"
grep -q 'client EXECUTE on SECURITY DEFINER: idem_begin(uuid,text,text,text,interval,interval) authenticated' "$tmp/out" \
  || { echo "TEST FAIL: (a2) sin el mensaje de la matriz grants/RLS"; exit 1; }
fresh; echo "$leak" >> "$w/$del"; mutated "$w/$del"
expect_fail "(b) grant de cliente en 100300" "20260930100300_slice1_account_deletion_requests: VERIFY con contadores distintos de 0"
fresh; echo 'selec 1;' >> "$w/supabase/migrations/20260930100500_slice1_audit_hardening.sql"; mutated "$w/supabase/migrations/20260930100500_slice1_audit_hardening.sql"
expect_fail "(c) error de sintaxis" "20260930100500_slice1_audit_hardening no aplica"
fresh; sed -i '/^-- drop function if exists public.admin_record_report_action/d' "$w/$last"; mutated "$w/$last"
expect_fail "(d) rollback incompleto" "tras los ROLLBACK el catalogo no vuelve al bootstrap"
# SLICE 3 (20261008100000 + 20261008100100): (e) EXECUTE de cliente sobre una RPC de comando -> su VERIFY; (f) el conjunto
# congelado deja de excluir las anuladas -> la asercion de comportamiento de assertions.sql.
s3=supabase/migrations/20261008100100_slice3_attempt_command_rpcs.sql
fresh; echo 'grant execute on function public.app_finish_exam_attempt(uuid, uuid) to authenticated;' >> "$w/$s3"; mutated "$w/$s3"
expect_fail "(e) EXECUTE de cliente sobre una RPC de Slice 3" "20261008100100_slice3_attempt_command_rpcs: VERIFY con contadores distintos de 0"
fresh; sed -i 's/^    where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa$/    where pe.examen_id = p_examen_id and p.activa/' "$w/$s3"; mutated "$w/$s3"
expect_fail "(f) el intento congela tambien las preguntas anuladas" "assertions.sql"
grep -q 'frozen set = active and not annulled' "$tmp/out" || { echo "TEST FAIL: (f) sin la asercion del conjunto congelado"; exit 1; }
# GATE SLICE 3 (20261008100200): (g) los comandos vuelven a servir un intento que el arranque canonico no creo -> su VERIFY;
# (h) el marcador se puede fabricar con un UPDATE -> la asercion de comportamiento.
s3h=supabase/migrations/20261008100200_slice3_attempt_hardening.sql
fresh; sed -i "/^    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null$/d" "$w/$s3h"; mutated "$w/$s3h"
expect_fail "(g) los comandos de Slice 3 sin la guarda de marcador/origen" "20261008100200_slice3_attempt_hardening: VERIFY con contadores distintos de 0"
grep -q 'VERIFY hardening_command_without_marker_or_origin_guard = 3' "$tmp/out" || { echo "TEST FAIL: (g) sin el contador de la guarda"; exit 1; }
fresh; sed -i 's/^  if new.creado_por_comando is distinct from old.creado_por_comando then$/  if false then/' "$w/$s3h"; mutated "$w/$s3h"
expect_fail "(h) el marcador de comando deja de ser inmutable" "assertions.sql"
grep -q 'INTENTO_CREADO_POR_COMANDO_INMUTABLE' "$tmp/out" || { echo "TEST FAIL: (h) sin la asercion del marcador inmutable"; exit 1; }
echo "db-pg17-gate.test OK ($n escenarios)"
