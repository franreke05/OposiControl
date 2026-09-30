#!/usr/bin/env bash
# Prueba de verify-migrations-manifest.sh (MASTER4-ARCH-002): el repo pasa; entrada de mas/de menos/vacio/ausente falla.
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-migrations-manifest.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
fresh() { n=$((n + 1)); w="$tmp/w$n"; mkdir -p "$w/supabase/migrations" "$w/scripts"; cp supabase/SLICE1_MIGRATIONS.txt "$w/supabase/"; cp scripts/verify-migrations-manifest.sh "$w/scripts/"
  while IFS= read -r s; do [ -n "$s" ] && : > "$w/supabase/migrations/$s.sql"; done < supabase/SLICE1_MIGRATIONS.txt; }
expect_fail() { MIG_ROOT="$w" bash "$w/scripts/verify-migrations-manifest.sh" >"$tmp/out" 2>&1 && { echo "TEST FAIL: $1 paso"; exit 1; }; grep -q '^FAIL:' "$tmp/out" || { echo "TEST FAIL: $1 sin FAIL"; exit 1; }; }
fresh; MIG_ROOT="$w" bash "$w/scripts/verify-migrations-manifest.sh" >/dev/null || { echo "TEST FAIL: copia coincidente deberia pasar"; exit 1; }
fresh; : > "$w/supabase/migrations/20260930109999_extra.sql"; expect_fail "migracion de mas"
fresh; rm "$w/supabase/migrations/20260930101850_admin_sync_roles_audited.sql"; expect_fail "migracion de menos"
fresh; echo "20260930109999_solo_manifiesto" >> "$w/supabase/SLICE1_MIGRATIONS.txt"; expect_fail "entrada de manifiesto sin migracion"
fresh; : > "$w/supabase/SLICE1_MIGRATIONS.txt"; expect_fail "manifiesto vacio"
fresh; rm "$w/supabase/SLICE1_MIGRATIONS.txt"; expect_fail "manifiesto ausente"
fresh; rm "$w"/supabase/migrations/*.sql; expect_fail "directorio sin migraciones"
echo "verify-migrations-manifest.test OK ($n escenarios)"
