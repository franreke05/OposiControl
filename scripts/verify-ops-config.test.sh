#!/usr/bin/env bash
# Prueba de verify-ops-config.sh (MASTER3-CICD-006): pasa sobre el repo y CADA comprobacion falla al mutar
# justo lo que protege. Cada mutacion se aplica sobre una copia fresca, debe cambiar el archivo (no vacua) y
# el FAIL debe ser el de esa comprobacion (no otro).
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-ops-config.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
n=0
# mutate <descripcion> <archivo relativo> <sed -i expr | @touch | @rm> <subcadena del FAIL esperado>
mutate() {
  local desc="$1" file="$2" expr="$3" expect="$4" w="$tmp/w$n" out
  n=$((n + 1))
  mkdir "$w"
  cp -r ops scripts .github docs .dockerignore .env.example .gitattributes README.md "$w"/
  mkdir -p "$w/gradle" "$w/tools/redocly"
  cp gradle/verification-metadata.xml "$w/gradle/"
  cp tools/redocly/package.json tools/redocly/package-lock.json "$w/tools/redocly/"
  if [ "$expr" = "@touch" ]; then
    touch "$w/$file"
  elif [ "$expr" = "@rm" ]; then
    rm "$w/$file"
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
mutate "ci.yml cancel-in-progress incondicional (MASTER4-CICD-003)" .github/workflows/ci.yml 's/^  cancel-in-progress:.*/  cancel-in-progress: true/' "cancel-in-progress solo en pull_request"
mutate "guarda acepta ignorados bajo src/ (MASTER4-SEC-002)" scripts/assert-clean-tree.sh "s/grep -E '\^!! (\.\*\/)?src\//grep -E '^!! (.*\/)?zzz\//" "rechaza ignorados bajo src"
mutate "guarda vuelve a leer CLEAN_TREE_ROOT (MASTER4-SEC-002)" scripts/assert-clean-tree.sh 's/^cd "\${1:-/cd "${CLEAN_TREE_ROOT:-/' "rechaza ignorados bajo src"
mutate "README sin Bearer en el curl interno (MASTER4-CICD-004)" ops/monitoring/README.md '/Authorization: Bearer/d' "curl interno de /metrics envia el Bearer"
mutate "README vuelve a pasar el token en la linea de comandos (MASTER5-SEC-002)" ops/monitoring/README.md '$a docker run curlimages/curl -H "Authorization: Bearer $(cat ops/monitoring/secrets/metrics_token)" x' "el token nunca se lee en el host"
mutate "README vuelve a pedir el directorio del stack (MASTER5-SEC-002)" ops/monitoring/README.md 's#RAIZ del repositorio#directorio del stack#' "unico directorio de trabajo"
mutate "README con docker compose sin -f (MASTER5-SEC-002)" ops/monitoring/README.md '0,/^docker compose -f ops\/monitoring\/compose.yaml up -d/s##docker compose up -d#' "unico directorio de trabajo"
C=.github/workflows/ci.yml
mutate "ci.yml sin gitleaks (MASTER7-CICD-001)" $C '/gitleaks\/gitleaks-action@/d' "secret-scan con gitleaks"
mutate "ci.yml con gitleaks sin fijar por SHA (MASTER7-CICD-001)" $C 's#gitleaks-action@[0-9a-f]\{40\}#gitleaks-action@v2#' "secret-scan con gitleaks"
mutate "sin dependabot.yml (MASTER7-CICD-001)" .github/dependabot.yml @rm "dependabot.yml: ecosistemas"
mutate "dependabot sin github-actions (MASTER7-CICD-001)" .github/dependabot.yml 's/package-ecosystem: github-actions/package-ecosystem: pip/' "dependabot.yml: ecosistemas"
mutate "ci.yml no construye la imagen (MASTER7-CICD-002)" $C '/bash scripts\/build-backend-image.sh/d' "construye y arranca la imagen"
mutate "ci.yml no ejecuta el humo (MASTER7-CICD-002)" $C '/bash scripts\/smoke-backend-image.sh$/d' "construye y arranca la imagen"
mutate "humo sin /health/live (MASTER7-CICD-002)" scripts/smoke-backend-image.sh 's#/health/live#/health#g' "smoke-backend-image.sh:"
mutate "humo sin limpieza (MASTER7-CICD-002)" scripts/smoke-backend-image.sh '/^trap cleanup EXIT/d' "smoke-backend-image.sh:"
mutate "sin verification-metadata.xml (MASTER7-CICD-005)" gradle/verification-metadata.xml @rm "verification-metadata.xml existe"
mutate "ci.yml desactiva la verificacion de dependencias (MASTER7-CICD-005)" $C '$a # ./gradlew build --dependency-verification=off' "verification-metadata.xml existe"
mutate "ci.yml vuelve a npx --yes (MASTER7-CICD-005)" $C '$a # npx --yes @redocly/cli' "redocly desde tools/redocly"
mutate "sin package-lock de redocly (MASTER7-CICD-005)" tools/redocly/package-lock.json @rm "redocly desde tools/redocly"
mutate ".gitattributes sin *.md eol=lf (MASTER7-QUALITY-001)" .gitattributes '/^\*\.md text eol=lf/d' "fija LF en"
mutate "SECURITY.md con CR suelto (MASTER7-QUALITY-001)" docs/engineering/SECURITY.md '1s/$/\rx/' "CR suelto"
mutate "SECURITY.md pierde la mencion de /metrics (MASTER7-QUALITY-001)" docs/engineering/SECURITY.md 's/does exist/zzz/' "SECURITY.md no niega"
mutate "SECURITY.md vuelve a negar las metricas (MASTER7-QUALITY-001)" docs/engineering/SECURITY.md '$a no global Ktor `CallLogging`, metrics/tracing' "SECURITY.md no niega"
echo "verify-ops-config.test OK ($n mutaciones)"
