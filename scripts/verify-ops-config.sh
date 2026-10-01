#!/usr/bin/env bash
# Comprobaciones estaticas de la configuracion de ops (sin Docker, sin red).
# Falla si se pierde alguna proteccion versionada. Lo ejecuta el CI: `bash scripts/verify-ops-config.sh`
# (no depende del bit ejecutable). OPS_ROOT permite apuntarlo a otro arbol (lo usa verify-ops-config.test.sh).
set -uo pipefail
cd "${OPS_ROOT:-$(dirname "$0")/..}" || exit 2
fail=0
check() { if ( eval "$2" ) >/dev/null 2>&1; then echo "ok:   $1"; else echo "FAIL: $1"; fail=1; fi; }

# Bloque del job `ktor` de prometheus.yml: desde su `- job_name: ktor` hasta el siguiente `- job_name:`.
ktor_job() { awk '/^[[:space:]]*-[[:space:]]+job_name:[[:space:]]*ktor[[:space:]]*$/{f=1;next} f&&/^[[:space:]]*-[[:space:]]+job_name:/{f=0} f' ops/monitoring/prometheus/prometheus.yml; }

check "prometheus ktor job autentica con Bearer (lineas activas, no comentadas)" \
  "ktor_job | grep -Eq '^[[:space:]]+credentials_file:[[:space:]]*/etc/prometheus/secrets/metrics_token[[:space:]]*\$' && ktor_job | grep -Eq '^[[:space:]]+type:[[:space:]]*Bearer[[:space:]]*\$'"
check "compose monta el token de scrape" \
  "grep -Eq '^[[:space:]]*-[[:space:]]*.*secrets/metrics_token:/etc/prometheus/secrets/metrics_token:ro' ops/monitoring/compose.yaml"
check ".env.example documenta METRICS_SCRAPE_TOKEN" \
  "grep -q '^METRICS_SCRAPE_TOKEN=' .env.example"
check "Caddy bloquea /metrics con 404" \
  "grep -q '^@metrics path /metrics' ops/caddy/metrics-block.caddy && grep -q '^respond @metrics 404' ops/caddy/metrics-block.caddy"
check "Caddy limita el cuerpo no multipart (body-limit.caddy)"   "grep -Eq '^[[:space:]]*max_size[[:space:]]+[0-9]+MB' ops/caddy/body-limit.caddy && grep -q '^request_body @bounded' ops/caddy/body-limit.caddy"
check "secrets/ no se versiona" \
  "grep -q '^\*\$' ops/monitoring/secrets/.gitignore"
check "Dockerfile.backend con base fijada por digest" \
  "grep -Eq '^FROM eclipse-temurin:[^ ]+@sha256:[0-9a-f]{64}\$' ops/deploy/Dockerfile.backend"
check "ops/deploy solo contiene el Dockerfile construible con el .dockerignore raiz" \
  "test \"\$(ls ops/deploy | grep -c '^Dockerfile')\" = 1"
check ".dockerignore excluye todo salvo la distribucion" \
  "grep -q '^\*\$' .dockerignore && grep -q '^!backend/ktor/build/install/ktor' .dockerignore"
check ".gitattributes fija LF en migraciones" \
  "grep -q 'supabase/migrations/\*.sql text eol=lf' .gitattributes"
check ".gitattributes fija LF en gradlew" \
  "grep -q '^gradlew text eol=lf' .gitattributes"
check "script de build de imagen existe, con set -euo pipefail y llama a la guarda de arbol limpio" \
  "test -f scripts/build-backend-image.sh && grep -q '^set -euo pipefail' scripts/build-backend-image.sh && grep -q '^bash scripts/assert-clean-tree.sh' scripts/build-backend-image.sh"
check "guarda de arbol limpio: cambios, no rastreados y ignorados en arboles de codigo (MASTER3-SEC-002)" \
  "grep -q '^set -euo pipefail' scripts/assert-clean-tree.sh && grep -q 'git status --porcelain)' scripts/assert-clean-tree.sh && grep -q 'git status --porcelain --ignored' scripts/assert-clean-tree.sh && ! grep -q 'untracked-files=no' scripts/assert-clean-tree.sh"
check "ci.yml: cancel-in-progress solo en pull_request (MASTER4-CICD-003)" \
  "grep -Fq \"cancel-in-progress: \\\${{ github.event_name == 'pull_request' }}\" .github/workflows/ci.yml"
check "guarda de arbol limpio: rechaza ignorados bajo src/ y la raiz solo por argumento (MASTER4-SEC-002)" \
  "grep -q \"grep -E '^!! (.\\*/)?src/'\" scripts/assert-clean-tree.sh && ! grep -q 'CLEAN_TREE_ROOT' scripts/assert-clean-tree.sh"
check "README de monitoring: el curl interno de /metrics envia el Bearer (MASTER4-CICD-004)" \
  "grep -q 'Authorization: Bearer .(cat /run/secrets/metrics_token)' ops/monitoring/README.md && grep -q 'metrics_token:/run/secrets/metrics_token:ro' ops/monitoring/README.md"
check "README de monitoring: el token nunca se lee en el host para la linea de comandos y un unico directorio de trabajo (MASTER5-SEC-002)" \
  "! grep -q '(cat ops/monitoring/secrets/metrics_token)' ops/monitoring/README.md && grep -q 'RAIZ del repositorio' ops/monitoring/README.md && ! grep -q 'desde este directorio' ops/monitoring/README.md && ! grep -Eq '^docker compose (config|up|pull|ps)' ops/monitoring/README.md"
check "ci.yml: secret-scan con gitleaks-action fijada por SHA (MASTER7-CICD-001)"   "grep -Eq 'uses: gitleaks/gitleaks-action@[0-9a-f]{40}' .github/workflows/ci.yml && grep -q '^  secret-scan:' .github/workflows/ci.yml"
check "dependabot.yml: ecosistemas gradle y github-actions (MASTER7-CICD-001)"   "grep -Eq 'package-ecosystem: gradle' .github/dependabot.yml && grep -Eq 'package-ecosystem: github-actions' .github/dependabot.yml"
check "ci.yml construye y arranca la imagen del backend (MASTER7-CICD-002)"   "grep -q 'bash scripts/build-backend-image.sh' .github/workflows/ci.yml && grep -q 'bash scripts/smoke-backend-image.sh' .github/workflows/ci.yml && grep -q 'bash scripts/smoke-backend-image.test.sh' .github/workflows/ci.yml"
check "smoke-backend-image.sh: pipefail, uid 10001, /health/live y limpieza (MASTER7-CICD-002)"   "grep -q '^set -uo pipefail' scripts/smoke-backend-image.sh && grep -q 'id -u)\" = 10001' scripts/smoke-backend-image.sh && grep -q '/health/live' scripts/smoke-backend-image.sh && grep -q '^trap cleanup EXIT' scripts/smoke-backend-image.sh"
check "gradle/verification-metadata.xml existe y ci.yml no desactiva la verificacion (MASTER7-CICD-005)"   "grep -q '<verify-metadata>true</verify-metadata>' gradle/verification-metadata.xml && ! grep -Eq 'dependency-verification[= ]+(off|lenient)|--dependency-verification' .github/workflows/ci.yml scripts/build-backend-image.sh scripts/smoke-backend-image.sh"
check "ci.yml: redocly desde tools/redocly con package-lock (npm ci, sin npx --yes) (MASTER7-CICD-005)"   "test -s tools/redocly/package-lock.json && grep -q 'npm ci --prefix tools/redocly' .github/workflows/ci.yml && ! grep -q 'npx --yes' .github/workflows/ci.yml"
# MASTER7-QUALITY-001: un CR suelto (no seguido de LF) vuelve el blob no-texto: git no lo normaliza ni lo diffea. Un CRLF puro de un
# checkout de Windows es inocuo y no cuenta.
has_lone_cr() { local cr; cr="$(printf '\r')"; sed "s/${cr}\$//" "$1" | grep -qU "$cr"; }
no_lone_cr_md() { local f; while IFS= read -r f; do has_lone_cr "$f" && return 1; done < <(find docs README.md -name '*.md'); return 0; }
check ".gitattributes fija LF en *.md y ningun .md de docs/README tiene CR suelto (MASTER7-QUALITY-001)" \
  "grep -Fxq '*.md text eol=lf' .gitattributes && no_lone_cr_md"
check "SECURITY.md no niega las metricas Prometheus que existen (MASTER7-QUALITY-001)"   "grep -q 'Prometheus .*/metrics.* does exist' docs/engineering/SECURITY.md && ! grep -Eq 'CallLogging., metrics/tracing' docs/engineering/SECURITY.md"
exit $fail
