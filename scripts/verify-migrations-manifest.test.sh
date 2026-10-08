#!/usr/bin/env bash
# Prueba de verify-migrations-manifest.sh (MASTER4-ARCH-002): el repo pasa; entrada de mas/de menos/vacio/ausente falla.
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-migrations-manifest.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
fresh() { n=$((n + 1)); w="$tmp/w$n"; mkdir -p "$w/supabase/migrations" "$w/scripts"; cp supabase/SLICE1_MIGRATIONS.txt supabase/SLICE2_MIGRATIONS.txt supabase/SLICE3_MIGRATIONS.txt supabase/SLICE7_MIGRATIONS.txt "$w/supabase/"; cp scripts/verify-migrations-manifest.sh "$w/scripts/"; mkdir -p "$w/docs/engineering"; cp docs/engineering/DEPLOYMENT.md "$w/docs/engineering/"
  while IFS= read -r s; do [ -n "$s" ] && : > "$w/supabase/migrations/$s.sql"; done < <(cat supabase/SLICE1_MIGRATIONS.txt supabase/SLICE2_MIGRATIONS.txt supabase/SLICE3_MIGRATIONS.txt supabase/SLICE7_MIGRATIONS.txt); }
expect_fail() { MIG_ROOT="$w" bash "$w/scripts/verify-migrations-manifest.sh" >"$tmp/out" 2>&1 && { echo "TEST FAIL: $1 paso"; exit 1; }; grep -q '^FAIL:' "$tmp/out" || { echo "TEST FAIL: $1 sin FAIL"; exit 1; }; }
fresh; MIG_ROOT="$w" bash "$w/scripts/verify-migrations-manifest.sh" >/dev/null || { echo "TEST FAIL: copia coincidente deberia pasar"; exit 1; }
fresh; : > "$w/supabase/migrations/20260930109999_extra.sql"; expect_fail "migracion de mas"
fresh; rm "$w/supabase/migrations/20260930101850_admin_sync_roles_audited.sql"; expect_fail "migracion de menos"
fresh; echo "20260930109999_solo_manifiesto" >> "$w/supabase/SLICE1_MIGRATIONS.txt"; expect_fail "entrada de manifiesto sin migracion"
fresh; : > "$w/supabase/SLICE1_MIGRATIONS.txt"; expect_fail "manifiesto vacio"
fresh; rm "$w/supabase/SLICE1_MIGRATIONS.txt"; expect_fail "manifiesto ausente"
fresh; rm "$w"/supabase/migrations/*.sql; expect_fail "directorio sin migraciones"
fresh; sed -i 's/20260930100000..102300/20260930100000..102100/' "$w/docs/engineering/DEPLOYMENT.md"; cmp -s "$w/docs/engineering/DEPLOYMENT.md" docs/engineering/DEPLOYMENT.md && { echo "TEST FAIL: mutacion vacua"; exit 1; }; expect_fail "DEPLOYMENT con rango antiguo (MASTER5-CICD-003)"
fresh; rm "$w/docs/engineering/DEPLOYMENT.md"; expect_fail "DEPLOYMENT ausente (MASTER6-CICD-005)"; grep -q 'DEPLOYMENT.md ausente' "$tmp/out" || { echo "TEST FAIL: DEPLOYMENT ausente sin su mensaje"; exit 1; }
fresh; sed -i '/20260930101900_/d' "$w/supabase/SLICE1_MIGRATIONS.txt"; rm "$w/supabase/migrations/20260930101900_admin_sync_roles_audited_expected.sql"; expect_fail "101900 borrada del manifiesto y de las migraciones a la vez"; grep -q 'no lista 20260930101900' "$tmp/out" || { echo "TEST FAIL: 101900 ausente sin su mensaje"; exit 1; }
fresh; sed -i '/20260930102100_/d' "$w/supabase/SLICE1_MIGRATIONS.txt"; rm "$w/supabase/migrations/20260930102100_slice1_client_ddl_privileges_revoke.sql"; expect_fail "102100 borrada del manifiesto y de las migraciones a la vez (ronda 6)"; grep -q 'no lista 20260930102100' "$tmp/out" || { echo "TEST FAIL: 102100 ausente sin su mensaje"; exit 1; }
fresh; sed -i '/20260930100100_/d' "$w/supabase/SLICE1_MIGRATIONS.txt"; rm "$w"/supabase/migrations/20260930100100_*.sql; expect_fail "menos de 16 migraciones coherentes"; grep -q 'menos de 16' "$tmp/out" || { echo "TEST FAIL: conteo sin su mensaje"; exit 1; }
# Ronda 8 (R8b-DB): 102200 o 102300 borradas del manifiesto y de las migraciones a la vez (y el rango de DEPLOYMENT ajustado) fallan con su mensaje.
fresh; sed -i '/20260930102300_/d' "$w/supabase/SLICE1_MIGRATIONS.txt"; rm "$w/supabase/migrations/20260930102300_slice1_report_insert_bounds.sql"; sed -i 's/20260930100000..102300/20260930100000..102200/' "$w/docs/engineering/DEPLOYMENT.md"; expect_fail "102300 borrada del manifiesto y de las migraciones a la vez (ronda 8)"; grep -q 'no lista 20260930102300' "$tmp/out" || { echo "TEST FAIL: 102300 ausente sin su mensaje"; exit 1; }
fresh; sed -i '/20260930102200_/d' "$w/supabase/SLICE1_MIGRATIONS.txt"; rm "$w/supabase/migrations/20260930102200_slice1_roles_usuario_client_write_revoke.sql"; expect_fail "102200 borrada del manifiesto y de las migraciones a la vez (ronda 8)"; grep -q 'no lista 20260930102200' "$tmp/out" || { echo "TEST FAIL: 102200 ausente sin su mensaje"; exit 1; }
# SLICE 3: el manifiesto supabase/SLICE3_MIGRATIONS.txt queda atado a supabase/migrations/202610*.sql y al rango de DEPLOYMENT.md.
fresh; : > "$w/supabase/migrations/20261008109999_extra.sql"; expect_fail "migracion Slice 3 de mas"; grep -q 'manifiesto Slice 3 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 3 de mas sin su mensaje"; exit 1; }
fresh; rm "$w/supabase/migrations/20261008100100_slice3_attempt_command_rpcs.sql"; expect_fail "migracion Slice 3 de menos"
fresh; echo "20261008109999_solo_manifiesto" >> "$w/supabase/SLICE3_MIGRATIONS.txt"; expect_fail "entrada de manifiesto Slice 3 sin migracion"
fresh; : > "$w/supabase/SLICE3_MIGRATIONS.txt"; expect_fail "manifiesto Slice 3 vacio"
fresh; rm "$w/supabase/SLICE3_MIGRATIONS.txt"; expect_fail "manifiesto Slice 3 ausente"; grep -q 'SLICE3_MIGRATIONS.txt' "$tmp/out" || { echo "TEST FAIL: manifiesto Slice 3 ausente sin su mensaje"; exit 1; }
fresh; sed -i 's/20261008100000..100200/20261008100000..100100/' "$w/docs/engineering/DEPLOYMENT.md"; cmp -s "$w/docs/engineering/DEPLOYMENT.md" docs/engineering/DEPLOYMENT.md && { echo "TEST FAIL: mutacion vacua"; exit 1; }; expect_fail "DEPLOYMENT con rango Slice 3 antiguo"; grep -q 'no cita el rango Slice 3' "$tmp/out" || { echo "TEST FAIL: rango Slice 3 sin su mensaje"; exit 1; }
# Borrada de las dos partes a la vez (la ultima sigue siendo la 100200, el rango de DEPLOYMENT no cambia): la igualdad pasa, la lista de obligatorias no.
fresh; sed -i '/20261008100100_/d' "$w/supabase/SLICE3_MIGRATIONS.txt"; rm "$w/supabase/migrations/20261008100100_slice3_attempt_command_rpcs.sql"; expect_fail "100100 de Slice 3 borrada del manifiesto y de las migraciones a la vez"; grep -q 'no lista 20261008100100' "$tmp/out" || { echo "TEST FAIL: 100100 Slice 3 ausente sin su mensaje"; exit 1; }
# GATE SLICE 3: el endurecimiento (100200) tampoco se puede quitar de las dos partes y del runbook a la vez.
fresh; sed -i '/20261008100200_/d' "$w/supabase/SLICE3_MIGRATIONS.txt"; rm "$w/supabase/migrations/20261008100200_slice3_attempt_hardening.sql"; sed -i 's/20261008100000..100200/20261008100000..100100/' "$w/docs/engineering/DEPLOYMENT.md"; cmp -s "$w/docs/engineering/DEPLOYMENT.md" docs/engineering/DEPLOYMENT.md && { echo "TEST FAIL: mutacion vacua"; exit 1; }; expect_fail "100200 de Slice 3 borrada del manifiesto, de las migraciones y del runbook a la vez"; grep -q 'no lista 20261008100200' "$tmp/out" || { echo "TEST FAIL: 100200 Slice 3 ausente sin su mensaje"; exit 1; }
fresh; mv "$w/supabase/migrations/20261008100000_slice3_attempt_runtime_schema.sql" "$w/supabase/migrations/20260930109000_slice3_attempt_runtime_schema.sql"; expect_fail "migracion Slice 3 renombrada al rango de Slice 1"
# SLICE 2: el manifiesto supabase/SLICE2_MIGRATIONS.txt queda atado a supabase/migrations/202610*_slice2_*.sql y a DEPLOYMENT.md.
s2=20261008100300_slice2_my_exams_read_rpc
fresh; : > "$w/supabase/migrations/20261008109998_slice2_extra.sql"; expect_fail "migracion Slice 2 de mas"; grep -q 'manifiesto Slice 2 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 2 de mas sin su mensaje"; exit 1; }
fresh; rm "$w/supabase/migrations/$s2.sql"; expect_fail "migracion Slice 2 de menos"; grep -q 'manifiesto Slice 2 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 2 de menos sin su mensaje"; exit 1; }
fresh; echo "20261008109998_slice2_solo_manifiesto" >> "$w/supabase/SLICE2_MIGRATIONS.txt"; expect_fail "entrada de manifiesto Slice 2 sin migracion"
fresh; : > "$w/supabase/SLICE2_MIGRATIONS.txt"; expect_fail "manifiesto Slice 2 vacio"
fresh; rm "$w/supabase/SLICE2_MIGRATIONS.txt"; expect_fail "manifiesto Slice 2 ausente"; grep -q 'SLICE2_MIGRATIONS.txt' "$tmp/out" || { echo "TEST FAIL: manifiesto Slice 2 ausente sin su mensaje"; exit 1; }
# Una migracion Slice 2 listada tambien en el manifiesto de Slice 3 no pasa: cada 202610* pertenece a UN manifiesto, por su nombre.
fresh; echo "$s2" >> "$w/supabase/SLICE3_MIGRATIONS.txt"; expect_fail "migracion Slice 2 listada en el manifiesto Slice 3"; grep -q 'manifiesto Slice 3 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 2 en el manifiesto Slice 3 sin su mensaje"; exit 1; }
fresh; sed -i 's/20261008100300/2026100810030X/g' "$w/docs/engineering/DEPLOYMENT.md"; cmp -s "$w/docs/engineering/DEPLOYMENT.md" docs/engineering/DEPLOYMENT.md && { echo "TEST FAIL: mutacion vacua"; exit 1; }; expect_fail "DEPLOYMENT sin la migracion Slice 2"; grep -q 'no cita la migracion Slice 2 20261008100300' "$tmp/out" || { echo "TEST FAIL: DEPLOYMENT Slice 2 sin su mensaje"; exit 1; }
# Sustituida por otra en las dos partes a la vez (la igualdad pasa y el runbook sigue citando la version): la lista de obligatorias no.
fresh; grep -v "^$s2" "$w/supabase/SLICE2_MIGRATIONS.txt" > "$tmp/s2m"; echo "20261008100300_slice2_otra" >> "$tmp/s2m"; sort "$tmp/s2m" > "$w/supabase/SLICE2_MIGRATIONS.txt"; mv "$w/supabase/migrations/$s2.sql" "$w/supabase/migrations/20261008100300_slice2_otra.sql"; expect_fail "lectura de D2-13 sustituida en el manifiesto y en las migraciones a la vez"; grep -q "no lista $s2" "$tmp/out" || { echo "TEST FAIL: Slice 2 obligatoria ausente sin su mensaje"; exit 1; }
# Una migracion Slice 2 anterior a la ultima de Slice 3 (no tendria el marcador de comando de la 100200) falla por su orden.
fresh; grep -v "^$s2" "$w/supabase/SLICE2_MIGRATIONS.txt" > "$tmp/s2m"; echo "20261008100150_slice2_my_exams_read_rpc" >> "$tmp/s2m"; sort "$tmp/s2m" > "$w/supabase/SLICE2_MIGRATIONS.txt"; mv "$w/supabase/migrations/$s2.sql" "$w/supabase/migrations/20261008100150_slice2_my_exams_read_rpc.sql"; expect_fail "migracion Slice 2 anterior a la ultima de Slice 3"; grep -q 'no es posterior a la ultima de Slice 3' "$tmp/out" || { echo "TEST FAIL: orden Slice 2 sin su mensaje"; exit 1; }
# SLICE 7: el manifiesto supabase/SLICE7_MIGRATIONS.txt queda atado a supabase/migrations/202610*_slice7_*.sql y a DEPLOYMENT.md.
s7=20261008100500_slice7_wallet_ledger_keyset_index
fresh; : > "$w/supabase/migrations/20261008109997_slice7_extra.sql"; expect_fail "migracion Slice 7 de mas"; grep -q 'manifiesto Slice 7 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 7 de mas sin su mensaje"; exit 1; }
fresh; rm "$w/supabase/migrations/$s7.sql"; expect_fail "migracion Slice 7 de menos"; grep -q 'manifiesto Slice 7 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 7 de menos sin su mensaje"; exit 1; }
fresh; echo "20261008109997_slice7_solo_manifiesto" >> "$w/supabase/SLICE7_MIGRATIONS.txt"; expect_fail "entrada de manifiesto Slice 7 sin migracion"
fresh; : > "$w/supabase/SLICE7_MIGRATIONS.txt"; expect_fail "manifiesto Slice 7 vacio"
fresh; rm "$w/supabase/SLICE7_MIGRATIONS.txt"; expect_fail "manifiesto Slice 7 ausente"; grep -q 'SLICE7_MIGRATIONS.txt' "$tmp/out" || { echo "TEST FAIL: manifiesto Slice 7 ausente sin su mensaje"; exit 1; }
# Una migracion Slice 7 listada en el manifiesto de Slice 3 no pasa: cada 202610* pertenece a UN manifiesto, por su nombre.
fresh; echo "$s7" >> "$w/supabase/SLICE3_MIGRATIONS.txt"; expect_fail "migracion Slice 7 listada en el manifiesto Slice 3"; grep -q 'manifiesto Slice 3 y supabase/migrations/202610' "$tmp/out" || { echo "TEST FAIL: Slice 7 en el manifiesto Slice 3 sin su mensaje"; exit 1; }
fresh; sed -i 's/20261008100500/2026100810050X/g' "$w/docs/engineering/DEPLOYMENT.md"; cmp -s "$w/docs/engineering/DEPLOYMENT.md" docs/engineering/DEPLOYMENT.md && { echo "TEST FAIL: mutacion vacua"; exit 1; }; expect_fail "DEPLOYMENT sin citar la migracion Slice 7"; grep -q 'no cita la migracion Slice 7' "$tmp/out" || { echo "TEST FAIL: Slice 7 sin cita sin su mensaje"; exit 1; }
# Sustituida por otra en las dos partes a la vez: la lista de obligatorias no.
fresh; echo "20261008100500_slice7_otra" > "$w/supabase/SLICE7_MIGRATIONS.txt"; mv "$w/supabase/migrations/$s7.sql" "$w/supabase/migrations/20261008100500_slice7_otra.sql"; sed -i 's/20261008100500/20261008100500 20261008100500_slice7_otra/' "$w/docs/engineering/DEPLOYMENT.md"; expect_fail "indice keyset de D6-02 sustituido en el manifiesto y en las migraciones a la vez"; grep -q "no lista $s7" "$tmp/out" || { echo "TEST FAIL: Slice 7 obligatoria ausente sin su mensaje"; exit 1; }
# Una migracion Slice 7 anterior a la ultima de Slice 2 falla por su orden.
fresh; echo "20261008100350_slice7_wallet_ledger_keyset_index" > "$w/supabase/SLICE7_MIGRATIONS.txt"; mv "$w/supabase/migrations/$s7.sql" "$w/supabase/migrations/20261008100350_slice7_wallet_ledger_keyset_index.sql"; sed -i 's/20261008100500/20261008100350/' "$w/docs/engineering/DEPLOYMENT.md"; expect_fail "migracion Slice 7 anterior a la ultima de Slice 2"; grep -q 'no es posterior a la ultima de Slice 2' "$tmp/out" || { echo "TEST FAIL: orden Slice 7 sin su mensaje"; exit 1; }
echo "verify-migrations-manifest.test OK ($n escenarios)"
