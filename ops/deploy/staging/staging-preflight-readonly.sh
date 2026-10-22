#!/usr/bin/env bash
# CAPACITY GATE for staging on the production host. READ-ONLY: it changes nothing, prints no secret, and decides GO / ABORT.
# Run on oposibots-prod-01 AFTER the owner's GO and BEFORE any staging write:   bash staging-preflight-readonly.sh
# Exit 0 = GO, 2 = ABORT (a threshold failed), 1 = could not measure. Every threshold can be read below; change them only
# with a written reason in the runbook.
#
# Thresholds (staging hard limits total 2048 MiB RAM and 1.65 CPU, see ops/deploy/compose.staging.yaml):
#   RAM  : MemAvailable - 2048 MiB (staging) >= 1536 MiB headroom for production peaks, page cache and Docker.
#   SWAP : swap used <= 512 MiB (a host already swapping cannot host staging).
#   CPU  : nproc >= 2 and 5-min load average / nproc <= 0.70.
#   DISK : >= 5120 MiB free and <= 85 % used on the Docker root and on / .
#   PROD : every running production container is healthy (or has no healthcheck); nothing restarting.
#   NET  : staging subnet does not overlap any Docker network or host route; ports 18181/18183 free; no oposibots-staging leftovers.
set -u
STG_MEM_MB=2048; HEADROOM_MB="${STAGING_HEADROOM_MB:-1536}"; MAX_SWAP_MB=512; MAX_LOAD_PER_CPU=0.70
MIN_DISK_MB=5120; MAX_DISK_PCT=85
SUBNET="${STAGING_SUBNET:-172.30.28.0/24}"; PORTS="${STAGING_PORTS:-18181 18183}"
abort=0; unknown=0
go() { echo "GO     $*"; }
no() { echo "ABORT  $*"; abort=1; }
unk() { echo "UNKNOWN $*"; unknown=1; }
sec() { printf '\n== %s\n' "$1"; }

sec "host"; hostname; date -u +%FT%TZ; uname -srm
sec "memory"
avail="$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo 2>/dev/null)"
total="$(awk '/^MemTotal:/{print int($2/1024)}' /proc/meminfo 2>/dev/null)"
swap_used="$(awk '/^SwapTotal:/{t=$2}/^SwapFree:/{f=$2}END{print int((t-f)/1024)}' /proc/meminfo 2>/dev/null)"
free -m 2>/dev/null
if [ -n "$avail" ]; then
  left=$((avail - STG_MEM_MB))
  if [ "$left" -ge "$HEADROOM_MB" ]; then go "RAM: total ${total} MiB, available ${avail} MiB, after staging limits ${left} MiB >= ${HEADROOM_MB}"
  else no "RAM: available ${avail} MiB - staging ${STG_MEM_MB} = ${left} MiB < ${HEADROOM_MB} MiB headroom"; fi
  if [ "${swap_used:-0}" -le "$MAX_SWAP_MB" ]; then go "SWAP: ${swap_used} MiB used"; else no "SWAP: ${swap_used} MiB used > ${MAX_SWAP_MB}"; fi
else unk "RAM: /proc/meminfo unreadable"; fi

sec "cpu"
ncpu="$(nproc 2>/dev/null)"; load5="$(awk '{print $2}' /proc/loadavg 2>/dev/null)"
if [ -n "$ncpu" ] && [ -n "$load5" ]; then
  ratio="$(awk -v l="$load5" -v n="$ncpu" 'BEGIN{printf "%.2f", l/n}')"
  if [ "$ncpu" -ge 2 ] && awk -v r="$ratio" -v m="$MAX_LOAD_PER_CPU" 'BEGIN{exit !(r <= m)}'; then go "CPU: ${ncpu} cores, load5 ${load5} (${ratio}/core)"
  else no "CPU: ${ncpu} cores, load5 ${load5} (${ratio}/core); need >= 2 cores and <= ${MAX_LOAD_PER_CPU}/core"; fi
else unk "CPU: nproc or loadavg unreadable"; fi

sec "disk"
droot="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)"
for m in "$droot" /; do
  line="$(df -Pm "$m" 2>/dev/null | awk 'NR==2{gsub("%","",$5); print $4, $5}')"
  [ -n "$line" ] || { unk "DISK: cannot read $m"; continue; }
  set -- $line
  if [ "$1" -ge "$MIN_DISK_MB" ] && [ "$2" -le "$MAX_DISK_PCT" ]; then go "DISK $m: ${1} MiB free, ${2}% used"
  else no "DISK $m: ${1} MiB free, ${2}% used (need >= ${MIN_DISK_MB} MiB and <= ${MAX_DISK_PCT}%)"; fi
done
docker system df 2>/dev/null

sec "production containers (one sample)"
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' 2>/dev/null
bad_prod=0
while IFS='|' read -r name status health proj; do
  [ -z "$name" ] && continue
  [ "$proj" = oposibots-staging ] && continue
  case "$status" in restarting|dead|exited) no "PROD container $name is $status"; bad_prod=1 ;; esac
  case "$health" in unhealthy|starting) no "PROD container $name health=$health"; bad_prod=1 ;; esac
done < <(docker ps -a --format '{{.Names}}' 2>/dev/null | while read -r c; do
  docker inspect -f '{{.Name}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{index .Config.Labels "com.docker.compose.project"}}' "$c" 2>/dev/null | sed 's#^/##'; done)
[ "$bad_prod" -eq 0 ] && go "PROD: no container restarting, exited or unhealthy"
echo "sum of memory limits of running containers (MiB): $(docker ps -q | xargs -r docker inspect -f '{{.HostConfig.Memory}}' 2>/dev/null | awk '{s+=$1}END{print int(s/1048576)}')"

sec "network"
nets="$(docker network ls -q 2>/dev/null | xargs -r docker network inspect -f '{{.Name}} {{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null)"
echo "$nets"
ip -4 route 2>/dev/null
overlap="$(python3 - "$SUBNET" <<'PY' 2>/dev/null
import ipaddress, subprocess, sys
s = ipaddress.ip_network(sys.argv[1])
out = subprocess.run(["sh", "-c", "docker network ls -q | xargs -r docker network inspect -f '{{.Name}} {{range .IPAM.Config}}{{.Subnet}} {{end}}'; ip -4 route | awk '{print \"route\", $1}'"], capture_output=True, text=True).stdout
for line in out.splitlines():
    parts = line.split()
    for p in parts[1:]:
        try:
            n = ipaddress.ip_network(p, strict=False)
        except ValueError:
            continue
        if n.version == 4 and n.overlaps(s) and parts[0] != "oposibots-staging_staging" and str(n) != "0.0.0.0/0":
            print(parts[0], p)
PY
)"
if command -v python3 >/dev/null 2>&1; then
  if [ -z "$overlap" ]; then go "SUBNET $SUBNET overlaps no Docker network and no host route"; else no "SUBNET $SUBNET overlaps: $(echo $overlap)"; fi
else unk "SUBNET: python3 missing, check the list above by hand"; fi
for p in $PORTS; do
  if ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]$p\$"; then no "PORT $p already listening"; else go "PORT $p free"; fi
done
left_over="$(docker ps -aq --filter label=com.docker.compose.project=oposibots-staging 2>/dev/null | wc -l)"
echo "existing oposibots-staging containers: $left_over (re-run of up is fine; unexpected ones are not)"

sec "staging secrets dir (names and modes only)"
ls -la /etc/oposibots/staging 2>&1 | awk '{print $1, $3, $4, $NF}'

sec "caddy"
caddy version 2>/dev/null; systemctl is-active caddy 2>/dev/null
echo
if [ "$abort" -ne 0 ]; then echo "RESULT: ABORT (do not start staging)"; exit 2; fi
if [ "$unknown" -ne 0 ]; then echo "RESULT: UNKNOWN (a measure failed; decide by hand from the output)"; exit 1; fi
echo "RESULT: GO"; exit 0
