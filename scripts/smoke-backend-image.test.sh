#!/usr/bin/env bash
# Prueba de smoke-backend-image.sh (MASTER7-CICD-002 / CI-BACKEND-IMAGE) con `docker`/`curl` simulados en el PATH (sin
# Docker ni red): salud 200 en los dos perfiles -> exit 0; salud que nunca es 200 -> exit != 0 con los registros y limpieza;
# uid/permisos mal -> exit != 0; contenedor muerto al arrancar -> falla al primer intento; perfil consumer roto -> exit != 0;
# etiqueta de revision ausente o de otro commit -> exit != 0 sin arrancar nada.
set -uo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat > "$tmp/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
case "$1" in
  run) if [ "${2:-}" = "--rm" ]; then [ "${STUB_ID_OK:-1}" = 1 ]; exit $?; fi; echo container-id ;;
  logs) echo "registro-simulado" ;;
  inspect) echo "${STUB_RUNNING:-true}" ;;
  image) echo "${STUB_LABEL-}" ;;
esac
exit 0
STUB
cat > "$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
echo "curl $*" >> "$STUB_LOG"
case "$*" in *":${STUB_FAIL_PORT:-none}/"*) exit 22 ;; esac
[ "${STUB_HEALTH:-200}" = 200 ]
STUB
chmod +x "$tmp/bin/docker" "$tmp/bin/curl"
HEAD_SHA="$(git rev-parse HEAD)"
run() { STUB_LOG="$tmp/log" STUB_LABEL="${STUB_LABEL-$HEAD_SHA}" SMOKE_IMAGE=img:test SMOKE_TRIES="${TRIES:-2}" SMOKE_SLEEP=0 \
  PATH="$tmp/bin:$PATH" bash scripts/smoke-backend-image.sh "$@"; }

: > "$tmp/log"
out="$(STUB_HEALTH=200 run)" || { echo "TEST FAIL: salud 200 debia pasar: $out"; exit 1; }
grep -q '^docker rm -f' "$tmp/log" || { echo "TEST FAIL: no limpia el contenedor"; exit 1; }
grep 'docker run -d' "$tmp/log" | grep -q 'SERVER_PROFILE=consumer' || { echo "TEST FAIL: no arranca el perfil consumer"; exit 1; }
[ "$(grep 'docker run -d' "$tmp/log" | grep -c 'SERVER_PROFILE=consumer')" = 1 ] \
  && [ "$(grep -c 'docker run -d' "$tmp/log")" = 4 ] && grep 'docker run -d' "$tmp/log" | grep -q 'SERVER_PROFILE=control' && grep 'docker run -d' "$tmp/log" | grep -q 'SERVER_PROFILE=bot' || { echo "TEST FAIL: se esperaban combined + consumer + control + bot"; cat "$tmp/log"; exit 1; }
echo "$out" | grep -q 'SMOKE OK (consumer)' || { echo "TEST FAIL: sin OK del consumer: $out"; exit 1; }

: > "$tmp/log"
out="$(STUB_HEALTH=503 run)" && { echo "TEST FAIL: salud que nunca es 200 paso"; exit 1; }
echo "$out" | grep -q 'registro-simulado' || { echo "TEST FAIL: no imprime los registros al fallar"; exit 1; }
grep -q '^docker rm -f' "$tmp/log" || { echo "TEST FAIL: no limpia el contenedor tras fallar"; exit 1; }

: > "$tmp/log"
STUB_ID_OK=0 STUB_HEALTH=200 run >/dev/null && { echo "TEST FAIL: uid/permisos incorrectos pasaron"; exit 1; }
grep -q 'docker run -d' "$tmp/log" && { echo "TEST FAIL: arranco el contenedor pese a fallar la comprobacion de uid"; exit 1; }

# Salida temprana: el contenedor ya no corre -> falla tras UN intento (no agota los 25) y muestra los registros.
: > "$tmp/log"
out="$(TRIES=25 STUB_RUNNING=false STUB_HEALTH=503 run)" && { echo "TEST FAIL: un contenedor muerto paso"; exit 1; }
[ "$(grep -c '^curl ' "$tmp/log")" = 1 ] || { echo "TEST FAIL: no detecta la salida temprana ($(grep -c '^curl ' "$tmp/log") intentos)"; exit 1; }
echo "$out" | grep -q 'se detuvo durante el arranque' && echo "$out" | grep -q 'registro-simulado' \
  || { echo "TEST FAIL: sin mensaje/registros de salida temprana: $out"; exit 1; }

# El perfil consumer roto hace fallar el humo aunque combined responda.
: > "$tmp/log"
out="$(STUB_FAIL_PORT=18081 run)" && { echo "TEST FAIL: el consumer roto paso"; exit 1; }
echo "$out" | grep -q 'SMOKE OK (combined)' && echo "$out" | grep -q 'SMOKE FAIL (consumer)' \
  || { echo "TEST FAIL: el fallo no es del consumer: $out"; exit 1; }

# Etiqueta de revision ausente o de otro commit: falla antes de arrancar ningun contenedor.
for label in "" "0000000000000000000000000000000000000000"; do
  : > "$tmp/log"
  out="$(STUB_LABEL="$label" run)" && { echo "TEST FAIL: etiqueta '$label' paso"; exit 1; }
  echo "$out" | grep -q 'org.opencontainers.image.revision' || { echo "TEST FAIL: sin mensaje de etiqueta: $out"; exit 1; }
  grep -q 'docker run' "$tmp/log" && { echo "TEST FAIL: arranco contenedores con etiqueta '$label'"; exit 1; }
done
echo "smoke-backend-image.test OK"
