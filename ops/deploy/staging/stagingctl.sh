#!/usr/bin/env bash
# The ONLY way to run docker compose for STAGING on the production host. It pins the project (-p oposibots-staging), the file
# (-f ops/deploy/compose.staging.yaml) and the staging env file, and refuses to run if anything points at production.
#
#   bash ops/deploy/staging/stagingctl.sh config            render and validate (config -q); prints nothing secret
#   bash ops/deploy/staging/stagingctl.sh up                disk guard + up -d --no-build (never builds on the host)
#   bash ops/deploy/staging/stagingctl.sh ps | logs [svc]   status / last 200 log lines
#   bash ops/deploy/staging/stagingctl.sh stop              stop the containers (keeps them)
#   bash ops/deploy/staging/stagingctl.sh down              remove staging containers + network (named volume kept)
#   bash ops/deploy/staging/stagingctl.sh down --volumes    ... and the staging Prometheus volume (explicit, by project)
#   bash ops/deploy/staging/stagingctl.sh isolation-check   proves staging containers cannot reach production containers
#
# NEVER `docker system prune`, `docker volume prune`, `docker image prune` or `docker network prune` on this host: they
# act on PRODUCTION images, volumes and networks too. Cleanup is by explicit project/name only (this script).
# Variables: STAGING_COMPOSE_ENV (default /etc/oposibots/staging/compose.env), STAGING_MIN_FREE_DISK_MB (5120), STAGING_DISK_PATH (Docker root),
# STAGINGCTL_DRY=1 (print the docker command instead of running it; used by stagingctl.test.sh).
set -euo pipefail

PROJECT=oposibots-staging
ROOT="${STAGINGCTL_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}"
FILE="$ROOT/ops/deploy/compose.staging.yaml"
ENV_FILE="${STAGING_COMPOSE_ENV:-/etc/oposibots/staging/compose.env}"
MIN_DISK_MB="${STAGING_MIN_FREE_DISK_MB:-5120}"

die() { echo "ABORT: $*" >&2; exit 3; }
ok() { echo "ok: $*"; }

guard() {
  [ -f "$FILE" ] || die "missing $FILE"
  grep -Eq '^name:[[:space:]]*oposibots-staging[[:space:]]*$' "$FILE" || die "$FILE is not the staging project (name: must be $PROJECT)"
  # Anything in the environment that could redirect compose to another project or file is refused, not overridden silently.
  [ -z "${COMPOSE_PROJECT_NAME:-}" ] || [ "$COMPOSE_PROJECT_NAME" = "$PROJECT" ] || die "COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME is set; unset it"
  [ -z "${COMPOSE_FILE:-}" ] || die "COMPOSE_FILE is set; unset it (this script passes -f explicitly)"
  [ -z "${COMPOSE_ENV_FILES:-}" ] || die "COMPOSE_ENV_FILES is set; unset it"
  case "$ENV_FILE" in *production*) die "env file $ENV_FILE is a production path" ;; esac
  case "$ENV_FILE" in /etc/oposibots/staging/*|"${STAGINGCTL_ALLOW_ENV_DIR:-/nonexistent}"/*) ;; *) die "env file must live in /etc/oposibots/staging (got $ENV_FILE)" ;; esac
  [ -f "$ENV_FILE" ] || die "missing $ENV_FILE"
  # Path references only (no value is printed): a staging env file must never point at production files.
  if grep -q '/etc/oposibots/production' "$ENV_FILE"; then die "$ENV_FILE references /etc/oposibots/production"; fi
}

dc() {
  local cmd=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$FILE" "$@")
  if [ -n "${STAGINGCTL_DRY:-}" ]; then echo "DRY: ${cmd[*]}"; return 0; fi
  "${cmd[@]}"
}

disk_guard() {
  [ -n "${STAGINGCTL_DRY:-}" ] && { echo "DRY: disk guard skipped"; return 0; }
  local root free
  root="${STAGING_DISK_PATH:-$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)}"
  free="$(df -Pm "$root" 2>/dev/null | awk 'NR==2{print $4}')"
  [ -n "$free" ] || die "cannot read free disk of $root"
  [ "$free" -ge "$MIN_DISK_MB" ] || die "only ${free} MB free on $root (< ${MIN_DISK_MB} MB); free space first (never with prune)"
  ok "disk guard: ${free} MB free on $root"
}

isolation_check() {
  [ -n "${STAGINGCTL_DRY:-}" ] && { echo "DRY: isolation-check"; return 0; }
  local bot net others ips c ip port bad=0
  bot="$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=ktor-bot-1")"
  [ -n "$bot" ] || die "staging ktor-bot-1 is not running"
  # 1. Every staging container is attached ONLY to the staging network.
  for c in $(docker ps -q --filter "label=com.docker.compose.project=$PROJECT"); do
    net="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$c")"
    [ "$(echo $net)" = "${PROJECT}_staging" ] || { echo "FAIL: $(docker inspect -f '{{.Name}}' "$c") attached to: $net"; bad=1; }
    # Published ports: loopback only.
    if docker inspect -f '{{range $p, $b := .NetworkSettings.Ports}}{{range $b}}{{.HostIp}} {{end}}{{end}}' "$c" | tr ' ' '\n' | grep -Ev '^(127\.0\.0\.1)?$' | grep -q .; then
      echo "FAIL: $(docker inspect -f '{{.Name}}' "$c") publishes on a non-loopback address"; bad=1
    fi
  done
  # 2. No foreign container is attached to the staging network.
  others="$(docker network inspect -f '{{range .Containers}}{{.Name}} {{end}}' "${PROJECT}_staging")"
  for c in $others; do case "$c" in ${PROJECT}-*) ;; *) echo "FAIL: foreign container $c on the staging network"; bad=1 ;; esac; done
  # 3. From inside staging, every NON-staging container IP is unreachable on the usual ports (TCP connect, 2 s).
  ips="$(docker ps -q | while read -r c; do
      [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$c")" = "$PROJECT" ] && continue
      docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$c"; done | tr ' ' '\n' | grep -E '^[0-9.]+$' | sort -u)"
  for ip in $ips; do
    for port in 8080 6379 9090 9093 3000 9100; do
      if docker exec "$bot" curl -s -o /dev/null --connect-timeout 2 --max-time 3 "http://$ip:$port/" ; then
        echo "FAIL: staging reached $ip:$port"; bad=1
      else
        local rc=$?; case "$rc" in 7|28) ;; 52|56) echo "FAIL: staging connected to $ip:$port (rc $rc)"; bad=1 ;; *) ;; esac
      fi
    done
  done
  [ "$bad" -eq 0 ] || die "isolation check FAILED: stop staging (stagingctl.sh stop) and investigate"
  ok "isolation: staging only on ${PROJECT}_staging, loopback-only ports, $(echo "$ips" | grep -c . || true) non-staging container IPs unreachable"
}

[ $# -ge 1 ] || { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
sub="$1"; shift
guard
case "$sub" in
  config) dc config -q; ok "compose config valid for $PROJECT" ;;
  up) disk_guard; dc up -d --no-build --remove-orphans ;;
  ps) dc ps ;;
  logs) dc logs --tail=200 "$@" ;;
  stop) dc stop ;;
  down)
    if [ "${1:-}" = "--volumes" ]; then dc down --volumes --remove-orphans; else dc down --remove-orphans; fi ;;
  isolation-check) isolation_check ;;
  *) die "unknown subcommand: $sub" ;;
esac
