#!/usr/bin/env bash
# Prueba de verify-ops-config.sh: pasa sobre el repo y FALLA si una proteccion solo esta comentada.
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-ops-config.sh >/dev/null || { echo "TEST FAIL: el repo deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cp -r ops scripts .dockerignore .env.example .gitattributes "$tmp"/
sed -i 's/^\([[:space:]]*\)credentials_file:/\1# credentials_file:/' "$tmp/ops/monitoring/prometheus/prometheus.yml"
OPS_ROOT="$tmp" bash "$tmp/scripts/verify-ops-config.sh" >/dev/null && { echo "TEST FAIL: credentials_file comentado paso"; exit 1; }
cp ops/monitoring/prometheus/prometheus.yml "$tmp/ops/monitoring/prometheus/prometheus.yml"
touch "$tmp/ops/deploy/Dockerfile.extra"
OPS_ROOT="$tmp" bash "$tmp/scripts/verify-ops-config.sh" >/dev/null && { echo "TEST FAIL: Dockerfile extra paso"; exit 1; }
echo "verify-ops-config.test OK"
