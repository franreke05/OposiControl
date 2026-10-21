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
# Las 202610*_slice2_*.sql (SLICE 2) y 202610*_slice7_*.sql (SLICE 7), mas abajo, tienen su propio manifiesto: toda otra 202610* es de Slice 3.
# Las 202610*_security_*.sql son endurecimiento transversal (pasada de seguridad), no pertenecen a ningun slice ni a su manifiesto.
actual3="$( (cd supabase/migrations 2>/dev/null && ls | grep -E '^202610.*\.sql$' | grep -Ev '^[0-9]+_(slice[27]|security|v1)_' | sed 's/\.sql$//' | sort) )"
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
# Las migraciones del runtime de examen deben seguir en el manifiesto (borrarlas de las dos partes a la vez no pasa). La
# 100200 (endurecimiento del gate: marcador de comando, sin congelado tardio) cierra un bypass de acceso: no es opcional.
for need in 20261008100000_slice3_attempt_runtime_schema 20261008100100_slice3_attempt_command_rpcs \
  20261008100200_slice3_attempt_hardening; do
  echo "$expected3" | grep -qx "$need" || { echo "FAIL: el manifiesto Slice 3 no lista $need"; exit 1; }
done
echo "ok:   manifiesto Slice 3 == migraciones 202610* ($(echo "$actual3" | wc -l | tr -d ' '))"
# SLICE 2: mismo contrato para supabase/SLICE2_MIGRATIONS.txt y los supabase/migrations/202610*_slice2_*.sql (lecturas de
# catalogo que se apoyan en el esquema de Slice 3: comparten prefijo de fecha y se distinguen por el nombre).
man2=supabase/SLICE2_MIGRATIONS.txt
[ -s "$man2" ] || { echo "FAIL: manifiesto vacio o ausente: $man2"; exit 1; }
expected2="$(tr -d '\r' < "$man2" | grep -Ev '^[[:space:]]*(#|$)' | sort)"
[ -n "$expected2" ] || { echo "FAIL: manifiesto Slice 2 sin entradas"; exit 1; }
actual2="$( (cd supabase/migrations 2>/dev/null && ls | grep -E '^202610[0-9]*_slice2_.*\.sql$' | sed 's/\.sql$//' | sort) )"
if [ "$actual2" != "$expected2" ]; then
  echo "FAIL: el manifiesto Slice 2 y supabase/migrations/202610*_slice2_*.sql difieren (< manifiesto, > real):"
  diff <(echo "$expected2") <(echo "$actual2")
  exit 1
fi
# Toda migracion Slice 2 es POSTERIOR a la ultima de Slice 3: la lectura de "mis examenes" exige el marcador de comando
# de la 20261008100200 (su PREFLIGHT se detiene sin el).
first2="$(echo "$expected2" | head -1 | cut -d_ -f1)"
# La dependencia real es el marcador de comando de la 20261008100200 (base del runtime); Slice 3 puede recibir migraciones
# posteriores (p. ej. 20261010100000) sin que eso obligue a renombrar las de Slice 2/7.
base3=20261008100200
[ "$first2" -gt "$base3" ] || { echo "FAIL: la migracion Slice 2 $first2 no es posterior a la base de Slice 3 ($base3)"; exit 1; }
# El runbook de despliegue cita CADA migracion Slice 2 por su version completa.
for v2 in $(echo "$expected2" | cut -d_ -f1); do
  grep -qF "$v2" "$dep" || { echo "FAIL: $dep no cita la migracion Slice 2 $v2"; exit 1; }
done
# La lectura de D2-13 COLECCION debe seguir en el manifiesto (sustituirla en las dos partes a la vez no pasa): el Ktor que
# la llama responde 503 sin ella.
for need in 20261008100300_slice2_my_exams_read_rpc 20261008100400_slice2_content_grant_client_write_revoke; do
  echo "$expected2" | grep -qx "$need" || { echo "FAIL: el manifiesto Slice 2 no lista $need"; exit 1; }
done
echo "ok:   manifiesto Slice 2 == migraciones 202610*_slice2_* ($(echo "$actual2" | wc -l | tr -d ' '))"
# SLICE 7: mismo contrato para supabase/SLICE7_MIGRATIONS.txt y los supabase/migrations/202610*_slice7_*.sql (lecturas de cartera:
# indice keyset del libro de movimientos de D6-02). Comparten prefijo de fecha con Slice 3/2 y se distinguen por el nombre.
man7=supabase/SLICE7_MIGRATIONS.txt
[ -s "$man7" ] || { echo "FAIL: manifiesto vacio o ausente: $man7"; exit 1; }
expected7="$(tr -d '\r' < "$man7" | grep -Ev '^[[:space:]]*(#|$)' | sort)"
[ -n "$expected7" ] || { echo "FAIL: manifiesto Slice 7 sin entradas"; exit 1; }
actual7="$( (cd supabase/migrations 2>/dev/null && ls | grep -E '^202610[0-9]*_slice7_.*.sql$' | sed 's/.sql$//' | sort) )"
if [ "$actual7" != "$expected7" ]; then
  echo "FAIL: el manifiesto Slice 7 y supabase/migrations/202610*_slice7_*.sql difieren (< manifiesto, > real):"
  diff <(echo "$expected7") <(echo "$actual7")
  exit 1
fi
# Toda migracion Slice 7 es POSTERIOR a la ultima de Slice 2 y de Slice 3 (se aplican despues).
first7="$(echo "$expected7" | head -1 | cut -d_ -f1)"
last2="$(echo "$expected2" | tail -1 | cut -d_ -f1)"
[ "$first7" -gt "$last2" ] || { echo "FAIL: la migracion Slice 7 $first7 no es posterior a la ultima de Slice 2 ($last2)"; exit 1; }
[ "$first7" -gt "$base3" ] || { echo "FAIL: la migracion Slice 7 $first7 no es posterior a la base de Slice 3 ($base3)"; exit 1; }
# El runbook de despliegue cita CADA migracion Slice 7 por su version completa.
for v7 in $(echo "$expected7" | cut -d_ -f1); do
  grep -qF "$v7" "$dep" || { echo "FAIL: $dep no cita la migracion Slice 7 $v7"; exit 1; }
done
# El indice keyset de D6-02 debe seguir en el manifiesto (sustituirlo en las dos partes a la vez no pasa).
for need in 20261008100500_slice7_wallet_ledger_keyset_index; do
  echo "$expected7" | grep -qx "$need" || { echo "FAIL: el manifiesto Slice 7 no lista $need"; exit 1; }
done
echo "ok:   manifiesto Slice 7 == migraciones 202610*_slice7_* ($(echo "$actual7" | wc -l | tr -d ' '))"
# V1 (propietario 2026-10-15): supabase/V1_MIGRATIONS.txt y los supabase/migrations/202610*_v1_*.sql (comunidad obligatoria, economia).
manv1=supabase/V1_MIGRATIONS.txt
[ -s "$manv1" ] || { echo "FAIL: manifiesto vacio o ausente: $manv1"; exit 1; }
expectedv1="$(tr -d '\r' < "$manv1" | grep -Ev '^[[:space:]]*(#|$)' | sort)"
[ -n "$expectedv1" ] || { echo "FAIL: manifiesto V1 sin entradas"; exit 1; }
actualv1="$( (cd supabase/migrations 2>/dev/null && ls | grep -E '^202610[0-9]*_v1_.*\.sql$' | sed 's/\.sql$//' | sort) )"
if [ "$actualv1" != "$expectedv1" ]; then
  echo "FAIL: el manifiesto V1 y supabase/migrations/202610*_v1_*.sql difieren (< manifiesto, > real):"
  diff <(echo "$expectedv1") <(echo "$actualv1")
  exit 1
fi
for vv1 in $(echo "$expectedv1" | cut -d_ -f1); do
  grep -qF "$vv1" "$dep" || { echo "FAIL: $dep no cita la migracion V1 $vv1"; exit 1; }
done
echo "ok:   manifiesto V1 == migraciones 202610*_v1_* ($(echo "$actualv1" | wc -l | tr -d ' '))"
