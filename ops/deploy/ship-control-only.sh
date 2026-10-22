#!/usr/bin/env bash
# Cambia SOLO el contenedor Ktor de Control a otra imagen. bot-1 y bot-2 no se tocan.
# Uso (Git Bash, con la llave cargada en el agente de Windows):
#   bash ops/deploy/ship-control-only.sh oposicontrol-backend:<sha12>          # plan, no hace nada
#   bash ops/deploy/ship-control-only.sh oposicontrol-backend:<sha12> --go     # ejecuta
# Rollback: la misma orden con la imagen anterior (oposicontrol-backend:cfa2e14afc8e), que ya esta en el servidor.
# Fija CONTROL_IMAGE (compose nuevo) y BACKEND_IMAGE (compose viejo) SOLO para esta orden. Para que dure, pon tambien
# CONTROL_IMAGE=<imagen> en compose.env del servidor: si no, un `compose up` del stack entero devuelve control a BACKEND_IMAGE.
set -euo pipefail

img="${1:?imagen obligatoria: oposicontrol-backend:<sha12>}"
go="${2:-}"
host="${OPOSI_SSH_HOST:-opsadmin@49.12.232.217}"
ctr="${OPOSI_CONTROL_CONTAINER:-oposibots-ktor-control-1}"
# El ssh de Windows usa el agente donde se cargo la llave; el de Git Bash no.
ssh_bin="${OPOSI_SSH_BIN:-/c/Windows/System32/OpenSSH/ssh.exe}"
[ -x "$ssh_bin" ] || ssh_bin=ssh

echo "Imagen:     $img"
echo "Servidor:   $host"
echo "Contenedor: $ctr (solo este)"
if [ "$go" != "--go" ]; then
  echo "PLAN (no se ha ejecutado nada):"
  echo "  1. comprobar acceso y anotar la imagen actual de los tres Ktor"
  echo "  2. copia de ~/oposibots-stack/env"
  echo "  3. docker save $img | gzip | ssh 'gunzip | docker load' (si el servidor no la tiene ya)"
  echo "  4. docker compose up -d --no-deps <servicio de $ctr> con BACKEND_IMAGE=$img"
  echo "  5. esperar healthy y comprobar /health/ready y la imagen de los tres Ktor"
  echo "Repite con --go para ejecutar."
  exit 0
fi

r() { "$ssh_bin" -o BatchMode=yes "$host" "$@"; }

echo "== 1. acceso y estado antes"
r 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}" | grep ktor' | tee "control-antes-$(date +%Y%m%d%H%M).txt"

echo "== 2. copia del entorno"
r 'cp -a ~/oposibots-stack/env ~/oposibots-stack/env.bak-$(date +%Y%m%d%H%M) 2>/dev/null && echo copia hecha || echo "sin ~/oposibots-stack/env (no se copia)"'

echo "== 3. imagen en el servidor"
if r "docker image inspect $img >/dev/null 2>&1"; then
  echo "ya estaba"
else
  docker save "$img" | gzip | "$ssh_bin" -o BatchMode=yes "$host" 'gunzip | docker load' | tail -1
fi

echo "== 4. solo $ctr"
r "set -e
c=$ctr
dir=\$(docker inspect \$c --format '{{index .Config.Labels \"com.docker.compose.project.working_dir\"}}')
files=\$(docker inspect \$c --format '{{index .Config.Labels \"com.docker.compose.project.config_files\"}}')
proj=\$(docker inspect \$c --format '{{index .Config.Labels \"com.docker.compose.project\"}}')
svc=\$(docker inspect \$c --format '{{index .Config.Labels \"com.docker.compose.service\"}}')
envf=\$(docker inspect \$c --format '{{index .Config.Labels \"com.docker.compose.project.environment_file\"}}')
[ -n \"\$dir\" ] && [ -n \"\$files\" ] && [ -n \"\$svc\" ] || { echo 'ERROR: el contenedor no tiene etiquetas de compose'; exit 1; }
args=\"-p \$proj\"
for f in \$(echo \"\$files\" | tr ',' ' '); do args=\"\$args -f \$f\"; done
for e in \$(echo \"\$envf\" | tr ',' ' '); do [ -f \"\$e\" ] && args=\"\$args --env-file \$e\"; done
cd \"\$dir\"
echo \"compose \$args up -d --no-deps \$svc\"
CONTROL_IMAGE=$img BACKEND_IMAGE=$img docker compose \$args up -d --no-deps \$svc"

echo "== 5. esperar healthy (max 180 s)"
for i in $(seq 1 36); do
  st=$(r "docker inspect $ctr --format '{{.State.Health.Status}}'" 2>/dev/null || echo unknown)
  echo "  $st"; [ "$st" = healthy ] && break; sleep 5
done
r 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}" | grep ktor'
r "docker inspect $ctr --format '{{.Config.Image}} {{index .Config.Labels \"org.opencontainers.image.revision\"}}'"
[ "$st" = healthy ] || { echo "ERROR: $ctr no esta healthy. Rollback: bash ops/deploy/ship-control-only.sh oposicontrol-backend:cfa2e14afc8e --go"; exit 1; }
echo "OK: solo $ctr corre $img"
echo "Recuerda: CONTROL_IMAGE=$img en ~/oposibots-stack/env/compose.env para que un compose up del stack la respete."
