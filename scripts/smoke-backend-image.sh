#!/usr/bin/env bash
# MASTER7-CICD-002: humo de la imagen del backend ya construida (scripts/build-backend-image.sh). Solo verificacion: no hace
# push ni despliega y NO usa secretos reales (variables de relleno que no apuntan a ningun servicio).
# Comprueba que (1) corre como uid 10001 con /app no escribible y /tmp escribible (MASTER5-SEC-003) y (2) la JVM arranca y
# `/health/live` responde 200 (esa ruta no depende de la base de datos). Uso: bash scripts/smoke-backend-image.sh [imagen]
# Variables: SMOKE_IMAGE, SMOKE_PORT (18080), SMOKE_TRIES (30), SMOKE_SLEEP (2).
set -uo pipefail
cd "$(dirname "$0")/.."

IMAGE="${1:-${SMOKE_IMAGE:-oposicontrol-backend:$(git rev-parse --short=12 HEAD)}}"
PORT="${SMOKE_PORT:-18080}"
TRIES="${SMOKE_TRIES:-30}"
SLEEP="${SMOKE_SLEEP:-2}"
NAME="oposicontrol-smoke-$$"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run --rm --entrypoint sh "$IMAGE" -c 'test "$(id -u)" = 10001 && ! touch /app/x 2>/dev/null && touch /tmp/x' \
  || { echo "SMOKE FAIL: la imagen no corre como uid 10001 con /app no escribible y /tmp escribible"; exit 1; }

docker run -d --name "$NAME" -p "127.0.0.1:${PORT}:8080" \
  -e APP_ENV=staging -e SUPABASE_URL=http://127.0.0.1:9 -e SUPABASE_SECRET_KEY=smoke-placeholder-not-a-secret \
  "$IMAGE" >/dev/null \
  || { echo "SMOKE FAIL: no se pudo arrancar el contenedor"; exit 1; }

for _ in $(seq 1 "$TRIES"); do
  if curl -fsS -o /dev/null "http://127.0.0.1:${PORT}/health/live"; then
    echo "SMOKE OK: ${IMAGE} responde 200 en /health/live"
    exit 0
  fi
  sleep "$SLEEP"
done
echo "SMOKE FAIL: /health/live no respondio 200 tras ${TRIES} intentos; registros del contenedor:"
docker logs "$NAME" 2>&1 | tail -n 80
exit 1
