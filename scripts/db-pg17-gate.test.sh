#!/usr/bin/env bash
# Control negativo de db-pg17-gate.sh (DB-CI-PG17), con PostgreSQL 17 REAL (Docker): una copia sin tocar del arbol PASA y cada
# mutacion sembrada FALLA por SU motivo (mensaje FAIL concreto), nunca por otro. Desde la captura de MAIN del 2026-10-14
# (supabase/baseline/MAIN_MIGRATION_HISTORY.txt) las pendientes son 20261022100000 (revisiones de pregunta) y 20261022100100 (E2-P03);
#   manifiesto: la pendiente borrada de PENDING_MIGRATIONS.txt / una ya aplicada en MAIN anadida / un VERIFY ausente
#   (a) grant select de solicitudes_eliminacion_cuenta a authenticated en la pendiente -> frontera de assertions.sql
#   (a2) grant execute de idem_begin (objeto de MAIN) a authenticated -> la matriz grants/RLS de assertions.sql
#   (b) EXECUTE de cliente sobre app_record_attempt_answer en la pendiente -> su VERIFY
#   (c) error de sintaxis en la pendiente -> ON_ERROR_STOP
#   (d) ROLLBACK de la pendiente sin borrar revisiones_pregunta -> la huella tras el rollback no es la del bootstrap
#   (e) SELECT de cliente sobre revisiones_pregunta -> su VERIFY
#   (f) el congelado deja de ligar las preguntas a su revision -> la asercion de comportamiento de assertions.sql
#   (g) N3-3 / tarea 4: el cerrojo por clave de tienda MOVIDO por debajo de la busqueda (el texto sigue, el VERIFY de 102900 pasa) en la reversion
#       (mutA), el reembolso (mutA2) y el credito -> la carrera REAL de dos conexiones del paso 6 (R1 / R2 / R3-R4)
#   (h) N3-1: reconcile vuelve a bloquear la fila antes que la clave (texto de 103000 intacto) -> 40P01 en la carrera D1 del paso 6
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
rev=supabase/migrations/20261022100000_slice3_attempt_content_revisions.sql
pend=supabase/baseline/PENDING_MIGRATIONS.txt
fresh; sed -i 's/^20261022100000_slice3_attempt_content_revisions/20261015100000_security_audit_log_index/' "$w/$pend"; mutated "$w/$pend"
expect_fail "pendiente sustituida por una ya aplicada en MAIN" "PENDING_MIGRATIONS.txt y (repo - historial de MAIN) difieren"
fresh; sed -i '/^-- VERIFY-BEGIN/,/^-- VERIFY-END/d' "$w/$rev"; mutated "$w/$rev"
expect_fail "bloque VERIFY ausente" "20261022100000_slice3_attempt_content_revisions: bloque VERIFY ausente"
fresh; gate || { cat "$tmp/out"; echo "TEST FAIL: la copia sin mutar deberia pasar"; exit 1; }; echo "ok:   copia sin mutar pasa"
fresh; echo 'grant select on table public.solicitudes_eliminacion_cuenta to authenticated;' >> "$w/$rev"; mutated "$w/$rev"
expect_fail "(a) grant de cliente en la pendiente" "assertions.sql"
grep -q 'ASSERT FAILED: select \* from public.solicitudes_eliminacion_cuenta -> expected 42501' "$tmp/out" \
  || { echo "TEST FAIL: (a) sin la asercion de frontera anon/authenticated"; exit 1; }
fresh; echo 'grant execute on function public.idem_begin(uuid, text, text, text, interval, interval) to authenticated;' >> "$w/$rev"; mutated "$w/$rev"
expect_fail "(a2) EXECUTE de cliente sobre una RPC SECURITY DEFINER" "assertions.sql"
grep -q 'client EXECUTE on SECURITY DEFINER: idem_begin(uuid,text,text,text,interval,interval) authenticated' "$tmp/out" \
  || { echo "TEST FAIL: (a2) sin el mensaje de la matriz grants/RLS"; exit 1; }
fresh; echo 'grant execute on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) to authenticated;' >> "$w/$rev"; mutated "$w/$rev"
expect_fail "(b) EXECUTE de cliente sobre el comando de respuesta" "20261022100000_slice3_attempt_content_revisions: VERIFY con contadores distintos de 0"
grep -q 'VERIFY revision_commands_executable_by_clients = 1' "$tmp/out" || { echo "TEST FAIL: (b) sin el contador"; exit 1; }
fresh; echo 'selec 1;' >> "$w/$rev"; mutated "$w/$rev"
expect_fail "(c) error de sintaxis" "20261022100000_slice3_attempt_content_revisions no aplica"
fresh; sed -i '/^-- drop table if exists public.revisiones_pregunta;$/d' "$w/$rev"; mutated "$w/$rev"
expect_fail "(d) rollback incompleto" "el ROLLBACK falla\|tras los ROLLBACK el catalogo no vuelve al bootstrap"
fresh; echo 'grant select on table public.revisiones_pregunta to authenticated;' >> "$w/$rev"; mutated "$w/$rev"
expect_fail "(e) SELECT de cliente sobre las revisiones" "20261022100000_slice3_attempt_content_revisions: VERIFY con contadores distintos de 0"
grep -q 'VERIFY revisions_client_privileges = 1' "$tmp/out" || { echo "TEST FAIL: (e) sin el contador"; exit 1; }
fresh; sed -i 's/^      insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id)$/      insert into public.preguntas_intento (intento_id, pregunta_id, posicion)/; s/^      select p_intento_id, u.pregunta_id, u.ord::integer, r.id$/      select p_intento_id, u.pregunta_id, u.ord::integer/' "$w/$rev"; mutated "$w/$rev"
expect_fail "(f) el congelado no liga la revision" "assertions.sql"
grep -q 'every frozen question bound to a revision' "$tmp/out" || { echo "TEST FAIL: (f) sin la asercion de la revision"; exit 1; }
# (g) Mueve la linea del cerrojo `store-key:` de la funcion $2 justo antes de la primera linea de esa funcion que empieza por $3.
store_mig=supabase/migrations/20261022102900_v1_store_payments_concurrency.sql
move_lock() {
  awk -v fn="function public.$2(" -v marker="$3" '
    /^create (or replace )?function public\./ { cur = $0 }
    index(cur, fn) && /^  perform pg_advisory_xact_lock\(hashtextextended\(.store-key:/ && held == "" { held = $0; next }
    held != "" && !done && index(cur, fn) && index($0, marker) == 1 { print held; done = 1 }
    { print }
    END { if (held == "" || !done) exit 1 }' "$1" > "$1.mut" && mv "$1.mut" "$1"
}
fresh; move_lock "$w/$store_mig" app_store_refund_reversed "  perform pg_advisory_xact_lock(hashtextextended('wallet:'" || { echo "TEST FAIL: mutA no aplica"; exit 1; }; mutated "$w/$store_mig"
expect_fail "(g) mutA: cerrojo de la reversion movido bajo la busqueda" "carreras de tienda con dos conexiones"
grep -q "race R1\.1" "$tmp/out" || { echo "TEST FAIL: (g) mutA no la detecta R1"; exit 1; }
fresh; move_lock "$w/$store_mig" app_store_refund_purchase "  return query select r.outcome" || { echo "TEST FAIL: mutA2 no aplica"; exit 1; }; mutated "$w/$store_mig"
expect_fail "(g2) mutA2: cerrojo del reembolso movido bajo la busqueda" "carreras de tienda con dos conexiones"
grep -q "race R2\.1" "$tmp/out" || { echo "TEST FAIL: (g2) mutA2 no la detecta R2"; exit 1; }
fresh; move_lock "$w/$store_mig" app_store_credit_purchase "  return query select * from public.app_credit_payment_order(" || { echo "TEST FAIL: mutC no aplica"; exit 1; }; mutated "$w/$store_mig"
expect_fail "(g3) cerrojo del credito movido tras los huerfanos" "carreras de tienda con dos conexiones"
grep -q "race R[34]\.1" "$tmp/out" || { echo "TEST FAIL: (g3) no la detecta R3/R4"; exit 1; }
r3=supabase/migrations/20261022103000_v1_store_payments_round3.sql
fresh
{ sed -n '1,/^-- 4\. N3-1:/p' "$w/$r3" | sed '$d'
  sed -n '/^create function public.app_store_reconcile_orphans/,/^\$\$;/p' "$w/$store_mig" \
    | sed 's/^create function/create or replace function/; s/^begin$/begin\n  -- mutante: pg_try_advisory_xact_lock solo en el texto; la fila se bloquea antes que la clave/'
  sed -n '/^-- 5\. Subscriptions:/,$p' "$w/$r3"; } > "$w/$r3.mut" && mv "$w/$r3.mut" "$w/$r3"; mutated "$w/$r3"
expect_fail "(h) N3-1: reconcile fila-antes-que-clave" "carreras de tienda con dos conexiones"
grep -q "race D1\.[12]: .*40P01\|deadlock" "$tmp/out" || { grep "race" "$tmp/out"; echo "TEST FAIL: (h) sin el 40P01 de D1"; exit 1; }
echo "db-pg17-gate.test OK ($n escenarios)"
