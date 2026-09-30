#!/usr/bin/env bash
# Comprobaciones estaticas de la configuracion de ops (sin Docker, sin red).
# Falla si se pierde alguna proteccion versionada. Lo ejecuta el CI.
set -u
cd "$(dirname "$0")/.."
fail=0
check() { if ! eval "$2"; then echo "FAIL: $1"; fail=1; else echo "ok:   $1"; fi; }

check "prometheus ktor job autentica con Bearer" \
  "awk '/job_name: ktor/{f=1} f' ops/monitoring/prometheus/prometheus.yml | grep -q 'credentials_file: /etc/prometheus/secrets/metrics_token'"
check "compose monta el token de scrape" \
  "grep -q 'secrets/metrics_token:/etc/prometheus/secrets/metrics_token:ro' ops/monitoring/compose.yaml"
check ".env.example documenta METRICS_SCRAPE_TOKEN" \
  "grep -q '^METRICS_SCRAPE_TOKEN=' .env.example"
check "Caddy bloquea /metrics con 404" \
  "grep -q '^@metrics path /metrics' ops/caddy/metrics-block.caddy && grep -q '^respond @metrics 404' ops/caddy/metrics-block.caddy"
check "secrets/ no se versiona" \
  "grep -q '^\*\$' ops/monitoring/secrets/.gitignore"
check "Dockerfile.backend con base fijada por digest" \
  "grep -Eq '^FROM eclipse-temurin:[^ ]+@sha256:[0-9a-f]{64}\$' ops/deploy/Dockerfile.backend"
check ".dockerignore excluye todo salvo la distribucion" \
  "grep -q '^\*\$' .dockerignore && grep -q '^!backend/ktor/build/install/ktor' .dockerignore"
check ".gitattributes fija LF en migraciones" \
  "grep -q 'supabase/migrations/\*.sql text eol=lf' .gitattributes"
check ".gitattributes fija LF en gradlew" \
  "grep -q '^gradlew text eol=lf' .gitattributes"
check "script de build de imagen existe y es ejecutable" \
  "test -x scripts/build-backend-image.sh || git ls-files -s scripts/build-backend-image.sh | grep -q '^100755'"
exit $fail
