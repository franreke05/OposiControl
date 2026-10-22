#!/usr/bin/env bash
# Fails if STAGING could talk to MAIN or reuse a production secret. Never prints a value: only file and variable NAMES.
#
#   bash ops/deploy/staging/staging-secrets-check.sh --repo
#       versioned staging files (compose.staging, ops/deploy/staging, ops/caddy/staging, prometheus.staging.yml, runbook).
#   sudo bash ops/deploy/staging/staging-secrets-check.sh --env-dir /etc/oposibots/staging [--prod-dir /etc/oposibots/production]
#       the REAL staging env files on the host (run after writing them and before `stagingctl.sh up`).
#
# Rules (any hit = exit 1):
#   R1 the MAIN project ref appears anywhere (URL, key, comment).
#   R2 a JWT whose payload names the MAIN ref (legacy anon/service_role keys carry "ref").
#   R3 SUPABASE_URL is not https://<ref>.supabase.co, or its ref is MAIN's.          (--env-dir)
#   R4 PAYMENTS_SANDBOX_SECRET or a development-only variable is defined.               (--env-dir)
#   R5 a staging secret (name with KEY/SECRET/TOKEN/PASSWORD/DSN/HMAC or VALKEY_URL, >= 16 chars) is IDENTICAL to any value
#      in the production env files (compared by sha256 only, in memory).                (--prod-dir)
#   R6 an env file is not mode 0600/0400 or is readable by group/others.                (--env-dir, Linux)
# The forbidden ref is assembled at run time so that this file does not trip its own rule. STAGING_FORBIDDEN_REFS (space
# separated) can add refs.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MAIN_REF="$(printf '%s%s' ndiooyyqta eysnedywer)"
REFS="$MAIN_REF ${STAGING_FORBIDDEN_REFS:-}"
fail=0
bad() { echo "FAIL: $*"; fail=1; }

b64url_decode() { local s; s="$(printf '%s' "$1" | tr '_-' '/+')"; while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done; printf '%s' "$s" | base64 -d 2>/dev/null; }

scan_text() { # <label> <file>: R1 + R2 on any text file
  local label="$1" f="$2" r tok payload
  for r in $REFS; do grep -qi -- "$r" "$f" && bad "R1 $label contains the MAIN project ref"; done
  while IFS= read -r tok; do
    payload="$(b64url_decode "$(echo "$tok" | cut -d. -f2)")"
    for r in $REFS; do echo "$payload" | grep -qi -- "\"ref\"[[:space:]]*:[[:space:]]*\"$r\"" && bad "R2 $label contains a JWT issued for the MAIN project"; done
  done < <(grep -oE 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*' "$f" 2>/dev/null)
}

val() { # <file> <NAME>: last value of NAME in an env file (never printed by callers)
  sed -nE "s/^[[:space:]]*(export[[:space:]]+)?$2=[[:space:]]*//p" "$1" | tail -1 | sed -E 's/^["'"'"'](.*)["'"'"']$/\1/'
}

mode=""; env_dir=""; prod_dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) mode=repo; shift ;;
    --env-dir) mode=env; env_dir="${2:-}"; shift 2 ;;
    --prod-dir) prod_dir="${2:-}"; shift 2 ;;
    *) echo "usage: $0 --repo | --env-dir <dir> [--prod-dir <dir>]"; exit 2 ;;
  esac
done

if [ "$mode" = repo ]; then
  n=0
  while IFS= read -r f; do n=$((n + 1)); scan_text "${f#"$ROOT"/}" "$f"; done < <(
    { echo "$ROOT/ops/deploy/compose.staging.yaml"; echo "$ROOT/ops/monitoring/prometheus/prometheus.staging.yml"
      echo "$ROOT/docs/engineering/STAGING_ON_PROD_HOST_RUNBOOK.md"
      find "$ROOT/ops/deploy/staging" "$ROOT/ops/caddy/staging" -type f ! -name 'staging-secrets-check*.sh'; } | while read -r p; do [ -f "$p" ] && echo "$p"; done)
  [ "$n" -ge 5 ] || bad "only $n staging files found to scan"
  [ "$fail" -eq 0 ] && echo "ok: $n versioned staging files free of MAIN refs and MAIN JWTs"
  exit $fail
fi

[ "$mode" = env ] && [ -d "$env_dir" ] || { echo "usage: $0 --repo | --env-dir <dir> [--prod-dir <dir>]"; exit 2; }
files="$(find "$env_dir" -maxdepth 1 -type f \( -name '*.env' -o -name 'metrics_token' \) | sort)"
[ -n "$files" ] || { bad "no env files in $env_dir"; exit 1; }
for f in $files; do
  scan_text "$(basename "$f")" "$f"
  if [ "$(uname -s)" = Linux ]; then
    m="$(stat -c '%a' "$f")"; case "$m" in 600|400) ;; *) bad "R6 $(basename "$f") has mode $m (want 600 or 400)" ;; esac
  fi
  for v in PAYMENTS_SANDBOX_SECRET OPOSICONTROL_ALLOW_DEV_TOKEN OPOSICONTROL_ALLOW_DEV_USER OPOSICONTROL_ENABLE_BACKEND_RESTART OPOSICONTROL_ADMIN_EMAIL OPOSICONTROL_ADMIN_DISPLAY_NAME; do
    grep -Eq "^[[:space:]]*(export[[:space:]]+)?$v=" "$f" && bad "R4 $(basename "$f") defines $v"
  done
done
common="$env_dir/backend.common.env"
if [ -f "$common" ]; then
  url="$(val "$common" SUPABASE_URL)"
  if ! echo "$url" | grep -Eq '^https://[a-z0-9]{20}\.supabase\.co/?$'; then bad "R3 SUPABASE_URL is not https://<20-char ref>.supabase.co"
  else for r in $REFS; do echo "$url" | grep -qi -- "$r" && bad "R3 SUPABASE_URL is the MAIN project"; done; fi
else
  bad "R3 $common missing"
fi
if [ -n "$prod_dir" ]; then
  [ -d "$prod_dir" ] || { bad "R5 production dir $prod_dir not found"; exit 1; }
  prod_hashes="$(find "$prod_dir" -maxdepth 1 -type f \( -name '*.env' -o -name 'metrics_token' \) -exec cat {} + 2>/dev/null \
    | sed -nE 's/^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*=[[:space:]]*//p; /^[^#=]{16,}$/p' \
    | sed -E 's/^["'"'"'](.*)["'"'"']$/\1/' | awk 'length($0) >= 16' | while IFS= read -r v; do printf '%s' "$v" | sha256sum | cut -c1-64; done | sort -u)"
  for f in $files; do
    while IFS= read -r line; do
      name="${line%%=*}"; v="${line#*=}"; v="$(echo "$v" | sed -E 's/^["'"'"'](.*)["'"'"']$/\1/')"
      [ "${#v}" -ge 16 ] || continue
      # Only secret-bearing names (a shared bucket name or log level is not a reuse).
      echo "$name" | grep -Eqi 'KEY|SECRET|TOKEN|PASSWORD|PASS|DSN|HMAC|VALKEY_URL|metrics_token' || continue
      h="$(printf '%s' "$v" | sha256sum | cut -c1-64)"
      echo "$prod_hashes" | grep -qx "$h" && bad "R5 $(basename "$f"): $name has the SAME value as a production secret"
    done < <(sed -nE 's/^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*=.*)$/\2/p' "$f"; [ "$(basename "$f")" = metrics_token ] && { printf 'metrics_token='; head -1 "$f"; })
  done
fi
[ "$fail" -eq 0 ] && echo "ok: staging env files in $env_dir: no MAIN ref, no MAIN JWT, staging Supabase URL, no sandbox/dev vars${prod_dir:+, no value shared with production}"
exit $fail
