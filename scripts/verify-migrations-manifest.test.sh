#!/usr/bin/env bash
# Prueba de verify-migrations-manifest.sh (MASTER4-ARCH-002): el repo pasa; entrada de mas/de menos/vacio/ausente falla.
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-migrations-manifest.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
fresh() { n=$((n + 1)); w="$tmp/w$n"; mkdir -p "$w/supabase/migrations" "$w/scripts"; cp supabase/SLICE1_MIGRATIONS.txt "$w/supabase/"; cp scripts/verify-migrations-manifest.sh "$w/scripts/"; mkdir -p "$w/docs/engineering"; cp docs/engineering/DEPLOYMENT.md "$w/docs/engineering/"
  while IFS= read -r s; do [ -n "$s" ] && : > "$w/supabase/migrations/$s.sql"; done < supabase/SLICE1_MIGRATIONS.txt; }
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
echo "verify-migrations-manifest.test OK ($n escenarios)"
