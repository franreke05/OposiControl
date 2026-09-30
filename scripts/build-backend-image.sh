#!/usr/bin/env bash
# Construye la imagen del backend Ktor desde el arbol limpio.
# Uso: scripts/build-backend-image.sh            (etiqueta = git SHA corto)
# No despliega ni hace push. Ver docs/engineering/DEPLOYMENT.md.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "ERROR: arbol de trabajo con cambios; la etiqueta SHA no identificaria la imagen." >&2
  exit 1
fi
SHA="$(git rev-parse --short=12 HEAD)"
IMAGE="oposicontrol-backend"

./gradlew :backend:ktor:installDist --rerun-tasks --no-build-cache
docker build -f ops/deploy/Dockerfile.backend \
  --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" \
  -t "${IMAGE}:${SHA}" .
echo "Imagen: ${IMAGE}:${SHA}"
echo "BUILD_SHA para el contenedor: ${SHA} (variable SENTRY_RELEASE / BUILD_SHA)"
