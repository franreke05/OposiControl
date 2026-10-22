#!/usr/bin/env bash
# LOCAL validation of the staging Caddy site file together with the production host Caddyfile, exactly as it will be on the
# host: production Caddyfile + one `import /etc/caddy/staging/staging.caddy` line. Runs `caddy validate` in throwaway
# containers for each version in CADDY_VERSIONS (default: 2.11.7 and 2.6.4, the host runs 2.6.x). No network to the host.
# The TLS files are a throwaway self-signed pair generated here (stand-ins for the Cloudflare Origin CA pair).
# Also checks the negative control: the staging file ALONE must not contain the production site, and an import of a
# missing staging file must FAIL validation (so a broken import is caught before any reload).
# Usage (repo root): bash ops/caddy/staging/validate-local.sh
set -euo pipefail
cd "$(dirname "$0")/../../.."
VERSIONS="${CADDY_VERSIONS:-2.11.7 2.6.4}"
# Git Bash (MSYS): do not rewrite "/CN=..." nor the container paths (no effect on Linux).
export MSYS2_ARG_CONV_EXCL="/CN;/etc/caddy"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/etc/staging"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=staging-api.oposibots.es" \
  -keyout "$tmp/etc/staging/origin.key" -out "$tmp/etc/staging/origin.pem" >/dev/null 2>&1
cp ops/caddy/staging/Caddyfile.staging-hostcaddy "$tmp/etc/staging/staging.caddy"
{ cat ops/caddy/Caddyfile.hostcaddy; echo; echo 'import /etc/caddy/staging/staging.caddy'; } > "$tmp/etc/Caddyfile"
{ cat ops/caddy/Caddyfile.hostcaddy; echo; echo 'import /etc/caddy/staging/missing.caddy'; } > "$tmp/etc/Caddyfile.broken"
src="$tmp/etc"; command -v cygpath >/dev/null 2>&1 && src="$(cygpath -w "$tmp/etc")"
grep -q '^api\.oposibots\.es {' ops/caddy/staging/Caddyfile.staging-hostcaddy && { echo "FAIL: staging file contains the production site"; exit 1; }
for v in $VERSIONS; do
  docker run --rm -v "$src:/etc/caddy:ro" "caddy:$v" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >"$tmp/out" 2>&1 \
    || { echo "FAIL: caddy $v validate (prod + staging import)"; tail -20 "$tmp/out"; exit 1; }
  echo "ok:   caddy $v validate: production Caddyfile + import of the staging site file"
  docker run --rm -v "$src:/etc/caddy:ro" "caddy:$v" caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile >"$tmp/adapt.json" 2>/dev/null
  for h in api.oposibots.es staging-api.oposibots.es staging-control.oposibots.es; do
    grep -q "\"$h\"" "$tmp/adapt.json" || { echo "FAIL: caddy $v: host $h missing from the adapted config"; exit 1; }
  done
  echo "ok:   caddy $v adapt: the three hosts are present (production untouched, two staging sites)"
  if docker run --rm -v "$src:/etc/caddy:ro" "caddy:$v" caddy validate --config /etc/caddy/Caddyfile.broken --adapter caddyfile >/dev/null 2>&1; then
    echo "FAIL: caddy $v accepted an import of a missing file (negative control)"; exit 1
  fi
  echo "ok:   caddy $v rejects an import of a missing file (negative control)"
done
echo "CADDY STAGING VALIDATION OK"
