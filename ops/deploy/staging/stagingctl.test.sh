#!/usr/bin/env bash
# Guard test of stagingctl.sh (dry run: no Docker is touched). The wrapper must always pass -p oposibots-staging and the staging
# -f, and must REFUSE every way of pointing it at production.
set -uo pipefail
cd "$(dirname "$0")/../../.."
W=ops/deploy/staging/stagingctl.sh
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/stg"; printf 'BACKEND_IMAGE=x:1\n' > "$tmp/stg/compose.env"
run() { STAGINGCTL_DRY=1 STAGINGCTL_ALLOW_ENV_DIR="$tmp/stg" STAGING_COMPOSE_ENV="${ENVF:-$tmp/stg/compose.env}" bash "$W" "$@" 2>&1; }
n=0
refuse() { # <desc> <expected text> <command...>
  local desc="$1" exp="$2" out; shift 2; n=$((n + 1))
  out="$("$@" 2>&1)" && { echo "TEST FAIL: '$desc' was accepted:"; echo "$out"; exit 1; }
  echo "$out" | grep -q "ABORT: .*$exp" || { echo "TEST FAIL: '$desc' refused for another reason:"; echo "$out"; exit 1; }
  echo "$out" | grep -q '^DRY: docker' && { echo "TEST FAIL: '$desc' still printed a docker command"; exit 1; }
  echo "ok: refuses $desc"
}

out="$(run up)" || { echo "TEST FAIL: dry up failed:"; echo "$out"; exit 1; }
echo "$out" | grep -q '^DRY: docker compose -p oposibots-staging --env-file .* -f .*/ops/deploy/compose.staging.yaml up -d --no-build --remove-orphans$' \
  || { echo "TEST FAIL: up is not pinned to project/file:"; echo "$out"; exit 1; }
out="$(run down)"; echo "$out" | grep -q -- '-p oposibots-staging .* down --remove-orphans$' || { echo "TEST FAIL: down without volumes"; exit 1; }
echo "$out" | grep -q -- '--volumes' && { echo "TEST FAIL: plain down removes volumes"; exit 1; }
echo "ok: up/down pinned to -p oposibots-staging and the staging -f; down keeps volumes unless --volumes"

refuse "COMPOSE_PROJECT_NAME=oposibots-production" "COMPOSE_PROJECT_NAME" env COMPOSE_PROJECT_NAME=oposibots-production bash -c "$(declare -f run); tmp='$tmp' W='$W' run up"
refuse "COMPOSE_FILE pointing at production" "COMPOSE_FILE" env COMPOSE_FILE=ops/deploy/compose.production.yaml bash -c "$(declare -f run); tmp='$tmp' W='$W' run up"
mkdir -p "$tmp/production"; cp "$tmp/stg/compose.env" "$tmp/production/compose.env"
refuse "a production env file path" "production path" env ENVF="$tmp/production/compose.env" bash -c "$(declare -f run); tmp='$tmp' W='$W' run up"
refuse "an env file outside /etc/oposibots/staging" "must live in /etc/oposibots/staging" \
  env STAGINGCTL_DRY=1 STAGING_COMPOSE_ENV="$tmp/stg/compose.env" bash "$W" up
printf 'STAGING_COMMON_ENV_FILE=/etc/oposibots/production/backend.common.env\n' >> "$tmp/stg/compose.env"
refuse "a staging env file that references production files" "references /etc/oposibots/production" run up
printf 'BACKEND_IMAGE=x:1\n' > "$tmp/stg/compose.env"
# A copy of the repo whose compose file is renamed to production must be refused.
mkdir -p "$tmp/root/ops/deploy/staging"; cp "$W" "$tmp/root/ops/deploy/staging/"
sed 's/^name: oposibots-staging$/name: oposibots-production/' ops/deploy/compose.staging.yaml > "$tmp/root/ops/deploy/compose.staging.yaml"
refuse "a compose file that is not the staging project" "is not the staging project" \
  env STAGINGCTL_DRY=1 STAGINGCTL_ROOT="$tmp/root" STAGINGCTL_ALLOW_ENV_DIR="$tmp/stg" STAGING_COMPOSE_ENV="$tmp/stg/compose.env" bash "$tmp/root/ops/deploy/staging/stagingctl.sh" up
echo "stagingctl.test OK ($n refusals)"
