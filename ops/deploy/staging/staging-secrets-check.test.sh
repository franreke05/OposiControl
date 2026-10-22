#!/usr/bin/env bash
# Negative control of staging-secrets-check.sh: a clean synthetic staging dir passes, and EACH seeded violation fails with
# its own rule. Synthetic values only (built at run time; nothing here is a real key).
set -uo pipefail
cd "$(dirname "$0")"
CHK=./staging-secrets-check.sh
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
MAIN_REF="$(printf '%s%s' ndiooyyqta eysnedywer)"
STG_REF="abcdefghijklmnopqrst"
b64url() { printf '%s' "$1" | base64 | tr -d '=\n' | tr '/+' '_-'; }
jwt() { echo "$(b64url '{"alg":"HS256","typ":"JWT"}').$(b64url "{\"iss\":\"supabase\",\"ref\":\"$1\",\"role\":\"service_role\"}").c2lnbmF0dXJl"; }
filler() { printf 'synthetic-%s-%s' "$1" 0123456789abcdef; }

clean() { # <dir>
  rm -rf "$1"; mkdir -p "$1"
  printf 'SUPABASE_URL=https://%s.supabase.co\nSUPABASE_SERVICE_ROLE_KEY=%s\nMETRICS_SCRAPE_TOKEN=%s\n' "$STG_REF" "$(jwt "$STG_REF")" "$(filler metrics)" > "$1/backend.common.env"
  printf 'AUTH_RATE_LIMIT_HMAC_SECRET=%s\nVALKEY_URL=redis://:%s@valkey:6379\n' "$(filler hmac)" "$(filler valkey)" > "$1/backend.bot.env"
  printf 'OPOSICONTROL_BACKEND_TOKEN=%s\n' "$(filler admin)" > "$1/backend.control.env"
  chmod 600 "$1"/*.env
}
n=0
expect_fail() { # <desc> <rule> <args...>
  local desc="$1" rule="$2" out; shift 2; n=$((n + 1))
  out="$(bash "$CHK" "$@" 2>&1)" && { echo "TEST FAIL: '$desc' passed"; exit 1; }
  echo "$out" | grep -q "^FAIL: $rule " || { echo "TEST FAIL: '$desc' did not fail with $rule:"; echo "$out"; exit 1; }
  echo "$out" | grep -qF "$(filler hmac)" && { echo "TEST FAIL: '$desc' printed a value"; exit 1; }
  echo "ok: $desc -> $rule"
}

bash "$CHK" --repo >/dev/null || { echo "TEST FAIL: the repository staging files should pass"; bash "$CHK" --repo; exit 1; }
d="$tmp/stg"; p="$tmp/prod"
clean "$d"; mkdir -p "$p"; printf 'AUTH_RATE_LIMIT_HMAC_SECRET=%s\n' "$(filler prod-only)" > "$p/backend.bot.env"
bash "$CHK" --env-dir "$d" --prod-dir "$p" >/dev/null || { echo "TEST FAIL: clean synthetic staging dir should pass"; bash "$CHK" --env-dir "$d" --prod-dir "$p"; exit 1; }

clean "$d"; sed -i "s#^SUPABASE_URL=.*#SUPABASE_URL=https://$MAIN_REF.supabase.co#" "$d/backend.common.env"
expect_fail "SUPABASE_URL points at MAIN" R1 --env-dir "$d"
clean "$d"; sed -i "s#^SUPABASE_SERVICE_ROLE_KEY=.*#SUPABASE_SERVICE_ROLE_KEY=$(jwt "$MAIN_REF")#" "$d/backend.common.env"
expect_fail "service key is a MAIN JWT" R2 --env-dir "$d"
clean "$d"; sed -i 's#^SUPABASE_URL=.*#SUPABASE_URL=http://127.0.0.1:54321#' "$d/backend.common.env"
expect_fail "SUPABASE_URL is not a hosted project URL" R3 --env-dir "$d"
clean "$d"; printf 'PAYMENTS_SANDBOX_SECRET=%s\n' "$(filler sandbox)" >> "$d/backend.bot.env"
expect_fail "PAYMENTS_SANDBOX_SECRET defined" R4 --env-dir "$d"
clean "$d"; printf 'OPOSICONTROL_ALLOW_DEV_TOKEN=true\n' >> "$d/backend.control.env"
expect_fail "development-only variable defined" R4 --env-dir "$d"
clean "$d"; printf 'AUTH_RATE_LIMIT_HMAC_SECRET=%s\n' "$(filler hmac)" >> "$p/backend.bot.env"
expect_fail "HMAC secret reused from production" R5 --env-dir "$d" --prod-dir "$p"
if [ "$(uname -s)" = Linux ]; then
  clean "$d"; chmod 644 "$d/backend.bot.env"
  expect_fail "env file readable by others" R6 --env-dir "$d"
fi
# The repo scan itself: a MAIN ref dropped into a versioned staging file must fail.
cp ../compose.staging.yaml "$tmp/compose.bak"
trap 'cp "$tmp/compose.bak" ../compose.staging.yaml; rm -rf "$tmp"' EXIT
printf '# %s\n' "$MAIN_REF" >> ../compose.staging.yaml
expect_fail "MAIN ref in a versioned staging file" R1 --repo
cp "$tmp/compose.bak" ../compose.staging.yaml
echo "staging-secrets-check.test OK ($n violations caught, clean dir passes)"
