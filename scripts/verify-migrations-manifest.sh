#!/usr/bin/env bash
# MASTER4-ARCH-002: el manifiesto vendorizado de las migraciones Slice 1 (supabase/SLICE1_MIGRATIONS.txt, una version completa
# sin .sql por linea; copia IDENTICA de OposiBot docs/integration/canonical-implementation/slice1/SLICE1_MIGRATIONS.txt) debe
# coincidir EXACTAMENTE con los supabase/migrations/2026093*.sql reales. Se ejecuta en el CI de OposiControl (sin secretos,
# sin checkout cruzado): asi el manifiesto que OposiBot usa para sus docs queda atado a las migraciones reales.
# Glob vacio, entrada de mas o de menos = fallo (nunca vacuo). MIG_ROOT permite apuntarlo a otro arbol (lo usa la prueba).
set -uo pipefail
cd "${MIG_ROOT:-$(dirname "$0")/..}" || exit 2
man=supabase/SLICE1_MIGRATIONS.txt
[ -s "$man" ] || { echo "FAIL: manifiesto vacio o ausente: $man"; exit 1; }
expected="$(tr -d '\r' < "$man" | grep -Ev '^[[:space:]]*(#|$)' | sort)"
[ -n "$expected" ] || { echo "FAIL: manifiesto sin entradas"; exit 1; }
actual="$( (cd supabase/migrations 2>/dev/null && ls | grep -E '^2026093.*\.sql$' | sed 's/\.sql$//' | sort) )"
[ -n "$actual" ] || { echo "FAIL: sin migraciones 2026093*.sql en supabase/migrations"; exit 1; }
if [ "$actual" != "$expected" ]; then
  echo "FAIL: el manifiesto y supabase/migrations/2026093*.sql difieren (< manifiesto, > real):"
  diff <(echo "$expected") <(echo "$actual")
  exit 1
fi
echo "ok:   manifiesto Slice 1 == migraciones 2026093* ($(echo "$actual" | wc -l | tr -d ' '))"
