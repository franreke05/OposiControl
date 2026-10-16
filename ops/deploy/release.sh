#!/usr/bin/env bash
# Punto de entrada unico del despliegue a produccion (runbook: docs/engineering/RELEASE_RUNBOOK.md).
# Subcomandos:
#   preflight [--applied-list <fichero>]   comprobaciones locales de solo lectura + migraciones pendientes
#   build                                  construye la imagen con scripts/build-backend-image.sh y verifica el jar
#   ship [--i-have-the-go]                 pasa la imagen al servidor y lanza redeploy-rolling.sh (por defecto: DRY-RUN)
#   verify                                 comprobaciones HTTP publicas (OPOSI_API_URL)
# El acceso al servidor (llave SSH en el agente, host en OPOSI_SSH_HOST) lo pone el propietario; nada de eso vive aqui.
# Variables: RELEASE_ROOT (raiz del repo, por defecto la de este script), RELEASE_BRANCH (develop), RELEASE_REMOTE (origin),
#            OPOSI_SSH_HOST (usuario@host), OPOSI_API_URL (https://...), OPOSI_PROTECTED_PATH (ruta que debe dar 401, opcional).
set -euo pipefail

ROOT="${RELEASE_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
BRANCH="${RELEASE_BRANCH:-develop}"
REMOTE="${RELEASE_REMOTE:-origin}"
IMAGE_NAME="oposicontrol-backend"

die() { echo "ERROR: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }
g() { git -C "$ROOT" "$@"; }

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

cmd_preflight() {
  local applied=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --applied-list) [ $# -ge 2 ] || die "--applied-list necesita un fichero"; applied="$2"; shift 2 ;;
      *) die "argumento desconocido en preflight: $1" ;;
    esac
  done
  local cur sha
  cur="$(g rev-parse --abbrev-ref HEAD)"
  [ "$cur" = "$BRANCH" ] || die "la rama actual es '$cur'; se publica desde '$BRANCH'."
  ok "rama $BRANCH"
  [ -z "$(g status --porcelain)" ] || die "arbol de trabajo sucio (cambios o archivos sin rastrear); no se publica."
  ok "arbol limpio"
  g fetch -q "$REMOTE" || die "no se pudo hacer fetch de $REMOTE."
  [ "$(g rev-parse HEAD)" = "$(g rev-parse "$REMOTE/$BRANCH")" ] \
    || die "HEAD no coincide con $REMOTE/$BRANCH (haz 'git merge --ff-only $REMOTE/$BRANCH' o sube tus commits)."
  sha="$(g rev-parse --short=12 HEAD)"
  ok "$BRANCH == $REMOTE/$BRANCH ($sha)"
  if g ls-files | grep -Eq '(^|/)\.env$'; then die "hay un .env rastreado por git."; fi
  ok "ningun .env rastreado"
  [ -f "$ROOT/scripts/verify-migrations-manifest.sh" ] || die "falta scripts/verify-migrations-manifest.sh"
  bash "$ROOT/scripts/verify-migrations-manifest.sh" >/dev/null || die "verify-migrations-manifest.sh fallo (ejecutalo para ver el detalle)."
  ok "manifiesto de migraciones"
  [ -f "$ROOT/scripts/verify-ops-config.sh" ] || die "falta scripts/verify-ops-config.sh"
  bash "$ROOT/scripts/verify-ops-config.sh" >/dev/null || die "verify-ops-config.sh fallo (ejecutalo para ver el detalle)."
  ok "configuracion de ops/rutas"
  pending_migrations "$applied"
  echo "PREFLIGHT OK: $IMAGE_NAME:$sha"
}

# Migraciones de supabase/migrations que NO figuran en el historial exportado de MAIN. Solo lectura.
pending_migrations() {
  local applied="$1" dir="$ROOT/supabase/migrations" have last v f n=0
  if [ -z "$applied" ]; then
    echo "AVISO: sin --applied-list no se pueden calcular las migraciones pendientes (ver RELEASE_RUNBOOK.md, paso 3)."
    return 0
  fi
  [ -s "$applied" ] || die "la lista de aplicadas no existe o esta vacia: $applied"
  have="$(tr -d '\r' < "$applied" | grep -oE '[0-9]{14}' | sort -u || true)"
  [ -n "$have" ] || die "la lista de aplicadas no contiene versiones de 14 digitos: $applied"
  last="$(echo "$have" | tail -1)"
  echo "Ultima migracion aplicada en MAIN segun la lista: $last"
  echo "Migraciones PENDIENTES (en orden de aplicacion; las ya aplicadas NUNCA se editan):"
  while IFS= read -r f; do
    v="${f%%_*}"
    if ! echo "$have" | grep -qx "$v"; then
      n=$((n + 1))
      if [[ "$v" < "$last" ]]; then echo "  $f   <-- ANTERIOR a la ultima aplicada: revisar antes de aplicar"
      else echo "  $f"; fi
    fi
  done < <(ls "$dir" | grep -E '^[0-9]{14}_.*\.sql$' | sort)
  [ "$n" -gt 0 ] || echo "  (ninguna)"
  echo "Total pendientes: $n"
}

cmd_build() {
  [ $# -eq 0 ] || die "build no admite argumentos."
  command -v docker >/dev/null 2>&1 || die "docker no esta disponible."
  docker info >/dev/null 2>&1 || die "Docker no responde (esta en pausa?)."
  local sha img jar_local jar_image
  sha="$(g rev-parse --short=12 HEAD)"; img="$IMAGE_NAME:$sha"
  bash "$ROOT/scripts/build-backend-image.sh" | tail -3
  jar_local="$(sha256sum "$ROOT/backend/ktor/build/install/ktor/lib/ktor.jar" | cut -c1-64)"
  jar_image="$(docker run --rm --entrypoint sh "$img" -c 'sha256sum /app/lib/ktor.jar' | cut -c1-64)"
  [ -n "$jar_local" ] && [ "$jar_local" = "$jar_image" ] || die "el jar de la imagen no es el recien construido."
  ok "imagen $img con jar $jar_image"
}

cmd_ship() {
  local go=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --i-have-the-go) go=1; shift ;;
      *) die "argumento desconocido en ship: $1" ;;
    esac
  done
  local sha img host
  sha="$(g rev-parse --short=12 HEAD)"; img="$IMAGE_NAME:$sha"
  host="${OPOSI_SSH_HOST:-}"
  [ -n "$host" ] || die "define OPOSI_SSH_HOST (usuario@host); el acceso al servidor es del propietario."
  echo "Plan de ship para $img hacia $host:"
  echo "  1. ssh -o BatchMode=yes $host true            (comprobar llave en el agente)"
  echo "  2. docker image inspect $img                  (la imagen debe existir en local)"
  echo "  3. docker save $img | gzip | ssh $host 'gunzip | docker load'"
  echo "  4. ssh $host 'bash ~/redeploy-rolling.sh $img'"
  if [ "$go" -ne 1 ]; then
    echo "DRY-RUN: no se ha ejecutado nada. Para ejecutar de verdad: ship --i-have-the-go (y teclear el SHA corto)."
    return 0
  fi
  local typed
  printf 'Escribe el SHA corto (%s) para confirmar el despliegue a PRODUCCION: ' "$sha" >&2
  IFS= read -r typed || die "sin confirmacion interactiva; abortado."
  [ "$typed" = "$sha" ] || die "el SHA tecleado no coincide; abortado, no se ha tocado el servidor."
  local ssh_bin="${SSH_BIN:-ssh}"
  "$ssh_bin" -o BatchMode=yes -o ConnectTimeout=10 "$host" true || die "no hay acceso SSH. Carga la llave en el agente (ssh-add) y repite."
  docker image inspect "$img" >/dev/null 2>&1 || die "la imagen $img no existe en local; ejecuta 'release.sh build'."
  docker save "$img" | gzip | "$ssh_bin" -o BatchMode=yes "$host" 'gunzip | docker load' | tail -1
  "$ssh_bin" -o BatchMode=yes "$host" "bash ~/redeploy-rolling.sh $img"
  ok "redespliegue lanzado: $img"
  echo "Siguiente: release.sh verify, y el paso 8b del runbook solo con GO expreso."
}

cmd_verify() {
  [ $# -eq 0 ] || die "verify no admite argumentos."
  local base="${OPOSI_API_URL:-}" code bad=0
  [ -n "$base" ] || die "define OPOSI_API_URL (https://...)."
  base="${base%/}"
  chk() { # <ruta> <codigo esperado>
    code="$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$base$1" || true)"
    if [ "$code" = "$2" ]; then ok "$1 -> $code"; else echo "FALLO: $1 -> $code (esperado $2)" >&2; bad=1; fi
  }
  chk /health/live 200
  chk /health/ready 200
  chk /metrics 404
  if [ -n "${OPOSI_PROTECTED_PATH:-}" ]; then chk "$OPOSI_PROTECTED_PATH" 401; fi
  [ "$bad" -eq 0 ] || die "verify fallo; ver RELEASE_RUNBOOK.md (rollback)."
  echo "VERIFY OK (falta la llamada de administrador autenticada y la de /v1/auth: manuales, ver runbook)."
}

[ $# -ge 1 ] || usage
sub="$1"; shift
case "$sub" in
  preflight) cmd_preflight "$@" ;;
  build) cmd_build "$@" ;;
  ship) cmd_ship "$@" ;;
  verify) cmd_verify "$@" ;;
  *) usage ;;
esac
