# Server resource plan (TEMPLATE)

**The real production host has not been inventoried.** Every number marked `PLACEHOLDER` is a repo default from `ops/deploy/compose.production.yaml`, not a sizing decision. Fill the "Host" table from the inventory commands (section 5), then derive limits (section 3). Validate with `PERFORMANCE_TEST_PLAN.md`; do not size from this document alone.

## 1. Services and current defaults (read from compose.production.yaml)

| Service | Role | cpus | mem_limit | JVM / config | Status |
|---|---|---|---|---|---|
| ktor-bot-1, ktor-bot-2 | Consumer API (`SERVER_PROFILE=bot`), stateless pair behind Caddy `least_conn` | 1.0 (`BOT_CPUS`) PLACEHOLDER | 768m (`BOT_MEM_LIMIT`) PLACEHOLDER | `MaxRAMPercentage=60`, `InitialRAMPercentage=30`, `ExitOnOutOfMemoryError` PLACEHOLDER | pids_limit 512 |
| ktor-control | Admin/authoring (`control`), ffmpeg/PDF/multipart | 1.5 PLACEHOLDER | 2g PLACEHOLDER | `MaxRAMPercentage=45` (ffmpeg is a child process of this container) PLACEHOLDER | |
| valkey | Rate-limit counters only (no persistence) | 0.25 PLACEHOLDER | 96m, `maxmemory 64mb`, `volatile-ttl` PLACEHOLDER | | |
| caddy | TLS termination, gzip/zstd, LB, health checks | 1.0 PLACEHOLDER | 192m PLACEHOLDER | | |
| prometheus | 15 d / 4 GB retention | 0.5 PLACEHOLDER | 512m PLACEHOLDER | | |
| grafana | Dashboards | 0.5 PLACEHOLDER | 256m PLACEHOLDER | | |
| **Sum of limits** | | **5.75 vCPU** PLACEHOLDER | **~4.5 GiB** (192+1536+2048+96+512+256 MiB) PLACEHOLDER | | limits, not reservations |

## 2. Priority order and what it means

Priority when resources are short (who is protected first, who yields first): **bot > control > valkey > caddy > monitoring**. Practical meaning:

- Protect the bot pair's CPU and memory first; they carry end-user traffic. Give them CPU `cpus` guarantees via sizing, not by starving them.
- Control (admin, imports, ffmpeg) is bursty and tolerates queuing; its limits must never let it take the bot's cores (keep `cpus` limits, consider `cpu_shares`/`cpuset` pinning if the host is small).
- Valkey is tiny but on every authenticated request (fail-closed 503): keep it responsive (low latency matters more than size); do not let it be CPU-starved. Note Caddy is also on every request path: if the host is small, do not squeeze Caddy below what the test shows it needs - the order above is the shedding order under shortage, not a statement that the edge is unimportant.
- Prometheus/Grafana yield first. Never let them OOM the host; they have hard `mem_limit`.

## 3. How to derive limits from the real host (formulas, not values)

Let `HOST_CPU` = logical CPUs, `HOST_RAM` = total RAM, from the inventory.

1. **Reserve for the OS + Docker + page cache** before allocating to containers:
   - CPU: reserve max(1 vCPU, 10% of `HOST_CPU`) for kernel, dockerd, containerd, sshd, Caddy TLS bursts. `PLACEHOLDER` percent, validate under test.
   - RAM: reserve `OS_RESERVE = max(1 GiB, 15% of HOST_RAM)` for kernel, dockerd, journald and **page cache** (Prometheus TSDB, container layers, logs). Do not allocate 100% of RAM to `mem_limit` sums: the Linux page cache is what keeps Prometheus and Docker fast, and OOM-killer decisions are made on total pressure.
   - `AVAILABLE_RAM = HOST_RAM - OS_RESERVE`. `sum(mem_limit)` must be <= ~`0.9 x AVAILABLE_RAM` to leave swap-free headroom.
2. **JVM heap (`MaxRAMPercentage`)**: the container limit must cover heap + native memory. Native/non-heap per JVM = metaspace + code cache + thread stacks (`~1 MiB x live threads`; the bot can reach ~64 IO threads + Netty + GC threads) + direct buffers (Netty) + GC structures. Rule of thumb to validate, not to trust: `native_non_heap ~ 150-300 MiB` per bot JVM PLACEHOLDER. Then `MaxRAMPercentage = (mem_limit - native_non_heap - safety 10%) / mem_limit x 100`, rounded down. Example shape only: with 768m and ~250 MiB native the heap is ~60% - which is what the current default encodes, so the default is plausible but UNVERIFIED. Confirm with `jvm_memory_used_bytes` (heap and non-heap) at the knee, and watch for OOM exits (`ExitOnOutOfMemoryError` restarts the container).
   - Control: ffmpeg runs as a child process of the container; its memory is NOT in the JVM heap. Keep `MaxRAMPercentage` lower (current 45) and size `mem_limit = heap + native + ffmpeg_peak + tmpfs`. `/tmp` is a 512m tmpfs per Ktor container and **counts against the container memory**: include 512m in worst-case accounting for control (uploads) PLACEHOLDER.
3. **Bot CPU**: `BOT_CPUS` per replica from the test: the smallest value after which adding CPU no longer moves the knee (Ktor CPU bound) - not from guesswork. With `cpus: 1.0` the JVM sees ~1 CPU; Ktor/Netty/GC thread counts are derived from that (audit hazard 8). Number of replicas = decision rule in the test plan (only add if Ktor CPU is the bottleneck).
4. **Caddy**: size CPU from measured TLS + compression load at the knee; memory 192m is probably fine, verify.
5. **Valkey**: memory = keys x ~(100-200 B) for active users x operations x window; `maxmemory` must exceed that so `volatile-ttl` never evicts live counters (evictions weaken the limiter). Measure `used_memory` and `evicted_keys` during the 5000-user stage.
6. **Prometheus**: disk and RAM scale with series; check `prometheus_tsdb_head_series`; retention is capped at 4 GB in the compose.
7. **Disk**: Docker json-file logs capped at 20m x 5 per container (compose), Prometheus <= 4 GB, images + volumes. Keep >= 20% free on the Docker data root.
8. **Final check**: `sum(cpus) <= HOST_CPU - CPU_RESERVE` is NOT required (limits may overcommit CPU because it is compressible), but `sum(mem_limit) <= 0.9 x AVAILABLE_RAM` is required (memory is not compressible).

## 4. Host table to fill (all PLACEHOLDER until inventoried)

| Item | Value |
|---|---|
| Provider / instance type | PLACEHOLDER |
| Logical CPUs / model / cgroup version | PLACEHOLDER |
| RAM total / swap | PLACEHOLDER |
| Disk size / type / free on Docker data root | PLACEHOLDER |
| OS / kernel | PLACEHOLDER |
| Docker / Compose version, storage driver, default log driver | PLACEHOLDER |
| Listening ports and public exposure | PLACEHOLDER |
| Firewall rules (host + provider security group) | PLACEHOLDER |
| Network bandwidth / egress to Supabase region RTT | PLACEHOLDER |

## 5. Read-only inventory commands (NOT executed; run on the server, none changes state)

```bash
# CPU
lscpu
nproc
grep -E 'model name|cpu MHz' /proc/cpuinfo | sort | uniq -c
stat -fc %T /sys/fs/cgroup            # cgroup2fs = v2
# RAM / swap
free -h
grep -E 'MemTotal|MemAvailable|SwapTotal|Cached' /proc/meminfo
swapon --show
# Disk
lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT,ROTA
df -hT
sudo du -sh /var/lib/docker 2>/dev/null
# OS
cat /etc/os-release; uname -r; uptime
# Docker
docker version
docker info
docker compose version
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
docker system df
docker stats --no-stream
docker inspect -f '{{.Name}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}} pids={{.HostConfig.PidsLimit}}' $(docker ps -q)
# Ports / listeners
ss -tulpn
# Firewall (use whichever exists)
sudo ufw status verbose
sudo nft list ruleset
sudo iptables -S
sudo firewall-cmd --list-all
# Network
ip -br addr
ip route
cat /etc/resolv.conf
# Kernel limits that matter for many connections
sysctl net.core.somaxconn net.ipv4.ip_local_port_range net.ipv4.tcp_max_syn_backlog fs.file-max
ulimit -n
# Time sync (JWT exp / clock skew)
timedatectl
```

After inventory: fill section 4, compute section 3, change values via the compose env vars (`BOT_CPUS`, `BOT_MEM_LIMIT`, `BOT_JAVA_TOOL_OPTIONS`, `CONTROL_*`, `VALKEY_MEM_LIMIT`, `CADDY_MEM_LIMIT`, `PROMETHEUS_MEM_LIMIT`, `GRAFANA_MEM_LIMIT`) - not by editing the compose - and re-run the stages.
