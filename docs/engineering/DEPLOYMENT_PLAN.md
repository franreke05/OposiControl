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
  also sets `SENTRY_RELEASE` to the image tag. `main` of both repositories was then the single baseline.
- Branch model since 2026-10-15 (OposiControl): only `main` and `develop`. `main` stays at the reference
  `cfa2e14` and is not merged into without a release gate; all Alpha work lands in `develop` through short-lived
  `tmp/*` branches that are deleted after merging. Every former branch head is preserved under the tag
  `archive/2026-10-14/<branch>` (see `docs/engineering/BRANCH_CONSOLIDATION_2026-10-14.md`).
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

## 10. Preparación del backend de consumo y de las solicitudes de acceso (2026-10-14, PREPARADO, NO EJECUTADO)

Rama `feature/oposibot-consumer-backend`. Nada de esta sección se ha hecho en producción: cada paso necesita el GO del propietario. Inventario leído por SSH y metadatos de Supabase en solo lectura, con su autorización expresa del 2026-10-14.

### 10.1 Inventario real de producción (leído por SSH y metadatos de Supabase, 2026-10-14, solo lectura)

| Pieza | Valor |
|---|---|
| Servidor | `oposibots-prod-01`, Ubuntu 26.04.1 LTS, 4 vCPU, 7,7 GB RAM |
| Caddy | servicio del sistema, `caddy 2.6.2`, `/usr/bin/caddy run --environ --config /etc/caddy/Caddyfile`. Se leyó la copia `~/Caddyfile.hostcaddy` (10-oct); el fichero real necesita sudo y no se leyó |
| Rutas en Caddy (copia) | `@bot` = `/v1/auth/*`, `/v1/especialidades`, `/v1/examenes`, `/v1/examenes/*`, `/api/v1/app`, `/api/v1/app/*` → 127.0.0.1:18081 y :18082; `@control` (rutas de administración) → :18083; `/metrics` y rutas dev → 404; resto → 404 |
| bot-1 | `oposibots-ktor-bot-1-1`, `oposicontrol-backend:cfa2e14afc8e`, 127.0.0.1:18081→8080, 1 CPU, 768 MiB, `unless-stopped`, red `oposibots_edge` |
| bot-2 | `oposibots-ktor-bot-2-1`, misma imagen, 127.0.0.1:18082→8080, 1 CPU, 768 MiB |
| control | `oposibots-ktor-control-1`, misma imagen, 127.0.0.1:18083→8080, 1,5 CPU, 2 GiB |
| Valkey | `oposibots-valkey-1`, `valkey/valkey:8.1-alpine`, 0,25 CPU, 96 MiB, solo red interna |
| Prometheus | `oposibots-prometheus-1`, `prom/prometheus:v3.14.0`, 0,5 CPU, 512 MiB |
| Grafana | `oposibots-grafana-1`, `grafana/grafana:11.6.0`, 0,5 CPU, 256 MiB |
| Alertmanager | `oposibots-alertmanager-1`, `prom/alertmanager:v0.27.0` |
| Restos de la pila anterior | `oposicontrol-ktor` (parado), `oposicontrol-prometheus` (127.0.0.1:9090), `oposicontrol-cadvisor`, `oposicontrol-node-exporter` |
| Compose | proyecto `oposibots`, `/opt/oposicontrol-monitoring/oposibots-next/ops/deploy/compose.production.yaml` + `compose.hostcaddy.override.yaml` |
| Entorno | `/home/opsadmin/oposibots-stack/env/`: `backend.common.env`, `backend.bot.env` (solo bots), `backend.control.env` (solo control), `compose.env` |
| Despliegue | `~/redeploy-rolling.sh <imagen>`: bot-1 → sano → bot-2 → sano → control, con vuelta a la imagen anterior si uno no queda sano |
| Supabase MAIN | `ndiooyyqtaeysnedywer` (eu-west-1). Migraciones aplicadas hasta `20261015100000`, incluida `20261013100000` |
| `solicitudes_acceso_admin` | presente; RLS activado sin políticas; permisos solo `postgres` y `service_role`; índices: PK, `UNIQUE(usuario_id)`, revisión, `resuelto_por`; sin índice en `email` |

Variables de auth en los bots (nombres; ningún valor secreto se imprimió):

| Variable | Estado | Efecto hoy |
|---|---|---|
| `AUTH_CAPTCHA_REQUIRED` | NOT_SET | Con `APP_ENV=production` el backend exige captcha. La app no lo envía: registro, reenvío y recuperación deberían responder 400 `INVALID_CAPTCHA` |
| `OPOSIBOTS_EMAIL_CONFIRM_REDIRECT_URL` | NOT_SET | La variable solo existe en la rama nueva |
| `OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL` | NOT_SET | `/recover` no pasa `redirect_to` |

Producción (`cfa2e14`) no tiene la guarda de solicitudes de acceso ni la puerta de sesión: el salto de autoconfirmación está desplegado. Que se pueda explotar depende de si el captcha está activo dentro de Supabase (UNKNOWN).

UNKNOWN (no visible con las herramientas autorizadas): plantillas de correo, Site URL y redirecciones permitidas, captcha de Supabase, SMTP y límites de envío, duración del JWT y política de contraseñas de Supabase, contenido real de `/etc/caddy/Caddyfile`.

### 10.2 Cambio de entorno preparado

Fichero real: `/home/opsadmin/oposibots-stack/env/backend.bot.env` (lo leen solo los dos bots).

```bash
cd ~/oposibots-stack/env
```

```bash
cp backend.bot.env "backend.bot.env.bak-$(date +%Y%m%d%H%M)"
```

```bash
grep -q '^AUTH_CAPTCHA_REQUIRED=' backend.bot.env || echo 'AUTH_CAPTCHA_REQUIRED=false' >> backend.bot.env
```

- `OPOSIBOTS_EMAIL_CONFIRM_REDIRECT_URL` y `OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL`: se dejan sin definir. Las plantillas de Supabase llevan el enlace fijo (sección 3), así que no hacen falta.
- Control: no consume estas variables, no se toca su fichero.
- Bot-1 y bot-2: se recrean en el despliegue rodante con la imagen nueva y leen el fichero en ese momento.
- Control: se redespliega igualmente, porque la aprobación de solicitudes vive en su imagen.

SECURITY_NOTE: quitar el captcha no es la protección antiabuso definitiva. Siguen pendientes Cloudflare delante del origen (DEBT-093), el límite por usuario en las rutas de lectura (DEBT-092) y la vigilancia del abuso de registro y recuperación. Hoy protegen los límites por IP y por correo del backend en Valkey, que cierran (503) si Valkey cae.

### 10.3 Supabase Auth (lo cambia el propietario en el panel, tras el GO)

- **Confirm signup** (registro de la app): enlace `oposibots://auth?token_hash={{ .TokenHash }}&type=signup`. No usar `{{ .ConfirmationURL }}`. Debe llevar TAMBIEN `{{ .Token }}` (ver la nota de abajo, revision 2026-10-14).
- **Magic Link** (solo solicitudes de acceso al panel): únicamente el código `{{ .Token }}` y el texto de la solicitud de acceso; sin enlaces (`{{ .ConfirmationURL }}`, `{{ .TokenHash }}`) ni enlace de la app. El proyecto no usa enlaces mágicos para nada más.
- **Reset password**: `oposibots://auth?token_hash={{ .TokenHash }}&type=recovery`.
- **Captcha** de Supabase Auth: apagado (coherente con la sección 2).
- **Confirm email**: activado (consta activado el 14-oct según el traspaso).
- **SMTP**: proveedor propio recomendado antes de tener usuarios reales. El correo integrado puede limitar destinatarios y envíos por hora (UNKNOWN).
- **Redirecciones permitidas**: solo hacen falta si algún día se usan las variables de redirección.
- **Riesgo abierto**: no se ha probado si un enlace `oposibots://` se puede pulsar en Gmail Android o en Mail de iOS. Si no, hace falta un puente https (no implementado). Se comprueba con un único correo real después del GO.
- **Nota (revisión de seguridad 2026-10-14, DEBT-117/118)**: para una cuenta SIN confirmar, GoTrue puede mandar «Confirm signup» en lugar de «Magic Link» cuando el backend pide el código de una solicitud de acceso (`/otp`). Por eso «Confirm signup» DEBE incluir también `{{ .Token }}` (con una línea del tipo «si has pedido acceso al panel de OposiControl, tu código es …»): si no, el solicitante recibiría solo el enlace de la app y no podría verificar. Pulsar ese enlace ya no da la cuenta a nadie: la cuenta de una solicitud se crea con una contraseña aleatoria del servidor y la contraseña del panel se elige al verificar el código; el panel avisa «No pulses ningún enlace del correo: usa solo el código».

### 10.4 SQL de revisión antes de desplegar (lo ejecuta el propietario en el editor SQL de Supabase)

Solo lectura. Agregados, sin datos personales.

```sql
select s.estado,
       count(*) as solicitudes,
       count(*) filter (where u.email_confirmed_at is not null) as con_correo_confirmado,
       count(*) filter (where s.creado_en < now() - interval '1 hour') as con_mas_de_1_hora
from public.solicitudes_acceso_admin s
left join auth.users u on u.id = s.usuario_id
group by s.estado
order by s.estado;
```

Cuentas con solicitud no aprobada que tienen sesiones abiertas:

```sql
select count(distinct s.usuario_id) as cuentas_con_sesion, count(se.id) as sesiones
from public.solicitudes_acceso_admin s
join auth.sessions se on se.user_id = s.usuario_id
where s.estado <> 'aprobada';
```

Si la segunda consulta devuelve algo distinto de 0, cerrar esas sesiones (escritura: solo con GO expreso):

```sql
delete from auth.sessions
where user_id in (select usuario_id from public.solicitudes_acceso_admin where estado <> 'aprobada');
```

Tras el despliegue el refresco ya se deniega para esas cuentas; un token de acceso vivo dura como mucho la duración del JWT del proyecto (UNKNOWN, 1 hora por defecto en Supabase).

### 10.5 Migración

`20261020100000_security_access_request_email_verified.sql` (columnas `correo_verificado_en` e `intentos_verificacion`). Va antes del despliegue: el código nuevo la lee. Se aplica con el procedimiento habitual del propietario (copiar el fichero y aplicarlo). No se ha ejecutado.

### 10.6 Orden del despliegue (preparado, NO ejecutado)

1. Instantánea: copia de `~/oposibots-stack/env/*.env`, `docker ps` e imágenes actuales (`cfa2e14afc8e` es la vuelta atrás).
2. SQL de revisión (sección 4).
3. Aplicar la migración `20261020100000`.
4. Plantillas de Supabase (sección 3).
5. Entorno (sección 2).
6. Fusión autorizada de `feature/oposibot-consumer-backend` en `main`.
7. Imagen con el script oficial: `bash scripts/build-backend-image.sh`, desde el árbol limpio en `origin/main` (lo hace `publicar-oposicontrol.sh`).
8. `redeploy-rolling.sh`: bot-1 → sano → bot-2 → sano → control.
8b. **OBLIGATORIO, con GO expreso y solo cuando TODAS las réplicas (bot-1, bot-2 y control) ya corren la imagen nueva**: limpieza de las solicitudes del flujo ANTERIOR (revisión de seguridad #3). Ver el detalle debajo de esta lista.
9. Pruebas mínimas contra producción, una de cada (sección 7).
10. Logs: `held by a non-approved admin access request`, `rejected by upstream`; métricas de 4xx/5xx en `/v1/auth/*`.
11. Observación de 24 horas.

Caddy, Valkey, Prometheus, Grafana y los límites de recursos: sin cambios.

#### 10.6.8b Limpieza de las solicitudes del flujo anterior (preparado, NO ejecutado)

Por qué: las solicitudes creadas ANTES del despliegue nacieron con la contraseña que eligió quien las pidió (sin demostrar el buzón), sin `correo_verificado_en` y, si alguien pulsó un enlace, con la cuenta confirmada y sesiones vivas. El código nuevo las trata como sin verificar y las libera de forma perezosa (DEBT-116), pero hasta entonces esas credenciales y sesiones siguen ahí. Este paso las limpia de una vez. Se ejecuta DESPUÉS de que todas las réplicas corran la imagen nueva (así ninguna réplica vieja crea filas del flujo anterior mientras tanto) y SOLO las filas creadas antes del despliegue: `<INSTANTE_DEL_DESPLIEGUE>` es el instante (UTC) en que arrancó la PRIMERA réplica con la imagen nueva (`docker inspect -f '{{.State.StartedAt}}' <contenedor>` de bot-1 en el paso 8). Sin ese filtro se borrarían solicitudes NUEVAS que alguien está verificando.

Esquema comprobado en el repositorio: `public.roles_usuario (usuario_id, rol_id)` y `public.roles_app (id, codigo)` (`supabase/baseline/main_shape_subset.sql`); `public.solicitudes_acceso_admin.usuario_id → auth.users(id) on delete cascade` (`20261013100000`); `pgcrypto` en el esquema `extensions` (`CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions` en la línea base), así que `extensions.crypt`, `extensions.gen_salt` y `extensions.gen_random_bytes` existen. El esquema `auth` no está en el repositorio: la consulta 0 lo comprueba en vivo.

**0) Consulta previa (solo lectura).** Reglas de borrado de las FK hacia `auth.users` y de `auth.refresh_tokens` hacia `auth.sessions` (`c` = cascade, `n` = set null, `a`/`r` = bloquean el borrado: si alguna tabla con filas de estas cuentas tiene `a`/`r`, el `delete` de abajo falla y la transacción entera se deshace, que es lo seguro):

```sql
select conrelid::regclass as tabla, conname, confdeltype
from pg_constraint
where contype = 'f' and confrelid = 'auth.users'::regclass
order by 1;

select conrelid::regclass as tabla, conname, confdeltype
from pg_constraint
where contype = 'f' and conrelid = 'auth.refresh_tokens'::regclass and confrelid = 'auth.sessions'::regclass;
```

Si la segunda devuelve `confdeltype = 'c'`, los refresh tokens con `session_id` caen en cascada al borrar las sesiones; el `delete` explícito de `auth.refresh_tokens` de abajo se mantiene igualmente porque los tokens anteriores a esa columna tienen `session_id` nulo y no caen en cascada (no hace daño si no hay ninguno).

**1) Listados previos (solo lectura, sin datos personales).**

Solicitudes no aprobadas y sin verificar cuya cuenta TIENE roles (no se tocan aquí: se resuelven a mano, una a una):

```sql
select s.id, s.usuario_id, s.estado, s.creado_en,
       (u.email_confirmed_at is not null) as confirmada,
       array_agg(ra.codigo order by ra.codigo) as roles
from public.solicitudes_acceso_admin s
join public.roles_usuario ru on ru.usuario_id = s.usuario_id
join public.roles_app ra on ra.id = ru.rol_id
left join auth.users u on u.id = s.usuario_id
where s.estado <> 'aprobada' and s.correo_verificado_en is null
  and s.creado_en < '<INSTANTE_DEL_DESPLIEGUE>'::timestamptz
group by s.id, s.usuario_id, s.estado, s.creado_en, u.email_confirmed_at
order by s.creado_en;
```

Administradores YA aprobados por el flujo anterior (aprobados sin que nadie demostrara el buzón):

```sql
select s.id, s.usuario_id, s.resuelto_en, s.resuelto_por,
       (u.email_confirmed_at is not null) as confirmada,
       u.banned_until,
       (select count(*) from auth.sessions se where se.user_id = s.usuario_id) as sesiones
from public.solicitudes_acceso_admin s
left join auth.users u on u.id = s.usuario_id
where s.estado = 'aprobada' and s.correo_verificado_en is null
order by s.resuelto_en;
```

Para cada uno, el propietario decide UNA de dos opciones:

- **(A) Confirmar fuera de banda** (por un canal ya conocido, no por ese correo) que la persona es la titular del buzón. Si lo es, no se escribe nada; se anota la decisión en el ledger.
- **(B) Rotar la contraseña y purgar las sesiones** (solo con GO expreso, una cuenta cada vez): el titular real del buzón tendrá que usar `recover` para volver a entrar; quien pidió el acceso con un correo ajeno se queda fuera.

```sql
-- solo con GO expreso
begin;
update auth.users
   set encrypted_password = extensions.crypt(encode(extensions.gen_random_bytes(32), 'base64'), extensions.gen_salt('bf'))
 where id = '<usuario_id>';
select public.admin_purge_user_sessions('<usuario_id>');
commit;
```

**2) Limpieza (escritura, solo con GO expreso, todo o nada).** Solicitudes del flujo anterior no aprobadas, sin verificar y cuya cuenta NO tiene roles: cuenta nunca confirmada → se borra (la fila cae en cascada); cuenta confirmada → contraseña aleatoria, sesiones y refresh tokens fuera y se borra solo la fila (la cuenta queda para el titular del buzón, que entra con `recover`). Cada escritura repite las condiciones por si algo cambió entre la foto y la escritura.

```sql
-- solo con GO expreso
begin;

create temp table pre_change on commit drop as
  select s.id, s.usuario_id, (u.email_confirmed_at is not null) as confirmed
  from public.solicitudes_acceso_admin s
  join auth.users u on u.id = s.usuario_id
  where s.estado <> 'aprobada'
    and s.correo_verificado_en is null
    and s.creado_en < '<INSTANTE_DEL_DESPLIEGUE>'::timestamptz
    and not exists (select 1 from public.roles_usuario r where r.usuario_id = s.usuario_id);

-- solo con GO expreso
delete from auth.users u
 where u.id in (select usuario_id from pre_change where not confirmed)
   and u.email_confirmed_at is null
   and not exists (select 1 from public.roles_usuario r where r.usuario_id = u.id);

-- solo con GO expreso
update auth.users u
   set encrypted_password = extensions.crypt(encode(extensions.gen_random_bytes(32), 'base64'), extensions.gen_salt('bf'))
 where u.id in (select usuario_id from pre_change where confirmed)
   and not exists (select 1 from public.roles_usuario r where r.usuario_id = u.id);

-- solo con GO expreso (refresh tokens sin session_id: no caen en cascada; ver la consulta 0)
delete from auth.refresh_tokens
 where user_id in (select usuario_id::text from pre_change where confirmed);

-- solo con GO expreso
delete from auth.sessions
 where user_id in (select usuario_id from pre_change where confirmed);

-- solo con GO expreso
delete from public.solicitudes_acceso_admin s
 where s.id in (select id from pre_change where confirmed)
   and s.correo_verificado_en is null
   and s.estado <> 'aprobada';

commit;
```

Comprobación después (solo lectura): repetir los dos listados de 1) y la consulta de sesiones de la sección 10.4; las filas no aprobadas, sin verificar, anteriores al despliegue y sin roles deben ser 0. No hay auditoría automática de este paso: anotar en el ledger cuántas cuentas se borraron y cuántas se neutralizaron (los contadores de filas de cada sentencia).

Requisito nuevo: `AUTH_RATE_LIMIT_HMAC_SECRET` (32+ caracteres) es obligatorio fuera de desarrollo también para el proceso de control; hoy solo está en `backend.bot.env`. Antes de desplegar, copiar el mismo valor a `backend.control.env` (sin imprimirlo), o control no arranca.

### 10.7 Pruebas mínimas para después del GO (no ejecutadas)

- **Registro**: cuenta nueva → correo → enlace abre la app → verificada → login.
- **Recuperación**: petición → correo → enlace → nueva contraseña → login.
- **Solicitud de acceso**: petición (nombre, correo, motivo; sin contraseña) → correo con código → código y contraseña elegida en el panel → verificada (cuenta bloqueada en Supabase) → aprobación por superadmin (desbloqueo + rol) → acceso al panel.
- **Antes de nada, comprobar con un único correo** que `/otp` sobre una cuenta creada por el administrador y sin confirmar envía la plantilla Magic Link con el código y no la de confirmación ni cambia la contraseña (DEBT-118).
- **Primera prueba tras el GO (obligatoria)**: anotar QUÉ plantilla llega para una solicitud de acceso nueva («Magic Link» o «Confirm signup») y que el código que trae verifica; si llega «Confirm signup», comprobar que lleva el código (nota de 10.3) y registrar el resultado en DEBT-118. Comprobar también que tras verificar se entra al panel con la contraseña elegida (después de aprobar) y no con ninguna otra.
- **Cuenta retenida**: solicitud verificada pendiente → login 403, refresco denegado, OAuth denegado.
- **Solicitud sin verificar** con el correo de otra persona → el dueño real se registra con normalidad y la solicitud desaparece.
- **Rechazo**: rechazar → la cuenta se borra y el correo queda libre.
