#!/usr/bin/env bash
# MASTER7-CICD-002 / CI-BACKEND-IMAGE: humo de la imagen del backend ya construida (scripts/build-backend-image.sh). Solo
# verificacion: no hace push ni despliega y NO usa secretos reales (variables de relleno que no apuntan a ningun servicio).
# Comprueba que (0) la etiqueta org.opencontainers.image.revision es el commit actual, (1) corre como uid 10001 con /app no
# escribible y /tmp escribible (MASTER5-SEC-003) y (2) la JVM arranca y `/health/live` responde 200 (ruta que no depende de
# la base de datos) en LOS DOS perfiles de despliegue: `combined` (por defecto) y `consumer` (SERVER_PROFILE=consumer, el del
# despliegue partido). Si el contenedor muere durante el arranque falla al momento con sus registros (sin agotar intentos).
# Uso: bash scripts/smoke-backend-image.sh [imagen]
# Variables: SMOKE_IMAGE, SMOKE_PORT (18080), SMOKE_CONSUMER_PORT (18081), SMOKE_TRIES (30), SMOKE_SLEEP (2).
set -uo pipefail
cd "$(dirname "$0")/.."

IMAGE="${1:-${SMOKE_IMAGE:-oposicontrol-backend:$(git rev-parse --short=12 HEAD)}}"
PORT="${SMOKE_PORT:-18080}"
CONSUMER_PORT="${SMOKE_CONSUMER_PORT:-18081}"
TRIES="${SMOKE_TRIES:-30}"
SLEEP="${SMOKE_SLEEP:-2}"
NAME="oposicontrol-smoke-$$"
cleanup() { docker rm -f "$NAME-combined" "$NAME-consumer" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# Relleno no secreto, comun a los dos perfiles.
PLACEHOLDERS=(-e SUPABASE_URL=http://127.0.0.1:9 -e SUPABASE_SECRET_KEY=smoke-placeholder-not-a-secret)

revision="$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$IMAGE" 2>/dev/null)"
[ -n "$revision" ] && [ "$revision" = "$(git rev-parse HEAD)" ] \
  || { echo "SMOKE FAIL: la etiqueta org.opencontainers.image.revision ('$revision') no es el commit actual"; exit 1; }

docker run --rm --entrypoint sh "$IMAGE" -c 'test "$(id -u)" = 10001 && ! touch /app/x 2>/dev/null && touch /tmp/x' \
  || { echo "SMOKE FAIL: la imagen no corre como uid 10001 con /app no escribible y /tmp escribible"; exit 1; }

# smoke_profile <perfil> <puerto> <args de docker run...>: arranca el contenedor y espera 200 en /health/live.
smoke_profile() {
  local profile="$1" port="$2" name="$NAME-$1"
  shift 2
  docker run -d --name "$name" -p "127.0.0.1:${port}:8080" "$@" "$IMAGE" >/dev/null \
    || { echo "SMOKE FAIL ($profile): no se pudo arrancar el contenedor"; return 1; }
  for _ in $(seq 1 "$TRIES"); do
    if curl -fsS -o /dev/null "http://127.0.0.1:${port}/health/live"; then
      echo "SMOKE OK ($profile): ${IMAGE} responde 200 en /health/live"
      docker rm -f "$name" >/dev/null 2>&1 || true
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" != "true" ]; then
      echo "SMOKE FAIL ($profile): el contenedor se detuvo durante el arranque; registros del contenedor:"
      docker logs "$name" 2>&1 | tail -n 80
      return 1
    fi
    sleep "$SLEEP"
  done
  echo "SMOKE FAIL ($profile): /health/live no respondio 200 tras ${TRIES} intentos; registros del contenedor:"
  docker logs "$name" 2>&1 | tail -n 80
  return 1
}

smoke_profile combined "$PORT" -e APP_ENV=staging "${PLACEHOLDERS[@]}" || exit 1
# El perfil consumer exige el runtime de auth. APP_ENV=development SOLO para esta comprobacion de arranque: fuera de
# desarrollo exigiria Valkey, HTTPS y TRUST_PROXY_HEADERS reales, que no existen en CI. El HMAC es relleno publico (>= 32).
smoke_profile consumer "$CONSUMER_PORT" -e SERVER_PROFILE=consumer -e APP_ENV=development -e OPOSIBOTS_AUTH_ENABLED=true \
  -e AUTH_RATE_LIMIT_MODE=memory -e AUTH_RATE_LIMIT_HMAC_SECRET=smoke-placeholder-hmac-filler-not-a-secret \
  "${PLACEHOLDERS[@]}" || exit 1
exit 0
