# Deployment plan (updated 2026-10-09 after the read-only server inventory)

Status: CUTOVER DONE on 2026-10-09 (window 10:34 to 10:36 UTC, about 1 min 42 s without backend). Sections 1 to 5 are the
plan as prepared; section 7 records what was executed.

## 1. SERVER_CURRENT_STATE (inventory 2026-10-09, read-only, as `opsadmin`)

| Item | Value |
|---|---|
| Host | `oposibots-prod-01`, `49.12.232.217`, Ubuntu 26.04.1 LTS, kernel 7.0; "system restart required" pending, 17 package updates pending |
| CPU / RAM / disk | 4 vCPU (AMD EPYC-Genoa), 7.7 GiB RAM (6.4 GiB available), 2 GiB swap unused, 150 GB disk with 134 GB free |
| Load | idle (99 % idle); the backend logged 4 requests in 30 hours, all from this inventory |
| Docker | 29.8.0, Compose 5.5.1, overlayfs, json-file logging; `opsadmin` is in the `docker` group; `sudo` asks for a password |
| Backend today | ONE container `oposicontrol-ktor` (`oposicontrol-ktor:v1` = commit `b899dc9`, 2026-09-23, before Slice 0), 466 MiB, no CPU or memory limit, published on `127.0.0.1:8080`, compose in `/opt/oposicontrol-deploy` (root-owned) |
| Rollback images present | `oposicontrol-ktor:v1` (= `b899dc92…`) and `oposicontrol-ktor:rollback-0eeae46` |
| Edge | Caddy 2.6.2 as a SYSTEM SERVICE (not a container), ports 80/443, admin API on `127.0.0.1:2019`, `/etc/caddy/Caddyfile` (root-owned): `/metrics` 404 + `reverse_proxy 127.0.0.1:8080`; automatic Let's Encrypt certificate valid until 2026-12-20; no Cloudflare in front |
| Env file | `/etc/oposibots/production/backend.env` (root:docker 640): `SUPABASE_URL`, `SUPABASE_SECRET_KEY`, the two bucket names, `OPOSICONTROL_ALLOW_DEV_USER=false`, `HOST`, `PORT`, `SENTRY_*`, `PROMETHEUS_URL`. No auth runtime variables: the consumer app is NOT served by production today |
| Monitoring | `/opt/oposicontrol-monitoring` (owned by `opsadmin`): Prometheus v3.14.0 on `127.0.0.1:9090`, node-exporter, cAdvisor. No Grafana, no alert rules, no Valkey |
| Source checkout | `/opt/oposicontrol` at `b899dc9` (images were built on the server) |
| Not readable without root | firewall rules, listening sockets with process names, sshd effective config |

Consequences for the plan:
- The edge stays the HOST Caddy (2.6, automatic certificates). The stack runs WITHOUT the Caddy container
  (`ops/deploy/compose.hostcaddy.override.yaml`) and the host Caddy gets `ops/caddy/Caddyfile.hostcaddy` (generated from
  `Caddyfile.production`, validated with the server's own Caddy 2.6.2: "Valid configuration").
- The image is built off the server and loaded there; the checkout in `/opt/oposicontrol` is not touched.
- The consumer plane (two `bot` replicas with Valkey) is NEW on this host; today only the admin plane exists.
- The admin client must be the build of this branch: a few callerless legacy admin routes now answer 410.

## 2. RESOURCE_LIMITS for this host (4 vCPU, 7.7 GiB)

| Service | CPU limit | Memory limit | Note |
|---|---|---|---|
| ktor-bot-1, ktor-bot-2 | 1.0 each | 768 MiB each | measured peak 450 MiB per bot under local load |
| ktor-control | 1.0 | 2 GiB | media compression and PDF parsing live here |
| valkey | 0.25 | 96 MiB | rate-limit counters only |
| prometheus (new stack) | 0.5 | 512 MiB | the old Prometheus stays until the monitoring stacks are merged |
| grafana | 0.5 | 256 MiB | |
| existing node-exporter, cAdvisor, old Prometheus | unlimited | about 220 MiB in use | |

Sum of limits about 4.4 GiB plus about 0.7 GiB already in use by the old backend and monitoring during the transition:
inside 7.7 GiB with more than 2 GiB of headroom for the OS, the host Caddy and page cache. CPU is oversubscribed on purpose
(limits are ceilings); revisit after the first measurement on this host.

## 3. Migrations on MAIN

- Applied before today: 8. Applied today as PHASE 1 (pre-deploy, with the current backend running): 14, one at a time, each
  VERIFY block at 0, history recorded with the repo version, protected-content fingerprint unchanged after every step
  (only `table_grants` changes once, by design, at `20260930102100`). MAIN history: 69 versions, last `20261008100500`.
  A fresh backup was taken first (`main-ndiooyyqtaeysnedywer-20261009T091146Z`). Production stayed healthy.
- PHASE 2 = CUTOVER, still pending (3), inside the window:
  `20260930100500_slice1_audit_hardening`, `20260930100800_slice1_event_idempotency_per_user`,
  `20260930100900_slice1_record_event_concurrent_retry`.

## 4. Cutover sequence for this server

Steps 1 to 4 do not touch the running backend or the edge. Step 5 onwards is the window.

1. Load the image `oposicontrol-backend:<sha12>` on the server (`docker save | ssh docker load`).
2. Create the stack directory (owned by `opsadmin`, mode 700) with `compose.production.yaml`, the override, Prometheus and
   Grafana provisioning, and the env files: `backend.common.env` (values copied on the server from the existing env file,
   `APP_ENV=production`, new metrics token), `backend.bot.env` (publishable key, new HMAC secret, `VALKEY_URL`,
   `TRUST_PROXY_HEADERS=true`, `TRUSTED_PROXY_HOSTS=172.28.0.1`), `backend.control.env`, `metrics_token`, `compose.env`.
   Secrets are generated on the server and never printed.
3. `docker compose config -q`; start Valkey, Prometheus, Grafana, then `ktor-control`, `ktor-bot-1`, `ktor-bot-2` on
   `127.0.0.1:18083/18081/18082`. The old backend keeps serving on `127.0.0.1:8080`.
4. Health and smoke against the loopback ports from the server (live, ready, 401 on protected routes, admin route on
   control, no admin route on a bot).
5. WINDOW: apply the 3 cutover migrations (VERIFY + fingerprint each).
6. Switch the edge: `caddy validate`, then `caddy reload --config ~/Caddyfile.hostcaddy --adapter caddyfile` (through the
   admin API, no root needed). Public smoke: health, `/metrics` 404, restart route 404, login, catalog, one attempt, admin list.
7. Persist the edge config (needs root, one command): copy `Caddyfile.hostcaddy` over `/etc/caddy/Caddyfile` (keep the old
   one as `Caddyfile.pre-cutover`), so a Caddy restart keeps the new routing.
8. Stop the old container (`docker stop oposicontrol-ktor`, do not remove it); tag the old image `rollback-<date>`.
9. Observability: three Prometheus targets up, Grafana dashboards loaded, one Sentry test event per profile.

## 5. Rollback

- Before step 5: stop the new stack; nothing else changed.
- After step 6 and before migration `100800` is applied: `caddy reload` with the previous Caddyfile; the old backend is
  still running.
- After `100800`: roll forward only (the old image keeps working with the new schema according to the compatibility
  review, but the runbook does not allow restarting it); fix and redeploy the new image.

## 6. Open items

- Root-only inventory (firewall, sshd) and step 7 need a root shell.
- Pending OS restart and package updates: schedule separately, not during the cutover.
- Cloudflare: later, `CLOUDFLARE_RUNBOOK.md` (then the origin certificate and the trusted-proxy block of
  `Caddyfile.production` apply, which needs Caddy 2.8 or newer on the host or the Caddy container).
- Merge the old monitoring stack into the new one (node-exporter and cAdvisor targets, single Prometheus).
- Sentry real event and a load measurement on this host.


## 7. Cutover record (2026-10-09)

- Image `oposicontrol-backend:e41d4bee865b`, compose project `oposibots`, run directory
  `/opt/oposicontrol-monitoring/oposibots-next/ops/deploy`, env files in `/home/opsadmin/oposibots-stack/env`.
- 10:34:03 UTC old container `oposicontrol-ktor` stopped (kept, not removed; image also tagged `rollback-20261009`).
- 10:34 to 10:35 UTC the 3 cutover migrations applied on MAIN (VERIFY 0, fingerprint unchanged, history 72).
- 10:35:45 UTC host Caddy reloaded through the admin API with `Caddyfile.hostcaddy` (bots `127.0.0.1:18081/18082`,
  control `127.0.0.1:18083`).
- Public smoke on `https://api.oposibots.es`: live/ready/health 200, `/metrics` 404, restart route 404, public catalog 200,
  protected app and admin routes 401.
- After the switch: 6 containers healthy with 0 restarts, 0 ERROR lines and 0 responses 5xx, three Prometheus targets up,
  no alert firing, Grafana healthy, Valkey answering, requests spread over both bots, about 6 GiB of memory available.
- Sentry SDK: one INFO event sent from a one-off container of the production image through the application own
  `initializeSentry` (tag `probe=cutover-sdk-verification`); the SDK reported the envelope as sent.
- Evidence on the server: `/home/opsadmin/cutover-evidence/`.
- STILL OPEN: step 7 (persist `/etc/caddy/Caddyfile`, needs root; until then a restart of the Caddy service would load the
  old routing) and the authenticated smoke (login, one attempt, admin list) with a real authorised account.

## 8. After the cutover (2026-10-09)

- Step 7 done by the owner: `/etc/caddy/Caddyfile` is the new file (old one kept as `Caddyfile.pre-cutover`).
- The production Prometheus also scrapes the host node-exporter and cAdvisor (`host-exporters.yml`, mounted by the host
  override): the system status panel needs `node_*` metrics for CPU and RAM.
- The closed content branch (`preview/todo`) was merged and redeployed the same day with a health-gated rolling script
  (`~/redeploy-rolling.sh <image>` on the server: bot-1, bot-2, control, automatic return to the previous image). The script
  also sets `SENTRY_RELEASE` to the image tag. `main` of both repositories is the single baseline.
- Authenticated admin use by the owner: login and list screens answered 200.
- Sentry SDK: one INFO event per profile from one-off containers of the deployed image through the application own
  initialisation: environment `production`, tag `profile` = `bot` / `control`, envelope reported as sent. Reception in the
  Sentry project was not checked from here (no access to the Sentry web or API).
- Observed: the readiness probe to Supabase times out now and then on both bots at once; see DEBT-085 (`ReadinessGrace`).
- MAIN: 72 versions in the history, RLS enabled on the 93 public tables, protected-content fingerprint unchanged.
- Stale local worktrees (do not use as a base; kept for recovery): every `OposiControl-*` worktree other than a fresh
  checkout of `origin/main`, in particular `OposiControl-MainRelease` (old `main`), `OposiControl-Recursos` (`preview/todo`,
  merged) and `OposiControl` (`integration/oposicontrol-pre-infra`, merged).

## 9. Incident 2026-10-09/10: Supabase egress quota exhausted by the readiness probe

- Cause: `/health/ready` did `GET /rest/v1/` (the whole OpenAPI description of the schema, about 0.5 MB) and the host Caddy
  probed it every 10 s on each bot and every 15 s on control. Measured on the server: each backend container received 1 to
  1.5 GB in about 7 hours with no user traffic. The free plan includes 5 GB per cycle; 5.82 GB were used, 5.4 GB on 2026-10-09.
- Effect: Supabase stopped answering the backend from about 21:45 UTC on 2026-10-09 (100 % of readiness probes failing),
  with one window of about 45 minutes on 2026-10-10 (09:05 to 09:50 UTC). Nobody was alerted: no alert receiver is configured.
- Emergency action (2026-10-10 10:16 UTC): host Caddy health checks moved to `/health/live` (no Supabase call).
- Fix: the probe is a body-less `HEAD /rest/v1/` and each replica reuses the answer for 30 s (at most one request per replica
  per 30 s whatever the number of callers); the grace for an isolated timeout is 90 s.
- Pending: confirm in production that `HEAD /rest/v1/` answers 2xx (Supabase was unreachable when this was written) before
  moving Caddy back to `/health/ready`; configure an alert receiver; decide the Supabase plan.
- Follow-up the same day: every Supabase call asks for gzip (`CompressedUpstreamHttpClient`; measured 2.4x to 5.3x fewer bytes),
  received bytes are exported as `upstream_response_wire_bytes_total` with two alert rules, the Supabase disk metrics read is
  cached for 10 minutes, and backend images are built only with `scripts/build-backend-image.sh` (three images built with
  a bare `docker build` on 2026-10-09/10 carried the jar of the first cutover: the Dockerfile copies `installDist` output).
