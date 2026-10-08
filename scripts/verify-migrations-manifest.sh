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
# MASTER5-CICD-003: el rango que cita el runbook de despliegue debe terminar en la ULTIMA migracion del manifiesto.
dep=docs/engineering/DEPLOYMENT.md
last="$(echo "$expected" | tail -1 | cut -d_ -f1)"
# MASTER6-CICD-005: un DEPLOYMENT.md ausente (renombrado/borrado) FALLA; antes el `[ -f ]` hacia que la comprobacion se omitiera.
[ -f "$dep" ] || { echo "FAIL: $dep ausente"; exit 1; }
if ! grep -qF "20260930100000..${last#20260930}" "$dep"; then
  echo "FAIL: $dep no cita el rango 20260930100000..${last#20260930} (ultima migracion del manifiesto)"
  exit 1
fi
# Remediacion completa: las migraciones NUEVAS (101900, 102000, ronda 6 102100 y ronda 8 102200/102300) deben seguir en el manifiesto y el total no puede bajar de 16
# (si se borraran de las dos partes a la vez, la igualdad de arriba seguiria pasando).
for need in 20260930101900_admin_sync_roles_audited_expected 20260930102000_slice1_report_action_once 20260930102100_slice1_client_ddl_privileges_revoke \
  20260930102200_slice1_roles_usuario_client_write_revoke 20260930102300_slice1_report_insert_bounds; do
  echo "$expected" | grep -qx "$need" || { echo "FAIL: el manifiesto no lista $need"; exit 1; }
done
[ "$(echo "$expected" | wc -l | tr -d ' ')" -ge 16 ] || { echo "FAIL: el manifiesto tiene menos de 16 migraciones Slice 1"; exit 1; }
echo "ok:   manifiesto Slice 1 == migraciones 2026093* ($(echo "$actual" | wc -l | tr -d ' '))"
# SLICE 3: mismo contrato para supabase/SLICE3_MIGRATIONS.txt y los supabase/migrations/202610*.sql (prefijo propio: las
# migraciones de Slice 3 NO se renombran para caber en 2026093*). Manifiesto vacio/ausente, entrada de mas o de menos = fallo.
man3=supabase/SLICE3_MIGRATIONS.txt
[ -s "$man3" ] || { echo "FAIL: manifiesto vacio o ausente: $man3"; exit 1; }
expected3="$(tr -d '\r' < "$man3" | grep -Ev '^[[:space:]]*(#|$)' | sort)"
[ -n "$expected3" ] || { echo "FAIL: manifiesto Slice 3 sin entradas"; exit 1; }
actual3="$( (cd supabase/migrations 2>/dev/null && ls | grep -E '^202610.*\.sql$' | sed 's/\.sql$//' | sort) )"
if [ "$actual3" != "$expected3" ]; then
  echo "FAIL: el manifiesto Slice 3 y supabase/migrations/202610*.sql difieren (< manifiesto, > real):"
  diff <(echo "$expected3") <(echo "$actual3")
  exit 1
fi
# Toda migracion Slice 3 es POSTERIOR a la ultima de Slice 1 (se aplican despues de las pendientes de Slice 0/1).
first3="$(echo "$expected3" | head -1 | cut -d_ -f1)"
[ "$first3" -gt "$last" ] || { echo "FAIL: la migracion Slice 3 $first3 no es posterior a la ultima de Slice 1 ($last)"; exit 1; }
# El runbook de despliegue cita el rango Slice 3 hasta la ULTIMA migracion del manifiesto.
last3="$(echo "$expected3" | tail -1 | cut -d_ -f1)"
if ! grep -qF "${first3}..${last3#20261008}" "$dep"; then
  echo "FAIL: $dep no cita el rango Slice 3 ${first3}..${last3#20261008} (ultima migracion del manifiesto)"
  exit 1
fi
# Las dos migraciones del runtime de examen deben seguir en el manifiesto (borrarlas de las dos partes a la vez no pasa).
for need in 20261008100000_slice3_attempt_runtime_schema 20261008100100_slice3_attempt_command_rpcs; do
  echo "$expected3" | grep -qx "$need" || { echo "FAIL: el manifiesto Slice 3 no lista $need"; exit 1; }
done
echo "ok:   manifiesto Slice 3 == migraciones 202610* ($(echo "$actual3" | wc -l | tr -d ' '))"
