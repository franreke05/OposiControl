# Staging on the production host (oposibots-prod-01): runbook

Status: **PREPARED, NOT EXECUTED.** Nothing in this document has touched the production host, its Caddy, Cloudflare, MAIN or any Supabase project. Every phase below needs the owner's GO; phase 6 (host Caddy) needs its **own, separate** GO.

Scope: the V1 candidate validated on `origin/develop` `d69db8be` (Bot server `ce8cfd1` + Control panel), deployed as a second, small, isolated stack on the production server, with its own Supabase project. Separation rules come from `STAGING_PLAN.md` §2 (Control's plan) and are not repeated here.

Artifacts (branch `feature/v1-staging-prodhost`):

| File | Role |
|---|---|
| `ops/deploy/compose.staging.yaml` | standalone compose project `oposibots-staging` (1 bot + 1 control + Valkey + minimal Prometheus) |
| `ops/deploy/staging/{compose,backend.common,backend.bot,backend.control}.env.example` | variable NAMES only |
| `ops/deploy/staging/stagingctl.sh` (+ `.test.sh`) | the only way to run compose for staging: pins `-p oposibots-staging`, `-f`, env file; refuses production |
| `ops/deploy/staging/staging-preflight-readonly.sh` | capacity gate, read-only, GO/ABORT with thresholds |
| `ops/deploy/staging/staging-secrets-check.sh` (+ `.test.sh`) | fails on the MAIN ref, MAIN JWTs, sandbox/dev variables, wrong Supabase URL, or a secret reused from production |
| `ops/deploy/staging/db/{migrations,seed}.order.txt`, `apply-staging-db.sh` | ordered schema migrations and synthetic seed for the staging project; guarded apply |
| `ops/caddy/staging/Caddyfile.staging-hostcaddy` (+ `gen-staging-hostcaddy.mjs`, `validate-local.sh`) | the separate staging site file for the host Caddy, generated from the production host Caddyfile |
| `ops/monitoring/prometheus/prometheus.staging.yml` | `env=staging`, no Alertmanager |
| `scripts/verify-ops-config.sh` (+ test) | 10 new static checks for all of the above, each with a negative mutation |

> **NEVER run `docker system prune`, `docker volume prune`, `docker image prune` or `docker network prune` on this host.** They act on PRODUCTION images, volumes and networks as well. Staging is removed only by explicit project and name (`stagingctl.sh down`, `docker image rm oposicontrol-backend:<tag>` after checking no container uses it).

> Every compose command goes through `bash ops/deploy/staging/stagingctl.sh <cmd>` (it always passes `-p oposibots-staging` and `-f ops/deploy/compose.staging.yaml`). `ops/deploy/release.sh` is **not** used for staging (its `ship` runs `~/redeploy-rolling.sh`, which redeploys PRODUCTION).

## 1. Decisions the owner must take before GO

| Id | Decision | Recommendation |
|---|---|---|
| **D-STG-1** | **How the staging base schema is built.** The repo migrations do NOT build MAIN from zero: `supabase/migrations` has no `CREATE TABLE` for `examenes`, `preguntas`, `especialidades`, `perfiles_usuario`, `compras_usuario`... (they predate the recorded history). "Only repo migrations" is not possible as stated. Options: **(A)** the repo's MAIN-shape subset (`supabase/baseline/main_shape_subset.sql` + `ops/local-exam/sql/00..02` overlays + `main_shape_subset_extra_*.sql`), the shape the local E2E used. It is known to make `20261022100000`, `100100`, `100400` and `102700` stop in their PREFLIGHT (README of CONTROL-E2E) and its stats/ranking tables **differ from MAIN** (section 9, R-1), so staging would NOT rehearse MAIN. **(B)** the data-free MAIN catalog capture of 2026-10-14 (`C:\Users\franr\.oposibots-gate\baseline-20261014\baseline_main.sql`, read-only `pg_catalog` capture, **no rows**, already on this machine; nothing new is read from MAIN) as base, then the 41 repo migrations. Rehearsed locally: base loads in two tolerant passes (residual errors: 12 `ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin`, grants on 2 views absent from the capture); then **40/41 migrations apply**, the 41st (`20261023100100`, Storage) stops only because the bare image has no `storage` schema (the hosted project has it); the synthetic seed applies (9/9). | **B**, with a reviewed loader owned by Bot/DB (the tolerant two-pass load is not acceptable for an unattended run). Until then phase 4 is BLOCKED. Never `pg_dump` MAIN; never copy rows. |
| **D-STG-2** | TLS for the two staging names behind Cloudflare. | **Cloudflare Origin CA** certificate (SANs `staging-api.oposibots.es`, `staging-control.oposibots.es`) in `/etc/caddy/staging/`. No ACME: with proxied records, HTTP-01/TLS-ALPN-01 are unreliable and a DNS-only record would expose the production origin IP. |
| **D-STG-3** | Access to the staging hostnames. | Public through Cloudflare (synthetic data only). Optional: Cloudflare Access on `staging-control`. |
| **D-STG-4** | Fix of R-1 (V1 stats/ranking columns vs MAIN) before staging E2E, or run staging to reproduce it first. | Reproduce on staging, then fix in Bot (new migration), then repeat. |

## 2. Owner inputs (checklist)

Nothing below goes through chat or the repository. Values are typed or pasted **on the host** (or in the operator's password manager).

- [ ] **Supabase project** `oposibots-staging` in the existing **Pro** organization, **same region as MAIN**, smallest compute (**Micro**). Not a branch of MAIN. Cost (approximate, owner to confirm in Billing): Pro compute credits usually cover one Micro project, so a second project likely adds **about 10 USD/month**.
- [ ] **Spend cap ON** for the organization (Billing > Cost control), so staging cannot create overage charges.
- [ ] In the staging project: **JWT signing keys = asymmetric (ECC P-256 / ES256)**, as Ktor verifies ES256 through JWKS (`iss = <staging URL>/auth/v1`). New API keys (`sb_publishable_...`, `sb_secret_...`) or legacy anon/service_role: either works (`SUPABASE_SECRET_KEY` / `SUPABASE_PUBLISHABLE_KEY`).
- [ ] Auth settings (staging project): **leaked-password protection ON**, **email confirmation ON**, Site URL and **redirect allow-list with staging URLs only** (no production URL, no localhost), captcha off unless the app sends it. SMTP: the built-in sender is rate-limited; a custom SMTP is optional.
- [ ] Storage buckets with the **same names and visibility as MAIN** (`news-assets`, `news-images`, `recursos-pregunta`, and whatever `SUPABASE_BUCKET_EXAM_RESOURCES` / `SUPABASE_BUCKET_NEWS_ASSETS` name if different): created empty in the staging dashboard. Policies come from the migrations.
- [ ] GoTrue test accounts per role (superadmin, admin, blocked admin, moderator, user), created in the staging dashboard (Authentication > Add user) with addresses the owner controls; passwords in the password manager only. Their UUIDs go to the role seed of phase 4.
- [ ] Decision **D-STG-1** (and D-STG-2..4).
- [ ] **Cloudflare DNS** (owner): `staging-api` and `staging-control`, type A (and AAAA if production has one), **same origin IP as `api`**, **Proxied**. SSL/TLS mode stays Full (strict). Cloudflare Universal SSL already covers first-level subdomains.
- [ ] **Cloudflare Origin CA** certificate for both names (SSL/TLS > Origin Server), saved directly on the host in phase 6.
- [ ] Who stores staging secrets: the owner, in `/etc/oposibots/staging/` on the host (root, 0600/0400) and in the password manager. **Not** the repository `.env` (it points at MAIN with service_role) and **nothing copied** from `/etc/oposibots/production`.
- [ ] SSH access (`OPOSI_SSH_HOST`, key in the agent) for the operator, at execution time.

## 3. Footprint, capacity assumptions and thresholds

Production limits today (compose.production + hostcaddy override): bot 768m x2, control 2g, valkey 96m, prometheus 512m, grafana 256m, alertmanager 64m, i.e. about 4.4 GiB of hard limits (host Caddy and the historical monitoring stack are extra). Host RAM/CPU are **unknown**: the capacity gate measures them.

Staging (all hard limits; `memswap_limit = mem_limit`, so staging never swaps):

| Service | cpus | memory | pids | other |
|---|---|---|---|---|
| ktor-bot-1 (bot) | 0.6 | 640m (heap 55 %) | 256 | tmpfs /tmp 64m |
| ktor-control | 0.75 | 1g (heap 45 %, ffmpeg off-heap) | 256 | tmpfs /tmp 256m |
| valkey | 0.1 | 64m (`maxmemory 32mb`) | 32 | read-only, no persistence |
| prometheus | 0.2 | 320m | 64 | 2 d / 200 MB TSDB |
| **total** | **1.65** | **2.0 GiB** | | logs 3 x 10 MB per container |

All staging containers: `cpu_shares: 256` (production keeps the default 1024: under contention production gets about 4x the CPU time) and `oom_score_adj: 500` (under host memory pressure the kernel kills staging first).

**One bot replica, not two:** staging validates behaviour, not redundancy. A second replica costs another 640 MiB on a host whose RAM is unknown; what two replicas add (shared Valkey limiter across replicas, rolling restarts) is already exercised in production and locally. Consequence: the `KtorBotPoolDegraded` rule fires permanently in staging (expected, it notifies nobody).

**Measured locally** (dummy env, `APP_ENV=staging`, 2026-10-21): bot 177 MiB, control 199 MiB, prometheus 33 MiB, valkey 7 MiB at idle; all four `healthy` within 60 s.

Capacity gate thresholds (`staging-preflight-readonly.sh`, exit 2 = ABORT):

| Check | ABORT when |
|---|---|
| RAM | `MemAvailable - 2048 MiB < 1536 MiB` (i.e. less than 3.5 GiB available before staging) |
| Swap | more than 512 MiB of swap in use |
| CPU | fewer than 2 cores, or 5-min load / cores > 0.70 |
| Disk | Docker root or `/` with less than 5 GiB free or more than 85 % used |
| Production | any production container restarting, exited, dead, unhealthy or starting |
| Network | `172.30.28.0/24` overlaps a Docker network or a host route; port 18181 or 18183 already listening |

## 4. Phases (ordered; exact commands)

Conventions: `$H` = the SSH target (owner sets `OPOSI_SSH_HOST`); `$TAG` = `d69db8be40a9`; repo root = this worktree; staging code on the host lives in `/opt/oposibots-staging/$TAG` (a `git archive`, not a clone).

### Phase 0 (done locally, repeatable): build and validate

```bash
# Clean tree at d69db8be, Gradle mutex, never on the host:
bash -c 'L=/c/Users/franr/AppData/Local/OposiBotGradleMutex; until mkdir "$L" 2>/dev/null; do sleep 15; done; trap "rmdir $L" EXIT; git -C <worktree at d69db8be> status --porcelain; bash scripts/build-backend-image.sh'
docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' oposicontrol-backend:$TAG   # d69db8be40a96ccf33c2842f117db54c2c0dd6df
docker run --rm --entrypoint sh oposicontrol-backend:$TAG -c 'sha256sum /app/lib/ktor.jar'                        # 37cbb0c914c18581d8d628758f1b8b80200e710aaefb5f31cbbb030c4d11d159
docker run --rm --entrypoint sh oposicontrol-backend:$TAG -c "grep -a -o 'com/oposicontrol/backend/StoreDecisionsKt.class' /app/lib/ktor.jar | head -1"   # newest code present
bash scripts/verify-ops-config.sh && bash scripts/verify-ops-config.test.sh
bash ops/deploy/staging/stagingctl.test.sh && bash ops/deploy/staging/staging-secrets-check.test.sh
bash ops/caddy/staging/validate-local.sh          # caddy 2.11.7 and 2.6.4: production Caddyfile + staging import
```
GO when all pass. Results of 2026-10-21 in section 10.

### Phase 1: capacity gate (read-only, after GO, before writing anything on the host)

Run first because the image transfer writes about 1 GB to the host disk.
```bash
scp ops/deploy/staging/staging-preflight-readonly.sh "$H":/tmp/staging-preflight-readonly.sh
ssh "$H" 'bash /tmp/staging-preflight-readonly.sh' | tee staging-preflight-$(date +%F).txt
```
GO: last line `RESULT: GO`. ABORT (exit 2): stop here, nothing was written. UNKNOWN (exit 1): read the output; the owner decides.

### Phase 2: transfer the image and the staging files

```bash
docker save oposicontrol-backend:$TAG | gzip | ssh "$H" 'gunzip | docker load'
ssh "$H" "docker image inspect -f '{{index .Config.Labels \"org.opencontainers.image.revision\"}}' oposicontrol-backend:$TAG; docker run --rm --entrypoint sh oposicontrol-backend:$TAG -c 'sha256sum /app/lib/ktor.jar'"
git archive --format=tar feature/v1-staging-prodhost ops/deploy/compose.staging.yaml ops/deploy/staging ops/monitoring/prometheus/prometheus.staging.yml ops/monitoring/prometheus/alert_rules.yml ops/caddy/staging \
  | ssh "$H" "sudo mkdir -p /opt/oposibots-staging/$TAG && sudo tar -x -C /opt/oposibots-staging/$TAG"
```
GO: revision = `d69db8be40a96ccf33c2842f117db54c2c0dd6df` and jar sha = `37cbb0c9...d159` on the host. ABORT: any mismatch (`docker image rm oposicontrol-backend:$TAG` on the host after checking `docker ps -a --filter ancestor=oposicontrol-backend:$TAG` is empty).

### Phase 3: staging secrets (owner, on the host)

```bash
sudo install -d -m 0700 -o root -g root /etc/oposibots/staging
# Random values are generated ON the host and written straight to the files (never echoed):
sudo sh -c 'cd /etc/oposibots/staging && umask 077 && V=$(openssl rand -hex 32); printf "BACKEND_IMAGE=oposicontrol-backend:%s\nSTAGING_VALKEY_PASSWORD=%s\n" d69db8be40a9 "$V" > compose.env
  printf "VALKEY_URL=redis://:%s@valkey:6379\nAUTH_RATE_LIMIT_HMAC_SECRET=%s\nTRUSTED_PROXY_HOSTS=172.30.28.1\nOPOSIBOTS_AUTH_ENABLED=true\n" "$V" "$(openssl rand -hex 32)" > backend.bot.env
  printf "ADMIN_RATE_LIMIT_VALKEY_URL=redis://:%s@valkey:6379\nADMIN_RATE_LIMIT_HMAC_SECRET=%s\nOPOSICONTROL_BACKEND_TOKEN=%s\nENABLE_VERBOSE_NETWORK_LOGS=false\n" "$V" "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > backend.control.env
  T=$(openssl rand -hex 32); printf "%s" "$T" > metrics_token; printf "METRICS_SCRAPE_TOKEN=%s\n" "$T" > backend.common.env; chmod 0400 metrics_token'
# The owner then adds, with an editor ON THE HOST (sudoedit), the values copied from the STAGING dashboard only:
#   backend.common.env : SUPABASE_URL, SUPABASE_SECRET_KEY (or SUPABASE_SERVICE_ROLE_KEY), SENTRY_RELEASE=d69db8be40a9
#   backend.bot.env    : SUPABASE_PUBLISHABLE_KEY (or SUPABASE_ANON_KEY), staging redirect URLs (optional)
#   backend.control.env: SUPABASE_BUCKET_NEWS_ASSETS (if not the default)
sudoedit /etc/oposibots/staging/backend.common.env /etc/oposibots/staging/backend.bot.env /etc/oposibots/staging/backend.control.env
sudo chmod 0600 /etc/oposibots/staging/*.env
sudo chown 65534:65534 /etc/oposibots/staging/metrics_token   # readable by the Prometheus container user (nobody)
sudo bash /opt/oposibots-staging/$TAG/ops/deploy/staging/staging-secrets-check.sh --env-dir /etc/oposibots/staging --prod-dir /etc/oposibots/production
```
GO: `ok: staging env files ... no value shared with production`. ABORT: any `FAIL: R1..R6` (fix the named variable; values are never printed).

### Phase 4: database of the staging project (from the operator's machine, not the host) - BLOCKED on D-STG-1

```bash
# connection string of the STAGING project (Connect > Session pooler), one line, outside the repo, 0600:
#   %USERPROFILE%\.oposibots-staging\db_url
# 4a. base schema per D-STG-1 (reviewed loader; not provided here)
# 4b. repo migrations (41, version order, CAND-04 respected):
bash ops/deploy/staging/db/apply-staging-db.sh --ref <staging ref> --db-url-file ~/.oposibots-staging/db_url --list migrations --dry-run
bash ops/deploy/staging/db/apply-staging-db.sh --ref <staging ref> --db-url-file ~/.oposibots-staging/db_url --list migrations
# 4c. VERIFY blocks of the security set (each must return 0): 20261023100000..100400, 20261021100000 (copy the commented VERIFY
#     block of each file into the SQL editor of the STAGING project).
# 4d. synthetic seed (9 files), then the role rows for the GoTrue accounts of the owner (by UUID, SQL editor of the STAGING project):
bash ops/deploy/staging/db/apply-staging-db.sh --ref <staging ref> --db-url-file ~/.oposibots-staging/db_url --list seed
```
GO: `DONE migrations`, every VERIFY = 0, `DONE seed`. ABORT: first `FAIL` (the script stops; nothing after it is applied). Recovery: Supabase Pro daily backup restore of the STAGING project, or delete and recreate the staging project (it holds nothing of value). `session_replication_role = replica` (used by the seed) must be allowed for `postgres` on the hosted project: if it is refused, the seed stops at `90_fixtures.sql` and needs an adaptation (open item).

### Phase 5: start the staging stack (host)

```bash
cd /opt/oposibots-staging/$TAG
bash ops/deploy/staging/stagingctl.sh config
bash ops/deploy/staging/stagingctl.sh up            # disk guard (>= 5 GiB free) then up -d --no-build
bash ops/deploy/staging/stagingctl.sh ps            # 4 x healthy within ~90 s
curl -fsS http://127.0.0.1:18181/health/live; curl -fsS http://127.0.0.1:18183/health/live
curl -s http://127.0.0.1:18181/health/ready         # 200 {"checks":{"supabase":"UP"}} (staging project)
bash ops/deploy/staging/stagingctl.sh isolation-check
bash /tmp/staging-preflight-readonly.sh | tail -3   # re-check RAM/CPU with staging running (expect GO; ports now in use by staging)
docker stats --no-stream                            # production containers unchanged
```
GO: 4 healthy, ready 200, `ok: isolation`, production containers healthy. ABORT: `bash ops/deploy/staging/stagingctl.sh down` (production untouched).

### Phase 6: host Caddy import (the ONLY step that touches a production component; separate GO)

Pre-checks (read-only):
```bash
systemctl cat caddy | grep -E 'ExecStart|ExecReload'     # confirms the config path (expected /etc/caddy/Caddyfile) and that reload = `caddy reload`
caddy version                                           # 2.6.x (validated locally with 2.6.4 and 2.11.7)
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile   # the CURRENT production config validates before we touch it
curl -s -o /dev/null -w '%{http_code}\n' https://api.oposibots.es/health/live   # 200 baseline
getent hosts staging-api.oposibots.es staging-control.oposibots.es              # Cloudflare IPs (records exist, proxied)
grep -n 'staging' /etc/caddy/Caddyfile || echo "no staging line yet"
```
Install (atomic order: files first, import line last):
```bash
TS=$(date +%Y%m%d%H%M%S)
sudo cp -a /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-staging-$TS                       # backup FIRST
sudo install -d -m 0750 -o root -g caddy /etc/caddy/staging
sudo install -m 0644 -o root -g caddy /opt/oposibots-staging/$TAG/ops/caddy/staging/Caddyfile.staging-hostcaddy /etc/caddy/staging/staging.caddy
sudoedit /etc/caddy/staging/origin.pem && sudoedit /etc/caddy/staging/origin.key          # paste the Origin CA pair (owner)
sudo chown root:caddy /etc/caddy/staging/origin.* && sudo chmod 0640 /etc/caddy/staging/origin.*
printf '\nimport /etc/caddy/staging/staging.caddy\n' | sudo tee -a /etc/caddy/Caddyfile >/dev/null
sudo -u caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile           # as the service user (reads the key)
sudo systemctl reload caddy                                                               # graceful; an invalid config is rejected and the old one keeps serving
```
Post-checks:
```bash
systemctl is-active caddy; journalctl -u caddy --since "-2 min" --no-pager | grep -iE 'error|failed' || echo "no errors"
curl -s -o /dev/null -w 'prod %{http_code}\n' https://api.oposibots.es/health/live          # still 200
curl -s -o /dev/null -w 'stg-api %{http_code}\n' https://staging-api.oposibots.es/health/live
curl -s -o /dev/null -w 'stg-ctl %{http_code}\n' https://staging-control.oposibots.es/health/live
```
GO: production 200 before and after; staging 200. **Rollback (one command):**
```bash
sudo cp -a /etc/caddy/Caddyfile.bak-staging-$TS /etc/caddy/Caddyfile && sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy
```
Why staging cannot take production down here: separate site blocks in a separate file (production blocks are byte-identical), separate upstreams (18181/18183, never 18081-18083), active health on `/health/live` only, manual certificates (no ACME traffic or rate limits shared with `api`), and `caddy reload` keeps the running config when the new one fails to load. A dead staging stack only makes the staging sites answer 502/503.

### Phase 7: smoke through the edge

```bash
for u in https://staging-api.oposibots.es https://staging-control.oposibots.es; do
  for p in /health/live /health/ready /metrics /admin/backend/restart /ruta-inexistente; do printf '%s%s -> ' "$u" "$p"; curl -s -o /dev/null -m 15 -w '%{http_code}\n' "$u$p"; done; done
# expected: live 200, ready 200, /metrics 404, /admin/backend/restart 404, unknown 404
curl -s -o /dev/null -w '%{http_code}\n' https://staging-control.oposibots.es/v1/auth/login     # 404 (bot routes are not on the control host)
curl -s -o /dev/null -w '%{http_code}\n' https://staging-api.oposibots.es/api/v1/admin/usuarios # 401 without token
curl -sI https://staging-api.oposibots.es/health/live | grep -i -E 'cf-ray|x-robots-tag'          # proxied + noindex
```
Then: login/refresh/logout with a GoTrue test account (real email confirmation, PKCE `required`), leaked-password rejection on signup, Control panel login against `staging-control` (admin and blocked admin), one admin mutation per minute limit sanity, Valkey fail-closed (`stagingctl.sh` cannot stop one service: use `docker stop oposibots-staging-valkey-1`, expect 503 on `/v1/auth/login`, then `docker start oposibots-staging-valkey-1`).

### Phase 8: E2E, IDOR and journeys against staging (prerequisites, not done)

- `ControlJourneysE2ETest` and `ControlPanelUiE2ETest` need the container-name guard replaced by a staging host allow-list (`STAGING_PLAN.md` §4; Control's change).
- Bot IDOR sweep (`ops/local-exam/e2-v1-night01-idor.mjs`) and journeys target the local stack; they need a staging base URL and GoTrue-issued tokens.
- Exit criteria of `STAGING_PLAN.md` §4.5 apply unchanged.

## 5. Full rollback

```bash
# 1. host Caddy (only if phase 6 ran):
sudo cp -a /etc/caddy/Caddyfile.bak-staging-$TS /etc/caddy/Caddyfile && sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy
sudo rm -r /etc/caddy/staging
# 2. staging stack (containers, network and its Prometheus volume, by project name only):
cd /opt/oposibots-staging/$TAG && bash ops/deploy/staging/stagingctl.sh down --volumes
# 3. image and files (explicit names only; never prune):
docker ps -a --filter ancestor=oposicontrol-backend:$TAG --format '{{.Names}}'   # must print nothing (or only production if it runs this same tag: then keep the image)
docker image rm oposicontrol-backend:$TAG
sudo rm -r /opt/oposibots-staging/$TAG /etc/oposibots/staging
# 4. owner: delete the two DNS records and revoke the Origin CA certificate in Cloudflare; pause or delete the staging Supabase project.
```

## 6. Explicitly NOT done / out of scope

- MAIN: no read, no write, no migration, no `pg_dump`, no data copy. The production database is not touched by any phase.
- Production compose project (`oposibots-production`), its env files, Valkey, Prometheus, Grafana and Alertmanager: not touched. Production Prometheus does not scrape staging and cannot reach its network.
- Payments: no store credentials, no real or sandbox purchases. G1 (`PAYMENTS_STORE_LIVE_CREDIT_ENABLED`) and D8 (`PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED`) are `false`, both store platforms disabled, `PAYMENTS_STORE_SANDBOX=reject`, reconciliation job off, all fixed in compose. `PAYMENTS_SANDBOX_SECRET` is never set (the process exits 78 outside local/test). Store sandbox testing needs its own GO and a compose change.
- GitHub Actions: stays disabled. No push of this branch without GO.
- Grafana and Alertmanager for staging: not deployed.

## 7. Supabase (Pro organization)

- Pro projects **do not pause** when idle (the 7-day pause of the Free plan is **not applicable on Pro**) and get **daily backups**. PITR is a paid add-on and is **not needed** for staging.
- Database size and egress are **organization quotas shared with MAIN**, billed on overage: staging must stay synthetic and small (the seed is a few hundred rows). The spend cap (owner checklist) prevents surprise charges.
- Reactivation, if the project is ever paused (manually or after a plan change): Dashboard > project > **Restore project**; wait until the status is Healthy; `bash ops/deploy/staging/stagingctl.sh ps` and `curl -s http://127.0.0.1:18181/health/ready` (200 again within 30 s; the readiness cache is 30 s and the Ktor containers need no restart).
- Never a branch of MAIN (`STAGING_PLAN.md` §2) and never data from MAIN.

### Egress estimate (staging idle)

- Docker healthchecks and host Caddy active health use `/health/live`: **no Supabase call**.
- `/health/ready` and the `upstream_supabase_up` gauge share one probe: a **body-less HEAD** to `<staging>/rest/v1/`, cached **30 s** per replica (`ReadinessGrace`, `Observability.kt`). Staging Prometheus scrapes every **60 s**, so at most 1 HEAD per replica per minute: 2 x 1,440 = **2,880 HEAD/day**, about 1 KB of headers each, **~3 MB/day**.
- Bot periodic jobs (`CHALLENGE_JOB_SECONDS` default 900 s): 4 RPC per run, 96 runs/day, small JSON: **~0.4 MB/day**. Store reconciliation: off (`PAYMENTS_STORE_JOB_SECONDS=0`).
- JWKS: one fetch at start and on key rotation.
- **Total idle: ~3.5-5 MB/day, ~0.1-0.15 GB/month.** Tests add their own traffic; an E2E pass is small (synthetic rows).

## 8. Observability

- Staging Prometheus: every series `env="staging"` (external label and target labels), scrape 60 s, retention 2 d / 200 MB, same `alert_rules.yml` as production.
- **No Alertmanager and no `alerting:` block**: alerts are evaluated (inside the stack, `/alerts`) and can never notify the on-call. Verified locally: `/api/v1/alertmanagers` = `[]`.
- The Control panel system status of staging reads this Prometheus. Host CPU/RAM panels show "not available" (node-exporter lives on a production network that staging must not join).
- Sentry: `SENTRY_ENVIRONMENT=staging` fixed; DSN empty unless a staging DSN exists.

## 9. Findings and open risks

- **R-1 (P1 candidate, owner Bot): V1 stats/ranking SQL writes columns MAIN does not have.** `20261022101600_v1_stats` (`private.app_apply_first_answer_stats`, `app_job_dificultad`) and `20261022101700_v1_ranking` (`app_job_ranking`) use `estadisticas_usuario_etiqueta.respondidas/correctas/falladas` and `lotes_actualizacion_ranking.tipo/filas/detalle/error`; MAIN (capture 2026-10-14) has `preguntas_respondidas/respuestas_correctas/respuestas_falladas` and no `tipo/filas/detalle/error`. The local E2E passed because `supabase/baseline/main_shape_subset_extra_engagement.sql` defines the local shape, not MAIN's. Runtime proof on the MAIN-shaped rehearsal: `select public.app_job_dificultad(true)` and `select public.app_job_ranking(true)` both fail with `column l.tipo does not exist`. On MAIN, the first answer to a tagged question would raise inside the answer RPC, and the 15-minute jobs would fail every run. `ligas_nivel.creado_en` also differs.
- **R-2: `scripts/smoke-backend-image.sh` is stale against `d69db8be`:** the `combined` and `control` profiles with `APP_ENV=staging` now exit at start (`ADMIN_RATE_LIMIT_MODE es obligatorio fuera de desarrollo`, DEBT-151) because the script does not pass the admin limiter variables. Not changed here (outside ops/). The staging compose itself starts healthy with `APP_ENV=staging` (section 10).
- **R-3: client IP behind Cloudflare.** Like production's host Caddyfile, the staging site forwards `{remote_host}` (a Cloudflare edge IP), so per-IP limits see Cloudflare IPs. Same behaviour as production; not changed here.
- **R-4: hosted-Supabase specifics not provable locally:** `session_replication_role` for `postgres`, writes to `auth.users` by the seed, Storage-owned policies of `20261023100100` (needs the owner of `storage.objects`; RB-16 PREFLIGHT).
- **R-5: host facts unknown** (RAM, CPU, disk, Docker version, the exact Caddyfile path, whether `ufw`/`DOCKER-USER` rules exist). The capacity gate and the phase 6 pre-checks read them before anything is written.
- **R-6: shared host.** Staging cannot reach production containers (Docker isolates bridges; checked by `isolation-check`) but shares kernel, disk and network bandwidth. Limits, `cpu_shares`, `oom_score_adj`, log caps and the disk guard bound it; a kernel-level incident affects both.

## 10. Local validation evidence (2026-10-21)

| Check | Result |
|---|---|
| `bash scripts/build-backend-image.sh` at `d69db8be` (clean tree, Gradle mutex) | `oposicontrol-backend:d69db8be40a9`, label = `d69db8be40a96ccf33c2842f117db54c2c0dd6df`, jar sha256 local = image = `37cbb0c9...d159`, `StoreDecisionsKt`/`StoreAppStoreApiKt` present |
| `scripts/smoke-backend-image.sh` | FAIL in `combined` (R-2, stale script), not a staging defect |
| `stagingctl.sh config` with dummy env | valid; rendered: bot/control `APP_ENV=staging`, PKCE `required`, G1/D8 `false`, no `PAYMENTS_SANDBOX_SECRET`, no admin token in bot; a host env file setting `APP_ENV=local` / G1 `true` is overridden |
| `stagingctl.sh up` locally (dummy env, placeholder Supabase) | 4/4 healthy; `/health/live` 200 on 18181/18183; `/metrics` 401 without token; Prometheus targets `up`, `env=staging`; Alertmanagers `[]` |
| `stagingctl.sh isolation-check` locally | staging only on its network, loopback ports, 8 foreign container IPs unreachable (positive control: staging reaches its own Valkey, curl rc 52) |
| Edge simulation (caddy 2.6.4 with the staging site, upstreams to the local stack) | api: live 200, /metrics 404, /admin/backend 404, unknown 404, admin 401, app 401; control host: bot routes 404, admin 401, live 200 |
| `ops/caddy/staging/validate-local.sh` | caddy 2.11.7 and 2.6.4: production host Caddyfile + import validates; adapt shows `api`, `staging-api`, `staging-control`; a missing import is rejected |
| Schema rehearsal (D-STG-1 option B) | 40/41 migrations, seed 9/9; R-1 found |
| `verify-ops-config.sh` / `.test.sh`, `stagingctl.test.sh`, `staging-secrets-check.test.sh` | all OK (section 11) |

## 11. Control acceptance criteria

| # | Criterion | Where it is satisfied |
|---|---|---|
| 1 | Resources: cpus + mem_limit + pids_limit on every service, log rotation, disk guard, no build on the host | `ops/deploy/compose.staging.yaml` (`x-ktor`: `pids_limit`, `cpu_shares`, `oom_score_adj`, `logging`; each service: `cpus`, `mem_limit`, `memswap_limit`; valkey/prometheus explicit); `x-logging` 3 x 10 MB; Prometheus 2 d / 200 MB; disk guard in `stagingctl.sh up` (`disk_guard`, 5 GiB) and in the capacity gate; image built locally (phase 0) and loaded (phase 2), `up --no-build`. Check: `verify-ops-config.sh` "cada servicio con cpus, mem_limit, memswap_limit, pids_limit, cpu_shares 256, oom_score_adj > 0 y logs rotados" |
| 2 | Network isolation, loopback-only ports, no shared Valkey/Prometheus/Alertmanager, no route to production | compose: single network `staging` (172.30.28.0/24), no `external:`, ports only `127.0.0.1:18181/18183`, own `valkey` and `prometheus`, no Alertmanager. Runtime proof: `stagingctl.sh isolation-check` (phase 5). Static: `verify-ops-config.sh` "solo los dos Ktor publican y en 127.0.0.1:1818x; una red propia, sin external ni subred de produccion" |
| 3 | Own env files; automated MAIN-ref / MAIN-key check with negative control; synthetic data | `/etc/oposibots/staging/*` (examples in `ops/deploy/staging/`); `staging-secrets-check.sh` (R1-R6) + `staging-secrets-check.test.sh` (7 seeded violations); run in phase 3 and by `verify-ops-config.sh` (`--repo`); `apply-staging-db.sh` refuses a MAIN URL; seed list `db/seed.order.txt` (synthetic, `@exam.local`, no passwords) |
| 4 | Explicit project, `-p oposibots-staging` + `-f` everywhere; release.sh guard; no prune | `stagingctl.sh` (pins `-p`/`-f`/env, refuses `COMPOSE_PROJECT_NAME`, `COMPOSE_FILE`, production env paths, a non-staging `name:`) + `stagingctl.test.sh` (6 refusals); `release.sh` is not used for staging (header of this runbook); prune ban in bold above; `verify-ops-config.sh` fails if this runbook contains a raw `docker compose` line |
| 5 | Caddy: separate imported file, backup, validate, reload; rollback; separate GO | `ops/caddy/staging/Caddyfile.staging-hostcaddy` (generated, `--check` in `verify-ops-config.sh`); phase 6: backup -> install -> import line -> `caddy validate` -> `systemctl reload caddy`; one-command rollback; separate GO stated at the top and in phase 6; locally validated in 2.6.4 and 2.11.7 |
| 6 | Observability: env=staging, alerts never notify the on-call | `ops/monitoring/prometheus/prometheus.staging.yml` (`env: staging`, no `alerting:`); no Alertmanager service; `SENTRY_ENVIRONMENT=staging`; check "env=staging, sin alerting/Alertmanager" |
| 7 | Supabase plan facts and reactivation | Section 7 (updated for Pro: no idle pause, daily backups, PITR not needed, shared org quotas, spend cap, restore procedure) |
| 8 | Payments sandbox only, G1 and D8 off | `compose.staging.yaml` ktor-bot-1 `environment:` (both platforms, G1, D8 `false`, sandbox `reject`, job 0, refunds off); no `PAYMENTS_SANDBOX_SECRET`; `staging-secrets-check.sh` R4; check "pagos de tienda apagados (G1, D8, plataformas) fijados en compose" |
