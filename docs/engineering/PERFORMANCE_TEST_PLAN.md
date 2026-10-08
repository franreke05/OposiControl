# Performance test plan (bot profile)

**No capacity number is claimed. None exists until this plan is executed.** Every threshold below is a parameter to be set from an agreed SLO. Tooling: `ops/loadtest/` (k6). Hazards that motivate the order of work: `PERFORMANCE_AUDIT.md`.

## 1. Preconditions

- Target = local or staging stack with the **same topology** as `ops/deploy/compose.production.yaml` (2 x ktor-bot behind Caddy `least_conn`, Valkey, Prometheus). Supabase = a disposable test project / branch with seeded fixtures. **Never MAIN, never production.**
- Seed >= as many users as the VU level (per-user limiter: ATTEMPT_ANSWER 600/60 s, ATTEMPT_REVIEW 120/60 s, ATTEMPT_START 30/300 s, ME 60/60 s ...). Otherwise the run measures the limiter, not capacity.
- Load generator must not be the bottleneck: separate machine or verified k6 CPU < 70% and `dropped_iterations` = 0.
- Raise `SLOW_REQUEST_THRESHOLD_MS` (default 1000) during runs to avoid log amplification.
- Record per run: git sha, image tag, `BOT_CPUS`, `BOT_MEM_LIMIT`, `BOT_JAVA_TOOL_OPTIONS`, replica count, Supabase plan/region, fixture sizes, k6 version, `RUN_ID`.

## 2. Stages

Per scenario (`read_heavy`, `mixed`, `write_heavy`, `auth`), run profiles 100 -> 500 -> 1000 -> 2000 -> 3000 -> 5000 VUs (ramp 2 m, hold 5 m, ramp-down 1 m; adjustable). Start at 100 and **stop climbing at the first stage that violates a gate or shows a saturated resource**; the earlier stage is the last clean one. Repeat each stage >= 3 times and report median and range, not a single run. `auth` is run at low VU only (it is limiter-bound); its output is limiter behaviour, not capacity.

## 3. What to measure per stage

Client side (k6 JSON summary): requests/s (`http_reqs.rate`), `app_errors` rate, `rate_limited` rate, `status_503` count, `server_errors_5xx`, p50/p95/p99 of `http_req_duration` split by `kind:read|write` and by `name` (route template), `dropped_iterations`, `iteration_duration`.

Server side - **only metrics that exist today**:

| Question | Source / metric (exists in code or dashboards) |
|---|---|
| Request rate, errors, latency by route | `ktor_http_server_requests_seconds_count`, `_bucket`, (histogram enabled: PrometheusMetrics.kt `percentilesHistogram(true)`; used in `ops/monitoring/grafana/dashboards/api-overview.json`) |
| Heap pressure | `jvm_memory_used_bytes` / `jvm_memory_max_bytes` (area=heap) (`jvm.json`) |
| GC | `jvm_gc_pause_seconds_count/_sum` (`jvm.json`) |
| Thread count (IO pool growth to 64) | `jvm_threads_live_threads` (`jvm.json`) |
| Replica up / scrape health | `up{job="ktor"}` (prometheus.production.yml targets ktor-bot-1/2, ktor-control) |
| Container CPU/mem (CPU throttling) | `docker stats` / cgroup `cpu.stat` (`nr_throttled`) on the host - Ktor CPU is NOT exported by the app today beyond JVM metrics; add `process_cpu_usage` only if Micrometer's processor metrics are enabled (NOT verified: not in current dashboards) |
| Supabase latency/errors | Supabase project dashboard (API gateway latency, PostgREST/Auth/Storage 4xx/5xx, DB CPU, connections) + `dependencies.json` Grafana dashboard + `ktor` slow-request events. Upstream latency is not exported per call by the app today |
| Valkey | `valkey-cli INFO stats` (`instantaneous_ops_per_sec`, `evicted_keys`, `rejected_connections`), `INFO cpu`; container CPU against its `cpus: 0.25` |
| Caddy upstream failures | Caddy access log (status 502/503/504, `duration`), `docker logs`; Caddy container CPU against `cpus: 1.0` |
| Limiter behaviour | k6 `rate_limited`, `status_503`; backend log `rate limiter unavailable` |

## 4. Finding the saturation point

Plot, per stage: throughput (RPS) vs VUs, p95/p99 vs VUs, error rate vs VUs. Saturation = first stage where **throughput stops growing with VUs while latency rises** (knee), or errors cross the gate. At that stage, find WHICH resource is pinned (section 5). Confirm with a second run holding that stage and changing exactly one variable.

## 5. Decision rule: MEASURE -> TUNE -> MEASURE

At the knee, classify the bottleneck and act only on the matching row; change one thing, re-run the same stage, compare medians.

| Observation at the knee | Bottleneck | Action | Do NOT |
|---|---|---|---|
| Ktor container CPU at limit / `nr_throttled` rising, Supabase latency flat, `jvm_threads_live_threads` below ~64 | Ktor CPU | Raise `BOT_CPUS`; only if still CPU-bound add a 3rd bot replica (and Caddy upstream, Prometheus target) | Add a replica when Supabase is the limit |
| Threads near the IO cap (64/replica), Ktor CPU low, p95 grows, Supabase latency rising or flat-but-high | Upstream round-trips / blocking pool | Reduce calls per request first (identity cache, parallel reads, catalog cache - audit #2-#4); add admission gate (audit #1) | Add replicas: more replicas = more load on the same Supabase |
| Supabase API latency / DB CPU / 5xx up first | Supabase | Index/RPC/plan work (audit section 5), cache, then plan upsizing | Tune JVM or Caddy |
| `jvm_memory_used_bytes` near max, GC pause sum climbing, OOM exits | Heap | Raise `BOT_MEM_LIMIT` and `MaxRAMPercentage` (see SERVER_RESOURCE_PLAN), look for queue growth | Raise heap before checking queueing |
| Valkey CPU/ops pinned, 503 from limiter, `evicted_keys` > 0 | Valkey | Raise its cpus/maxmemory | |
| Caddy CPU pinned / 502-504 from Caddy with healthy backends | Edge | Raise Caddy cpus, review compression | |
| k6 container CPU high, `dropped_iterations` > 0 | The generator | Move generator / split | Trust the numbers |
| 429 dominates | Limiter by design | Add seeded users or lower VUs per user | Disable the limiter |

Acceptance of a tuning: improvement in p95 or sustained RPS larger than run-to-run variance (>= 3 runs), with no regression in error rate and no new saturated resource. Otherwise revert.

## 6. Reporting

For each (scenario, stage): table of RPS, p50/p95/p99, app_errors, rate_limited, 503s, top-3 route latencies, resource peaks, verdict (clean / knee / failed), bottleneck class and action taken. Capacity is stated only as "last clean stage observed on config X on date Y", never extrapolated beyond the highest stage actually run.
