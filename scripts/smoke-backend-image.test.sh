#!/usr/bin/env bash
# Prueba de smoke-backend-image.sh (MASTER7-CICD-002) con `docker`/`curl` simulados en el PATH (sin Docker ni red):
# salud 200 -> exit 0; salud que nunca es 200 -> exit != 0 con los registros y limpieza; uid/permisos mal -> exit != 0.
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
esac
exit 0
STUB
cat > "$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
[ "${STUB_HEALTH:-200}" = 200 ]
STUB
chmod +x "$tmp/bin/docker" "$tmp/bin/curl"
run() { STUB_LOG="$tmp/log" SMOKE_IMAGE=img:test SMOKE_TRIES=2 SMOKE_SLEEP=0 PATH="$tmp/bin:$PATH" bash scripts/smoke-backend-image.sh "$@"; }

: > "$tmp/log"
out="$(STUB_HEALTH=200 run)" || { echo "TEST FAIL: salud 200 debia pasar: $out"; exit 1; }
grep -q '^docker rm -f' "$tmp/log" || { echo "TEST FAIL: no limpia el contenedor"; exit 1; }

: > "$tmp/log"
out="$(STUB_HEALTH=503 run)" && { echo "TEST FAIL: salud que nunca es 200 paso"; exit 1; }
echo "$out" | grep -q 'registro-simulado' || { echo "TEST FAIL: no imprime los registros al fallar"; exit 1; }
grep -q '^docker rm -f' "$tmp/log" || { echo "TEST FAIL: no limpia el contenedor tras fallar"; exit 1; }

: > "$tmp/log"
STUB_ID_OK=0 STUB_HEALTH=200 run >/dev/null && { echo "TEST FAIL: uid/permisos incorrectos pasaron"; exit 1; }
grep -q 'docker run -d' "$tmp/log" && { echo "TEST FAIL: arranco el contenedor pese a fallar la comprobacion de uid"; exit 1; }
echo "smoke-backend-image.test OK"
