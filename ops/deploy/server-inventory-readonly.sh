#!/usr/bin/env bash
# READ-ONLY inventory of the production host. It changes NOTHING: no install, no restart, no write outside stdout.
# It never prints secret values: environment files are listed by variable NAME only, compose files are shown with
# values of secret-looking keys masked, container environments are reduced to names.
# Usage on the server:   bash server-inventory-readonly.sh > inventory.txt 2>&1     (then send inventory.txt)
# Commands that need root are tried with `sudo -n` (never prompts); if sudo is not available the section says so.
set -u
sec() { printf '\n===== %s =====\n' "$1"; }
try() { "$@" 2>&1 || echo "(not available: $*)"; }
sroot() { sudo -n "$@" 2>/dev/null || echo "(needs sudo: $*)"; }
mask() { sed -E 's/((KEY|SECRET|TOKEN|PASSWORD|PASS|DSN|HMAC|CREDENTIAL)[A-Z0-9_]*[=:][[:space:]]*).*/\1<masked>/I; s#(://[^:/@[:space:]]+:)[^@[:space:]]+@#\1<masked>@#g'; }
names() { sed -E 's/^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=.*/\2/' | grep -E '^[A-Za-z_][A-Za-z0-9_]*$' | sort -u | tr '\n' ' '; echo; }

sec "HOST"; hostname; date -u +%FT%TZ; uname -srm; ( . /etc/os-release && echo "$PRETTY_NAME" ); uptime
sec "CPU"; nproc; lscpu | grep -E 'Model name|^CPU\(s\)|Thread|Core|Socket|MHz'
sec "MEMORY"; free -m; grep -E 'MemTotal|MemAvailable|SwapTotal' /proc/meminfo
sec "DISK"; df -hT -x tmpfs -x devtmpfs -x overlay; try lsblk -o NAME,SIZE,TYPE,MOUNTPOINT
sec "LOAD (5 samples)"; try vmstat 1 5
sec "TOP MEMORY PROCESSES"; ps -eo pid,comm,%cpu,%mem,rss --sort=-rss | head -12

sec "DOCKER"; try docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'; try docker compose version
try docker info --format 'driver={{.Driver}} root={{.DockerRootDir}} cpus={{.NCPU}} mem={{.MemTotal}} logging={{.LoggingDriver}}'
sec "CONTAINERS"; try docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
sec "CONTAINER DETAILS"
for c in $(docker ps -aq 2>/dev/null); do
  docker inspect "$c" --format 'name={{.Name}} image={{.Config.Image}} imageId={{.Image}} restart={{.HostConfig.RestartPolicy.Name}} mem={{.HostConfig.Memory}} nanoCpus={{.HostConfig.NanoCpus}} readonly={{.HostConfig.ReadonlyRootfs}} user={{.Config.User}} created={{.Created}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} labels.revision={{index .Config.Labels "org.opencontainers.image.revision"}} compose.project={{index .Config.Labels "com.docker.compose.project"}} compose.dir={{index .Config.Labels "com.docker.compose.project.working_dir"}}' 2>/dev/null
  printf '  env names: '; docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | names
  printf '  mounts: '; docker inspect "$c" --format '{{range .Mounts}}{{.Type}}:{{.Source}}->{{.Destination}}({{if .RW}}rw{{else}}ro{{end}}) {{end}}' 2>/dev/null; echo
  printf '  networks: '; docker inspect "$c" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}={{$v.IPAddress}} {{end}}' 2>/dev/null; echo
done
sec "IMAGES"; try docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.CreatedSince}}\t{{.Size}}'
sec "VOLUMES / NETWORKS"; try docker volume ls; try docker network ls; try docker system df
sec "CONTAINER RESOURCE USE (one sample)"; try docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}'

sec "LISTENING PORTS"; sroot ss -ltnp | awk 'NR==1 || /LISTEN/' | cut -c1-160
sec "FIREWALL"; sroot ufw status verbose; sroot nft list ruleset | head -60; sroot iptables -S | head -40
sec "SSH DAEMON (effective, no keys)"; sroot sshd -T | grep -E '^(port|permitrootlogin|passwordauthentication|allowusers|listenaddress) '

sec "DEPLOY DIRECTORIES"
for d in /opt/oposicontrol /opt/oposicontrol-deploy /opt/oposicontrol-monitoring /etc/oposibots /etc/oposibots/production /etc/caddy /srv; do
  [ -e "$d" ] && { echo "--- $d"; ls -la "$d" 2>&1 | head -40; } || echo "--- $d (absent)"
done
sec "GIT STATE OF DEPLOY DIRECTORIES"
for d in /opt/oposicontrol /opt/oposicontrol-deploy /opt/oposicontrol-monitoring; do
  [ -d "$d/.git" ] && { echo "--- $d"; git -C "$d" rev-parse HEAD 2>&1; git -C "$d" status --short 2>&1 | head -10; git -C "$d" log --oneline -3 2>&1; }
done
sec "ENV FILES (variable NAMES only)"
for f in /etc/oposibots/production/*.env /opt/oposicontrol*/*.env /opt/oposicontrol*/.env; do
  [ -f "$f" ] || continue; echo "--- $f ($(stat -c '%U:%G %a' "$f" 2>/dev/null))"; ( cat "$f" 2>/dev/null || sudo -n cat "$f" 2>/dev/null ) | grep -v '^[[:space:]]*#' | names
done
sec "COMPOSE FILES (secret-looking values masked)"
for f in /opt/oposicontrol*/*compose*.y*ml /opt/oposicontrol*/**/*compose*.y*ml; do [ -f "$f" ] || continue; echo "--- $f"; mask < "$f" | head -150; done
sec "CADDY"; try caddy version; sroot systemctl is-active caddy
for f in /etc/caddy/Caddyfile /opt/oposicontrol*/Caddyfile /opt/oposicontrol*/caddy/Caddyfile*; do [ -f "$f" ] || continue; echo "--- $f"; ( cat "$f" 2>/dev/null || sudo -n cat "$f" ) | mask | head -150; done
sec "SYSTEMD UNITS OF INTEREST"; systemctl list-units --type=service --state=running 2>/dev/null | grep -i -E 'docker|caddy|valkey|redis|prometheus|grafana|node_exporter|fail2ban|ufw|cron' | cut -c1-140
sec "CRON"; try crontab -l; sroot ls -la /etc/cron.d
sec "LOCAL HEALTH"; for u in http://127.0.0.1:8080/health/live http://127.0.0.1:8080/health/ready http://127.0.0.1:8080/health; do printf '%s -> ' "$u"; curl -s -m 5 -o /dev/null -w '%{http_code}\n' "$u" || echo fail; done
curl -s -m 5 http://127.0.0.1:8080/health 2>/dev/null | cut -c1-200; echo
sec "JAVA ON HOST (if any)"; try java -version
sec "TIME SYNC"; try timedatectl show -p NTPSynchronized -p Timezone
sec "END"; echo "no change was made by this script"
