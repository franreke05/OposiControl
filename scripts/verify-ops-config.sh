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
check "script de build de imagen existe, con set -euo pipefail y guarda de arbol limpio incl. no rastreados" \
  "test -f scripts/build-backend-image.sh && grep -q '^set -euo pipefail' scripts/build-backend-image.sh && grep -q 'git status --porcelain)' scripts/build-backend-image.sh && ! grep -q 'untracked-files=no' scripts/build-backend-image.sh"
exit $fail
