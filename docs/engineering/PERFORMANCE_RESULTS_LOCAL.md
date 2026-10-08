# Performance results: local stack (2026-10-08)

First measured run of `ops/loadtest`. It is a RELATIVE measurement to find what saturates first; it is not the capacity of
the production server.

## Environment (and why the numbers do not transfer)

- One Windows workstation, Docker Desktop: the load generator (k6), Caddy, two bot replicas, control, Valkey, Prometheus,
  Grafana and a complete local Supabase (Postgres, PostgREST, GoTrue, Kong, Storage) all share the same CPU.
- Stack: `ops/deploy/compose.production.yaml` unchanged (each Ktor container limited to 1 CPU), image built from `4a76d6b`.
- Database: a restore of the MAIN backup with every pending migration applied, plus 200 throwaway users, each with one
  finished and one open attempt on the same published exam (203 questions).
- Stages shortened: 30 s ramp, 90 s hold, 15 s ramp down. Scenario think time as defined in `ops/loadtest`.
- Nothing was sent to MAIN or to any production host.

## Results

| Scenario | Virtual users | Requests/s | Failed | Median | p95 | p99 | 5xx |
|---|---|---|---|---|---|---|---|
| read_heavy | 100 | 65 | 0 % | 32 ms | 83 ms | 159 ms | 0 |
| read_heavy | 500 | 155 | 0 % | 1.4 s | 2.8 s | 3.5 s | 0 |
| read_heavy | 1000 | 142 | 0 % | 4.8 s | 7.8 s | 10.0 s | 14 (7 of them 503) |
| mixed (reads + start/answer/finish) | 100 | 93 | 0 % | 76 ms | 259 ms | 408 ms | 0 |
| mixed | 500 | 95 | 0 % | 3.4 s | 6.4 s | 7.6 s | 2 |

CPU sampled mid-run (100 % = one core):

| Run | bot-1 | bot-2 | PostgREST | Postgres | Kong | Caddy | Valkey | control |
|---|---|---|---|---|---|---|---|---|
| read_heavy 500 | 83 % | 93 % | 324 % | 85 % | 75 % | 35 % | 0.4 % | 0.3 % |
| read_heavy 1000 | 104 % | 86 % | 293 % | 86 % | 80 % | 43 % | 0.4 % | 0.6 % |
| mixed 100 | 90 % | 94 % | 123 % | 178 % | 62 % | 26 % | 12 % | 0.6 % |
| mixed 500 | 74 % | 70 % | 360 % | 162 % | 52 % | 22 % | 2 % | 0.2 % |

Memory stayed far from the limits (bots 310-450 MiB of 768 MiB; no restart, no OOM).

## What the measurement shows

1. Throughput stops growing between 100 and 500 users (about 150 requests/s reading, about 95 mixed). Beyond that only
   latency grows: the system is saturated, it does not fail (0 % failed requests, a handful of 5xx at 1000 users).
2. Both bot replicas sit at their 1-CPU limit and take equal shares: the two replicas are used, and the control replica is
   idle, so admin traffic and consumer traffic are isolated as designed.
3. The upstream side works harder than the bots: PostgREST uses 3 or more cores for the same traffic. Each consumer request
   fans out into several upstream calls (identity lookup on every request plus 5 to 8 reads per catalog or attempt request,
   see `PERFORMANCE_AUDIT.md`), so about 150 client requests/s are roughly 900 upstream requests/s.
4. Valkey and Caddy are not bottlenecks.

## Decisions (measure -> tune -> measure)

- No tuning was applied from this run. The limiting factors here are the 1-CPU bot limit on a shared workstation and the
  local PostgREST; neither exists in that form in production (Supabase hosts PostgREST and Postgres).
- A third bot replica is NOT justified by this data: the upstream is busier than the bots.
- The optimisation the data points to is fewer upstream calls per request (cache of non-user data such as specialty
  capabilities and plans; one RPC per screen instead of several reads). The identity cache is the largest single saving and
  is an owner security decision (it delays the effect of a block), listed in `PRODUCT_DECISIONS_PENDING.md`.
- Next measurement: the same scenarios against a disposable hosted Supabase project and the real server, with the full
  stages of `PERFORMANCE_TEST_PLAN.md`, before changing limits, replicas or code.
