#!/usr/bin/env bash
# V1 deploy (docs/engineering/V1_PRODUCTION_DEPLOY_PLAN.md, fase F4): aplica UNA migracion a MAIN, en una transaccion (la del
# propio fichero), con ON_ERROR_STOP, y despues su bloque VERIFY (todas las filas con 0) y el registro en
# supabase_migrations.schema_migrations. LO EJECUTA SOLO EL PROPIETARIO, una migracion cada vez, en el orden de la seccion 4 del plan.
#
#   export MAIN_DB_URL='postgresql://postgres.<ref>:<password>@<pooler-host>:5432/postgres?sslmode=require'   # session pooler, SOLO en su terminal
#   bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/<version>_<nombre>.sql            # pide teclear la version
#   bash ops/deploy/v1/apply-main-migration.sh --verify-only supabase/migrations/<...>.sql           # solo lectura: el VERIFY (antes o despues)
#
# Nunca `supabase db push`: aplicaria TODO lo que no figura en el historial, incluidas 20260730120000 y 20260923103000
# (divergencias conocidas, supabase/baseline/README.md) y las 20261023* en un orden no revisado.
# psql corre en la imagen Supabase PG17 ya presente en local (sin instalar nada). MAIN_DB_URL nunca se imprime.
# Ensayo local: REHEARSAL_CONTAINER=<contenedor> usa ese contenedor en vez de MAIN (lo usa rehearse-v1-deploy.sh).
set -uo pipefail
PG_IMAGE="public.ecr.aws/supabase/postgres:17.6.1.171"
mode=apply
[ "${1:-}" = "--verify-only" ] && { mode=verify; shift; }
f="${1:?uso: apply-main-migration.sh [--verify-only] supabase/migrations/<version>_<nombre>.sql}"
[ -s "$f" ] || { echo "ERROR: $f no existe"; exit 2; }
base="$(basename "$f" .sql)"; version="${base%%_*}"; name="${base#*_}"
[[ "$version" =~ ^[0-9]{14}$ ]] || { echo "ERROR: nombre de fichero sin version de 14 digitos"; exit 2; }

psql_run() { # stdin = SQL; nunca imprime la URL
  if [ -n "${REHEARSAL_CONTAINER:-}" ]; then
    docker exec -i "$REHEARSAL_CONTAINER" psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"
  else
    [ -n "${MAIN_DB_URL:-}" ] || { echo "ERROR: define MAIN_DB_URL en tu terminal (no se guarda en ningun fichero)" >&2; return 2; }
    MSYS_NO_PATHCONV=1 docker run --rm -i -e MAIN_DB_URL "$PG_IMAGE" sh -c 'exec psql "$MAIN_DB_URL" -X -q -v ON_ERROR_STOP=1 "$@"' sh "$@"
  fi
}
block() { # bloque comentado -- NAME-BEGIN/END (misma regla que scripts/db-pg17-gate.sh)
  awk -v n="$2" '$0 ~ "^[[:space:]]*-- "n"-END[[:space:]]*$" {e=1; exit} s {print} $0 ~ "^[[:space:]]*-- "n"-BEGIN[[:space:]]*$" {s=1} END {exit !(s && e)}' "$1" \
    | { bad=0; while IFS= read -r l; do l="${l%$'\r'}"; case "$l" in --*) l="${l#--}"; echo "${l# }" ;; *) bad=1 ;; esac; done; exit $bad; }
}
run_verify() { # imprime el VERIFY; rc 0 solo si hay filas y todas valen 0
  local out
  out="$( { echo 'begin read only;'; block "$f" VERIFY; echo 'commit;'; } | psql_run -At -F '|' 2>&1)" || { echo "$out"; echo "ERROR: el VERIFY no se ejecuta"; return 1; }
  out="$(echo "$out" | grep -v -E '^(BEGIN|COMMIT)$')"
  echo "$out" | sed 's/^/  VERIFY | /'
  [ -n "$out" ] || { echo "ERROR: VERIFY sin filas"; return 1; }
  echo "$out" | awk -F'|' '$2 != "0" {bad=1} END {exit bad}'
}
block "$f" VERIFY >/dev/null || { echo "ERROR: $base no tiene bloque VERIFY"; exit 2; }

if [ "$mode" = verify ]; then
  run_verify && echo "VERIFY = 0 ($base)" || echo "VERIFY con valores distintos de 0 ($base): normal ANTES de aplicar; tras aplicar es NO-GO"
  exit 0
fi

already="$(echo "select count(*) from supabase_migrations.schema_migrations where version = '$version';" | psql_run -At 2>&1)" \
  || { echo "$already"; echo "ERROR: no se puede leer el historial"; exit 1; }
[ "$already" = 0 ] || { echo "STOP: $version ya figura en el historial de MAIN; no se re-aplica (las aplicadas no se tocan)."; exit 1; }
echo "Fichero: $f"
echo "blob git: $(git hash-object "$f" | cut -c1-12)   (debe coincidir con la columna blob de la seccion 4 del plan)"
printf 'Teclea la version (%s) para APLICARLA en la base destino: ' "$version" >&2
IFS= read -r typed || { echo "sin confirmacion"; exit 1; }
[ "$typed" = "$version" ] || { echo "ERROR: version distinta; no se ha tocado nada."; exit 1; }
t0=$(date +%s)
if ! psql_run < "$f"; then
  echo "FALLO al aplicar $base: la transaccion del fichero se ha deshecho (nada cambio salvo que el error sea posterior al COMMIT)."
  echo "NO aplicar las siguientes. Anotar el mensaje (PREFLIGHT / lock_timeout / otro) y seguir la seccion 7 del plan."
  exit 1
fi
echo "aplicada en $(( $(date +%s) - t0 )) s"
if ! run_verify; then echo "NO-GO: VERIFY distinto de 0 tras aplicar $base. Seccion 7 del plan (ROLLBACK de esta migracion)."; exit 1; fi
echo "insert into supabase_migrations.schema_migrations (version, name) values ('$version', '$name') on conflict (version) do nothing;" | psql_run \
  || { echo "AVISO: aplicada y VERIFY = 0, pero NO registrada en el historial: registrarla a mano antes de seguir."; exit 1; }
echo "OK $base: aplicada, VERIFY = 0, registrada."
