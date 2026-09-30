#!/usr/bin/env bash
# Prueba de verify-ops-config.sh (MASTER3-CICD-006): pasa sobre el repo y CADA comprobacion falla al mutar
# justo lo que protege. Cada mutacion se aplica sobre una copia fresca, debe cambiar el archivo (no vacua) y
# el FAIL debe ser el de esa comprobacion (no otro).
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-ops-config.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
# mutate <descripcion> <archivo relativo> <sed -i expr | @touch> <subcadena del FAIL esperado>
mutate() {
  local desc="$1" file="$2" expr="$3" expect="$4" w="$tmp/w$n" out
  n=$((n + 1))
  mkdir "$w"
  cp -r ops scripts .dockerignore .env.example .gitattributes "$w"/
  if [ "$expr" = "@touch" ]; then
    touch "$w/$file"
  else
    cp "$w/$file" "$w/$file.orig"
    sed -i "$expr" "$w/$file"
    if cmp -s "$w/$file" "$w/$file.orig"; then echo "TEST FAIL: la mutacion '$desc' no cambio $file (prueba vacua)"; exit 1; fi
    rm "$w/$file.orig"
  fi
  out="$(OPS_ROOT="$w" bash "$w/scripts/verify-ops-config.sh")" && { echo "TEST FAIL: '$desc' paso"; exit 1; }
  echo "$out" | grep -q "^FAIL: .*$expect" || { echo "TEST FAIL: '$desc' no fallo en '$expect':"; echo "$out"; exit 1; }
  [ "$(echo "$out" | grep -c '^FAIL:')" = 1 ] || { echo "TEST FAIL: '$desc' rompio mas de una comprobacion:"; echo "$out"; exit 1; }
}
P=ops/monitoring/prometheus/prometheus.yml
mutate "credentials_file comentado" $P 's/^\([[:space:]]*\)credentials_file:/\1# credentials_file:/' "prometheus ktor job"
mutate "type Bearer cambiado" $P 's/^\([[:space:]]*\)type:[[:space:]]*Bearer/\1type: Basic/' "prometheus ktor job"
mutate "sin montaje del token" ops/monitoring/compose.yaml '/secrets\/metrics_token:\/etc\/prometheus\/secrets\/metrics_token/d' "compose monta"
mutate "sin METRICS_SCRAPE_TOKEN" .env.example '/^METRICS_SCRAPE_TOKEN=/d' "documenta METRICS_SCRAPE_TOKEN"
mutate "Caddy /metrics ya no 404" ops/caddy/metrics-block.caddy 's/^respond @metrics 404/respond @metrics 200/' "Caddy bloquea"
mutate "Caddy sin max_size" ops/caddy/body-limit.caddy '/max_size/d' "Caddy limita el cuerpo"
mutate "secrets/ versionable" ops/monitoring/secrets/.gitignore '/^\*$/d' "secrets/ no se versiona"
mutate "base sin digest" ops/deploy/Dockerfile.backend 's/@sha256:[0-9a-f]\{64\}//' "base fijada por digest"
mutate "Dockerfile extra en ops/deploy" ops/deploy/Dockerfile.extra @touch "ops/deploy solo contiene"
mutate ".dockerignore sin la excepcion" .dockerignore '/^!backend\/ktor\/build\/install\/ktor/d' ".dockerignore excluye"
mutate ".gitattributes sin LF de migraciones" .gitattributes '/supabase\/migrations\/\*\.sql/d' "LF en migraciones"
mutate ".gitattributes sin LF de gradlew" .gitattributes '/^gradlew text eol=lf/d' "LF en gradlew"
mutate "build sin pipefail" scripts/build-backend-image.sh '/^set -euo pipefail/d' "script de build de imagen"
mutate "build sin guarda" scripts/build-backend-image.sh '/^bash scripts\/assert-clean-tree.sh/d' "script de build de imagen"
mutate "guarda sin --ignored" scripts/assert-clean-tree.sh 's/git status --porcelain --ignored/git status --porcelain/' "guarda de arbol limpio"
mutate "guarda ignora no rastreados" scripts/assert-clean-tree.sh 's/git status --porcelain)/git status --porcelain --untracked-files=no)/' "guarda de arbol limpio"
echo "verify-ops-config.test OK ($n mutaciones)"
