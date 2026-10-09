# Deployment plan (prepared 2026-10-09, nothing executed)

Status: PREPARED, NOT EXECUTABLE YET. The host inventory is incomplete: SSH to the server is filtered from the
workstation that prepared this plan, so everything inside the host is UNKNOWN until `ops/deploy/server-inventory-readonly.sh`
is run there. Every number marked PLACEHOLDER must be replaced with a measured value before step 3.

## 1. SERVER_CURRENT_STATE

### Observed from outside, without credentials (2026-10-09)

| Fact | Value |
|---|---|
| Public host | `api.oposibots.es` -> `49.12.232.217` (A record points straight at the server) |
| Cloudflare | NOT in front today: response header `Server: Caddy`, no `cf-ray` |
| TLS | Let's Encrypt, valid until 2026-12-20 (Caddy automatic certificates) |
| Open ports seen | 443 open; 22 filtered from this network (also 2222, 2200, 22022) |
| Topology | ONE backend serving both planes: `/api/v1/app/*` and `/api/v1/admin/*` answer 401 on the same host |
| `/health/live`, `/health/ready` | 200 |
| `/health` | `{"status":"ok","build":"20260512-no-legacy-columns","restartEnabled":"true"}` |
| `/metrics` | 404 at the edge (blocked, as intended) |
| `/admin/backend/restart` | route is mounted (405 to GET) and `restartEnabled` is true in production |
| `/v1/especialidades` | 200 (public read) |

Two things to fix in the cutover: the restart route is mounted in production (the new `bot` and `control` profiles do not
mount it), and there is a single combined process (no isolation between consumer and admin traffic).

### UNKNOWN until the inventory script runs

OS and kernel, CPU cores, RAM, disk, Docker version, running containers and images, deployed git SHA and image tag,
rollback image, compose and Caddyfile in use, env file locations and variable names, firewall rules, SSH port and
allow-list, existing Valkey/Prometheus/Grafana, volumes, cron jobs.

How to obtain it (either): allow SSH for the operator workstation, or run on the server
`bash server-inventory-readonly.sh > inventory.txt 2>&1` and hand over `inventory.txt` (it prints no secret values).

## 2. Migrations: 17 pending on MAIN, in two phases

MAIN today: 55 versions, last applied `20261008100400`. The order below was rehearsed end to end on a restore of the MAIN
backup (2026-10-09): phase 0 = what MAIN has today, then phase 1, then phase 2, every VERIFY block at 0, data unchanged,
no public table without RLS.

### Phase 1: PRE-DEPLOY (14) - safe while the current backend keeps running

Additive or narrowing, no data written, the current backend calls nothing they change (it signs every database call with
the service key; old function signatures are kept).

| Order | Version | What |
|---|---|---|
| 1 | `20260930101500_slice1_session_revocation_monotonic` | function body, same signature |
| 2 | `20260930101600_admin_sync_roles_actor_guard` | new 3-argument overload; the 2-argument one stays |
| 3 | `20260930101730_slice1_tickets_grant_audit_unique` | partial unique index on audit rows |
| 4 | `20260930101850_admin_sync_roles_audited` | new function |
| 5 | `20260930101900_admin_sync_roles_audited_expected` | new overload |
| 6 | `20260930102000_slice1_report_action_once` | new function + one column with a constant default |
| 7 | `20260930102100_slice1_client_ddl_privileges_revoke` | revokes TRUNCATE/TRIGGER/REFERENCES from client roles |
| 8 | `20260930102200_slice1_roles_usuario_client_write_revoke` | clients can no longer write roles |
| 9 | `20260930102300_slice1_report_insert_bounds` | report insert policy + two length checks |
| 10 | `20261008100000_slice3_attempt_runtime_schema` | attempt columns + one-open-attempt unique index |
| 11 | `20261008100100_slice3_attempt_command_rpcs` | new attempt functions |
| 12 | `20261008100200_slice3_attempt_hardening` | creation marker, indexes |
| 13 | `20261008100300_slice2_my_exams_read_rpc` | read function |
| 14 | `20261008100500_slice7_wallet_ledger_keyset_index` | one index |

Known effect on the CURRENT backend after phase 1: its legacy `POST /api/v1/app/examenes/{id}/iniciar` answers 409 instead
of opening a second attempt of the same exam (unique index). No client calls that route.

Precheck before phase 1 (read only): `select usuario_id, examen_id, count(*) from public.intentos_examen where
estado = 'en_progreso' and examen_id is not null group by 1, 2 having count(*) > 1` must return no row (today: 0 attempts).

### Phase 2: CUTOVER (3) - inside the maintenance window, old backend stopped

| Order | Version | Why it waits |
|---|---|---|
| 15 | `20260930100500_slice1_audit_hardening` | redefines the signup trigger function (`handle_new_auth_user`) |
| 16 | `20260930100800_slice1_event_idempotency_per_user` | replaces the global idempotency index: no way back to the old image afterwards |
| 17 | `20260930100900_slice1_record_event_concurrent_retry` | depends on 16 |

Window rule from the runbook: the clock starts when the old container stops; before 16 the old image can be restarted;
after 16 only roll forward; maximum 30 minutes, decision at minute 15.

Apply tool for both phases: `apply_one.sh` (one file, its VERIFY block, history recorded with the repo version) followed by
the protected-content fingerprint comparison, exactly as used for the 8 already applied.

## 3. Deployment sequence (each numbered step is one command block in DEPLOY_ASSIST, nothing runs unattended)

0. Read-only preflight: inventory script; confirm free disk, Docker, current image tag = rollback tag.
1. Capture the current deployment: `docker image tag <current> rollback-<date>`; copy of the Caddyfile, compose and env
   file names; new MAIN backup (same method as `BACKUP_EVIDENCE.txt`).
2. Phase 1 migrations on MAIN (can be days before the window).
3. Files on the server: `compose.production.yaml`, `Caddyfile.production`, Prometheus and Grafana provisioning; split the
   existing env file into `backend.common.env`, `backend.bot.env`, `backend.control.env` (names in the `.example` files);
   create `metrics_token`; decide `ORIGIN_CERT_DIR` (see 5).
4. Build or pull the image for the release SHA; `docker compose config -q`.
5. Bring up Valkey, Prometheus, Grafana (no traffic impact).
6. WINDOW: stop the old container; phase 2 migrations; start `ktor-control`, `ktor-bot-1`, `ktor-bot-2`; local health
   (`/health/live`, `/health/ready` on each).
7. Switch Caddy to the new Caddyfile (validate first, then reload); public health.
8. Smoke: login, catalog, one attempt on a free exam, admin list, `/metrics` 404, restart route 404.
9. Observability: three Prometheus targets up, dashboards loaded, one Sentry test event per profile.
10. Cloudflare (separate, later): `CLOUDFLARE_RUNBOOK.md`.

## 4. Decisions the plan needs before step 3

- TLS at the origin: today Caddy obtains Let's Encrypt certificates by itself. `Caddyfile.production` expects an origin
  certificate file (written for Cloudflare Full strict). Until Cloudflare is in front, either keep automatic certificates
  (one-line change in the Caddyfile) or install an origin certificate. Recommended: keep automatic certificates for the
  first cutover and move to the origin certificate with the Cloudflare step.
- Supabase URL must be HTTPS for the `bot` profile outside development (it refuses to start otherwise): true for the hosted
  project.
- Valkey is new on this host unless the inventory shows one.

## 5. RESOURCE_LIMITS

All values in `compose.production.yaml` are PLACEHOLDER until the inventory gives real CPU and RAM:
bot 1 CPU / 768 MiB each, control 2 GiB, about 5.1 GiB for the whole stack. Rule to derive them:
`docs/engineering/SERVER_RESOURCE_PLAN.md`. Local measurement for orientation only: `PERFORMANCE_RESULTS_LOCAL.md`
(bots reached their 1-CPU limit before memory mattered; peak 450 MiB per bot).

## 6. Rollback

- Before migration 16: stop the new containers, restore the previous Caddyfile, start the `rollback-<date>` image.
- After migration 16: roll forward only (fix and redeploy the new image); database rollback blocks exist per migration and
  were rehearsed, but 16 is reversible only while no two users share an idempotency key.
- Phase 1 migrations do not need to be rolled back to return to the old image.
