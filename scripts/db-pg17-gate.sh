#!/usr/bin/env bash
# DB-CI-PG17: gate REPRODUCIBLE de migraciones en PostgreSQL 17 (imagen de Supabase fijada por digest), sin secretos ni MAIN.
#   1. Manifiesto: supabase/baseline/PENDING_MIGRATIONS.txt == supabase/migrations/2026092810*.sql + 2026093*.sql, y su parte
#      2026093* == supabase/SLICE1_MIGRATIONS.txt; cada pendiente tiene bloque ROLLBACK y VERIFY (salvo NO_VERIFY, abajo).
#   2. Arranca un contenedor nuevo, carga supabase/baseline/main_shape_subset.sql con ON_ERROR_STOP=1 en UNA transaccion
#      (cero errores tolerados) y toma la huella de catalogo F0.
#   3. Aplica cada pendiente EN ORDEN con ON_ERROR_STOP=1 y ejecuta su VERIFY justo despues: cada fila debe ser
#      `<check>|0|...`; un valor distinto de 0, un resultado vacio o un bloque ausente FALLA. Huella F1.
#   4. supabase/baseline/assertions.sql (transaccion con ROLLBACK): comportamiento + matriz de grants/RLS (ASSERT = fallo).
#   5. ROLLBACK de cada pendiente en orden inverso -> la huella debe volver a F0; re-aplicar + VERIFY -> debe volver a F1.
# Cobertura y limites (frente al gate local completo): supabase/baseline/README.md.
# MIG_ROOT apunta el gate a otro arbol (lo usa db-pg17-gate.test.sh); PG17_IMAGE permite fijar otra imagen.
set -uo pipefail
cd "${MIG_ROOT:-$(dirname "$0")/..}" || exit 2
IMAGE="${PG17_IMAGE:-public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9}" # 17.6.1.171
# 20260928100100 (ya publicada) es anterior a la convencion VERIFY; su tabla la cubre assertions.sql (RLS, sin grants de cliente).
NO_VERIFY="20260928100100_idempotency_keys"
fail() { echo "FAIL: $*"; exit 1; }

boot=supabase/baseline/main_shape_subset.sql; pend=supabase/baseline/PENDING_MIGRATIONS.txt; man=supabase/SLICE1_MIGRATIONS.txt
for f in "$boot" "$pend" "$man" supabase/baseline/assertions.sql; do [ -s "$f" ] || fail "$f vacio o ausente"; done
pending="$(tr -d '\r' < "$pend" | grep -Ev '^[[:space:]]*(#|$)')"
[ "$pending" = "$(echo "$pending" | sort -u)" ] || fail "$pend no esta ordenado o tiene duplicados"
actual="$( (cd supabase/migrations && ls | grep -E '^(2026092810|2026093).*\.sql$' | sed 's/\.sql$//' | sort) )"
[ "$pending" = "$actual" ] || { echo "FAIL: $pend y supabase/migrations difieren (< pendientes, > real):"; diff <(echo "$pending") <(echo "$actual"); exit 1; }
[ "$(echo "$pending" | grep '^2026093')" = "$(tr -d '\r' < "$man" | grep -Ev '^[[:space:]]*(#|$)' | sort)" ] || fail "$pend (2026093*) y $man difieren"

block() { # bloque comentado `-- NAME-BEGIN/END` (misma regla que Slice0Db.bloqueComentado): toda linea debe empezar por --
  awk -v n="$2" '$0 ~ "^[[:space:]]*-- "n"-END[[:space:]]*$" {e=1; exit} s {print} $0 ~ "^[[:space:]]*-- "n"-BEGIN[[:space:]]*$" {s=1} END {exit !(s && e)}' "$1" \
    | { bad=0; while IFS= read -r l; do l="${l%$'\r'}"; case "$l" in --*) l="${l#--}"; echo "${l# }" ;; *) bad=1 ;; esac; done; exit $bad; }
}
for v in $pending; do
  f="supabase/migrations/$v.sql"
  block "$f" ROLLBACK >/dev/null || fail "$v: bloque ROLLBACK ausente o con lineas sin comentar"
  if [ "$v" != "$NO_VERIFY" ]; then block "$f" VERIFY >/dev/null || fail "$v: bloque VERIFY ausente o con lineas sin comentar"; fi
done
echo "ok:   manifiesto ($(echo "$pending" | wc -l | tr -d ' ') pendientes) y bloques VERIFY/ROLLBACK"

ctr="db-pg17-gate-$$-$RANDOM"
tmp="$(mktemp -d)"; err="$tmp/err"
trap 'docker rm -f "$ctr" >/dev/null 2>&1; rm -rf "$tmp"' EXIT
docker run -d --name "$ctr" -e POSTGRES_PASSWORD=postgres "$IMAGE" >/dev/null || fail "no arranca $IMAGE"
psqlc() { docker exec -i "$ctr" psql -U postgres -h localhost -d postgres -X -q -v ON_ERROR_STOP=1 "$@"; }
ready=0
for _ in $(seq 1 90); do # TCP solo responde tras los scripts de init de la imagen (el servidor temporal no escucha en TCP)
  [ "$(psqlc -Atc "select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')" 2>/dev/null)" = 3 ] \
    && [ "$(psqlc -Atc "select to_regclass('auth.users') is not null" 2>/dev/null)" = t ] && { ready=1; break; }
  sleep 2
done
[ "$ready" = 1 ] || { docker logs "$ctr" 2>&1 | tail -20; fail "PostgreSQL 17 no quedo listo"; }
echo "ok:   $(psqlc -Atc 'select version()' | cut -d' ' -f1-2)"

fingerprint() { # $1 = fichero: catalogo de public/private por NOMBRE (no cuerpos: un rollback verbatim reformatea el texto)
  psqlc -At > "$tmp/fp.raw" 2>"$err" <<'SQL' || { cat "$err"; fail "la huella de catalogo no se puede calcular"; }
select 'col:'||table_schema||'.'||table_name||'.'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') from information_schema.columns where table_schema in ('public','private');
select 'con:'||conrelid::regclass::text||'.'||conname||':'||pg_get_constraintdef(oid) from pg_constraint where connamespace in ('public'::regnamespace,'private'::regnamespace);
select 'idx:'||schemaname||'.'||tablename||'.'||indexname||':'||indexdef from pg_indexes where schemaname in ('public','private');
select 'trg:'||tgrelid::regclass::text||'.'||tgname||':'||tgfoid::regproc::text from pg_trigger where not tgisinternal and (tgrelid::regclass::text not like 'auth.%' or tgrelid = 'auth.users'::regclass);
select 'fn:'||p.oid::regprocedure::text||':'||p.prosecdef::text||':'||coalesce(p.proconfig::text,'')||':'||coalesce(p.proacl::text,'') from pg_proc p where p.pronamespace in ('public'::regnamespace,'private'::regnamespace);
select 'pol:'||schemaname||'.'||tablename||'.'||policyname||':'||cmd||':'||roles::text||':'||coalesce(qual,'')||':'||coalesce(with_check,'') from pg_policies where schemaname in ('public','private');
select 'rel:'||c.oid::regclass::text||':'||c.relkind::text||':'||c.relrowsecurity::text||':'||coalesce(c.relacl::text,'') from pg_class c where c.relnamespace in ('public'::regnamespace,'private'::regnamespace) and c.relkind in ('r','p','v','S');
SQL
  sort "$tmp/fp.raw" > "$1"; [ -s "$1" ] || fail "huella de catalogo vacia"
}
same() { cmp -s "$1" "$2" || { diff "$1" "$2" | head -20; fail "$3"; }; }
verify() { # $1 = version: ejecuta su VERIFY y exige >=1 fila y failures (columna 2) = 0 en todas
  local out
  [ "$1" = "$NO_VERIFY" ] && return 0
  out="$(block "supabase/migrations/$1.sql" VERIFY | psqlc -At -F '|' 2>&1)" || { echo "$out"; fail "$1: el VERIFY no se ejecuta"; }
  [ -n "$out" ] || fail "$1: VERIFY sin filas"
  echo "$out" | awk -F'|' '$2 != "0" {bad=1; print "  VERIFY " $1 " = " $2} END {exit bad}' || fail "$1: VERIFY con contadores distintos de 0"
}
apply_all() {
  for v in $pending; do
    psqlc < "supabase/migrations/$v.sql" >/dev/null 2>"$err" || { cat "$err"; fail "$v no aplica (ON_ERROR_STOP)"; }
    verify "$v"
  done
}

psqlc -1 < "$boot" >/dev/null 2>"$err" || { cat "$err"; fail "$boot no carga con ON_ERROR_STOP=1"; }
fingerprint "$tmp/f0"
apply_all
fingerprint "$tmp/f1"
echo "ok:   bootstrap + $(echo "$pending" | wc -l | tr -d ' ') migraciones aplicadas en orden; VERIFY = 0"
psqlc < supabase/baseline/assertions.sql >/dev/null 2>"$err" || { grep -E 'ERROR|ASSERT' "$err"; fail "assertions.sql"; }
echo "ok:   assertions.sql (comportamiento + grants/RLS)"
for v in $(echo "$pending" | sort -r); do
  block "supabase/migrations/$v.sql" ROLLBACK | psqlc >/dev/null 2>"$err" || { cat "$err"; fail "$v: el ROLLBACK falla"; }
done
fingerprint "$tmp/f2"; same "$tmp/f0" "$tmp/f2" "tras los ROLLBACK el catalogo no vuelve al bootstrap (< bootstrap, > tras rollback)"
apply_all
fingerprint "$tmp/f3"; same "$tmp/f1" "$tmp/f3" "re-aplicar tras el rollback no reproduce el mismo catalogo (< primera, > re-aplicada)"
echo "ok:   rollback inverso == bootstrap; re-aplicacion == primera aplicacion (VERIFY = 0)"
echo "db-pg17-gate OK"
