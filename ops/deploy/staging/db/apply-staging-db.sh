#!/usr/bin/env bash
# Applies the STAGING schema migrations or the synthetic seed to the STAGING Supabase project, one file at a time, stopping at
# the first error. Runs from the operator's machine (not from the production host) with psql inside the pinned Supabase
# Postgres image. It never prints the connection string, and refuses any target that is not the declared staging project.
#
#   bash ops/deploy/staging/db/apply-staging-db.sh --ref <staging project ref> --db-url-file <file> --list migrations [--from <name>] [--dry-run]
#   bash ops/deploy/staging/db/apply-staging-db.sh --ref <staging project ref> --db-url-file <file> --list seed       [--from <name>] [--dry-run]
#
# <file>: ONE line, the staging "Session pooler" or direct connection URI copied from the STAGING dashboard (Connect), mode 0600,
# outside the repo. Lists: ops/deploy/staging/db/migrations.order.txt (supabase/migrations) and seed.order.txt (ops/local-exam/sql).
# The base schema (decision D-STG-1 of the runbook) is NOT applied by this script.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
IMAGE="${PSQL_IMAGE:-public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9}"
MAIN_REF="$(printf '%s%s' ndiooyyqta eysnedywer)"
ref="" urlf="" list="" from="" dry=0
die() { echo "ABORT: $*" >&2; exit 3; }
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) ref="${2:-}"; shift 2 ;;
    --db-url-file) urlf="${2:-}"; shift 2 ;;
    --list) list="${2:-}"; shift 2 ;;
    --from) from="${2:-}"; shift 2 ;;
    --dry-run) dry=1; shift ;;
    *) die "unknown argument $1" ;;
  esac
done
echo "$ref" | grep -Eq '^[a-z0-9]{20}$' || die "--ref must be the 20-character STAGING project ref"
[ "$ref" != "$MAIN_REF" ] || die "--ref is the MAIN project"
[ -f "$urlf" ] || die "--db-url-file not found"
url="$(head -1 "$urlf" | tr -d '\r')"
echo "$url" | grep -qi "$MAIN_REF" && die "the connection string points at MAIN"
echo "$url" | grep -q "$ref" || die "the connection string does not contain the staging ref $ref"
echo "$url" | grep -Eq '^postgres(ql)?://' || die "the connection string is not a postgres URI"
case "$list" in
  migrations) manifest=ops/deploy/staging/db/migrations.order.txt; dir=supabase/migrations; ext=.sql ;;
  seed) manifest=ops/deploy/staging/db/seed.order.txt; dir=ops/local-exam/sql; ext= ;;
  *) die "--list must be migrations or seed" ;;
esac
items="$(grep -Ev '^[[:space:]]*(#|$)' "$manifest" | tr -d '\r')"
[ -z "$from" ] || { echo "$items" | grep -qx "$from" || die "--from $from is not in $manifest"; items="$(echo "$items" | sed -n "/^$from\$/,\$p")"; }
for i in $items; do [ -f "$dir/$i$ext" ] || die "missing $dir/$i$ext"; done
echo "target: staging project $ref; list: $list ($(echo "$items" | wc -l | tr -d ' ') files)"
export MSYS2_ARG_CONV_EXCL="*"
for i in $items; do
  if [ "$dry" = 1 ]; then echo "DRY  $i"; continue; fi
  if docker run --rm -i -e "DBURL=$url" "$IMAGE" sh -c 'psql "$DBURL" -X -q -v ON_ERROR_STOP=1 -f -' < "$dir/$i$ext" > /tmp/apply-staging-$$.log 2>&1; then
    echo "ok   $i"
  else
    echo "FAIL $i (stopped; nothing after it was applied). Errors:"; grep -E 'ERROR|DETAIL|HINT' /tmp/apply-staging-$$.log | head -8
    rm -f /tmp/apply-staging-$$.log; exit 1
  fi
done
rm -f /tmp/apply-staging-$$.log
echo "DONE $list"
