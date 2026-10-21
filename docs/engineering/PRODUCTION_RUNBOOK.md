# Runbook de produccion (un host)

Estado: `NOT_VERIFIED` (escrito, no ejercitado; nada desplegado desde este repo). Complementa `DEPLOYMENT.md` (orden obligatorio migraciones -> Ktor y gate de produccion).
Artefactos: `ops/deploy/compose.production.yaml`, `ops/deploy/backend.{common,bot,control}.env.example`, `ops/deploy/compose.env.example`, `ops/caddy/Caddyfile.production`,
`ops/monitoring/prometheus/{prometheus.production.yml,alert_rules.yml}`, `ops/monitoring/grafana/**`. Decisiones: `RUNTIME_DECISIONS.md`. Borde: `CLOUDFLARE_RUNBOOK.md`.

Aviso de capacidad: bot-1, bot-2 y control comparten el mismo host; **no son 3x capacidad** (ver cabecera del compose).

## PORT_MATRIX

| Servicio | Puerto interno | Publicado al host | Quien lo usa |
|---|---|---|---|
| caddy | 80, 443 | **80, 443** (unico servicio publicado) | Cloudflare (solo rangos CF en el firewall) |
| ktor-bot-1 / ktor-bot-2 / ktor-control | 8080 | no | caddy, prometheus |
| valkey | 6379 | no | los tres Ktor |
| prometheus | 9090 | no | grafana, ktor-control (estado del sistema) |
| grafana | 3000 | no (acceso por tunel SSH al IP del contenedor) | administradores |

Acceso a Grafana: `docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' <contenedor-grafana>` y `ssh -L 3000:<ip>:3000 <host>`; nunca publicarlo.

## VOLUME_MATRIX

| Volumen / bind | Contenido | Persistencia | Copia |
|---|---|---|---|
| `caddy-data` | estado de Caddy (OCSP, claves internas) | si | no critica |
| `caddy-config` | config autoguardada | si | no |
| `prometheus-data` | TSDB, 15 d / 4 GB | si | opcional |
| `grafana-data` | usuarios y preferencias de Grafana (dashboards vienen del repo) | si | opcional |
| `tmpfs /tmp` (Ktor, 512 MB) | temporales de ffmpeg | no | no |
| bind `/etc/oposibots/production/*` | `backend.common.env`, `backend.bot.env`, `backend.control.env`, `compose.env`, `metrics_token`, `origin/` | fuera del repo, root, 0600 | gestor de secretos |
| (sin volumen) valkey | contadores de limite | no, por diseno | no |

## RESOURCE_LIMITS (marcadores a re-medir en el host real)

| Servicio | cpus | mem_limit | Heap JVM | Nota |
|---|---|---|---|---|
| ktor-bot-1, ktor-bot-2 | 1.0 c/u | 768 MB c/u | 60 % (~460 MB) | sin ffmpeg |
| ktor-control | 1.5 | 2 GB | 45 % (~920 MB) | ffmpeg + decodificacion de imagenes |
| caddy | 1.0 | 192 MB | n/a | |
| valkey | 0.25 | 96 MB (`maxmemory 64mb`) | n/a | |
| prometheus | 0.5 | 512 MB | n/a | |
| grafana | 0.5 | 256 MB | n/a | |
| **Total** | **~6.25 cpu (techo)** | **~5.1 GB** | | el host necesita >= 6 GB de RAM y margen para SO/Docker; si no, bajar `BOT_MEM_LIMIT`/`CONTROL_MEM_LIMIT` y el % de heap en `compose.env` |

Los limites de CPU son topes, no reservas: con menos nucleos que la suma, los contenedores compiten.

## ENV_REQUIREMENTS (solo nombres)

* `compose.env` (interpolacion): `BACKEND_IMAGE`, `API_HOST`, `VALKEY_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` y opcionales; lista en `ops/deploy/compose.env.example`.
* Entorno de los Ktor: **un fichero por perfil** (minimo privilegio; antes un unico `backend.env` daba a los contenedores bot el token
  estatico de administracion). El reparto sale del codigo (que lee cada perfil), no de la costumbre:
  * `backend.common.env` (los tres Ktor; nombres en `ops/deploy/backend.common.env.example`): `APP_ENV`, `SUPABASE_URL`,
    `SUPABASE_SECRET_KEY` (o `SUPABASE_SERVICE_ROLE_KEY`), `METRICS_SCRAPE_TOKEN`, y los opcionales de log, timeouts, bucket de examen y Sentry.
  * `backend.bot.env` (SOLO `ktor-bot-1` y `ktor-bot-2`, el MISMO fichero; nombres en `ops/deploy/backend.bot.env.example`): el runtime de
    auth de consumidor (`OposiBotsAuthConfiguration.kt`, que `control` no construye): `SUPABASE_PUBLISHABLE_KEY`, `AUTH_RATE_LIMIT_MODE=valkey`,
    `AUTH_RATE_LIMIT_HMAC_SECRET` (>= 32 car., **identico en las dos replicas bot**), `VALKEY_URL`, `TRUST_PROXY_HEADERS=true`,
    `TRUSTED_PROXY_HOSTS` (IP literal de Caddy, por defecto la `.10` de `EDGE_SUBNET`), `OPOSIBOTS_AUTH_ENABLED`, captcha, recuperacion y Apple,
    `AUTH_PKCE_MODE=required` (obligatorio fuera de `APP_ENV=local|test`: con off/optional el arranque falla, SEC-V1-04 / RB-04), y de economia/trabajos
    `PAYMENTS_REFUNDS_ENABLED=false` (hasta decision del propietario) y `CHALLENGE_JOB_SECONDS` (vacia = 900). `PAYMENTS_SANDBOX_SECRET`
    **nunca** en produccion: con valor y `APP_ENV` distinto de `local`/`test` el proceso sale con codigo 78 antes de escuchar.
  * `backend.control.env` (SOLO `ktor-control`; nombres en `ops/deploy/backend.control.env.example`): `OPOSICONTROL_BACKEND_TOKEN` (secreto de
    administracion: **nunca** en los otros dos ficheros), `ENABLE_VERBOSE_NETWORK_LOGS`, `SUPABASE_BUCKET_NEWS_ASSETS`.
  * Migrar desde un `backend.env` unico: repartir sus lineas en los tres ficheros segun los `.example` y borrar el antiguo. Comprobar el
    resultado sin imprimir valores: `$C config --format json | jq '.services["ktor-bot-1"].environment | has("OPOSICONTROL_BACKEND_TOKEN")'` debe dar `false`.
  * `scripts/verify-ops-config.sh` falla si un servicio bot referencia el fichero de control (o al reves), si la plantilla comun vuelve a
    traer un `env_file`, o si una variable aparece en mas de un `.example`.
* Ficheros: `/etc/oposibots/production/metrics_token` (mismo valor que `METRICS_SCRAPE_TOKEN`), `/etc/oposibots/production/origin/{origin.pem,origin.key}`.
* **No definir** las variables de solo desarrollo (`OPOSICONTROL_ALLOW_DEV_*`, `OPOSICONTROL_ENABLE_BACKEND_RESTART`, ...).

## Preflight del servidor (SOLO LECTURA: comandos, no ejecutados)

```bash
uname -a; cat /etc/os-release | head -3
nproc; free -h; df -h /var/lib/docker /           # RAM >= 6 GB, disco libre >= 20 GB
docker version --format '{{.Server.Version}}'; docker compose version
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'      # que corre hoy (p. ej. oposicontrol-ktor viejo)
docker network ls; docker volume ls
ss -tlnp | grep -E ':(80|443|8080|6379|9090|3000)\b' || echo "puertos libres"
sudo ufw status verbose || sudo nft list ruleset | head -50         # reglas de entrada y DOCKER-USER
sudo iptables -S DOCKER-USER
ls -l /etc/oposibots/production/ 2>&1                                # existen los ficheros de secretos? (modo 0600/0400)
getent hosts {API_HOST}; curl -s https://www.cloudflare.com/ips-v4 | head -3
docker image ls --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep oposicontrol-backend   # tags previos para rollback
```

## Orden de arranque

0. Migraciones aplicadas y ventana de mantenimiento segun `DEPLOYMENT.md` (parar el Ktor viejo ANTES de las migraciones contract).
1. Validar sin arrancar (desde la RAIZ del repo):
   `docker compose --env-file /etc/oposibots/production/compose.env -f ops/deploy/compose.production.yaml config -q`
2. `... up -d valkey` y esperar `healthy`.
3. `... up -d ktor-control` -> esperar `healthy`; `... up -d ktor-bot-1` -> `healthy`; luego `ktor-bot-2`.
4. `... up -d caddy` (publica 80/443). Activar el proxy de Cloudflare / DNS solo despues del smoke interno.
5. `... up -d prometheus grafana`; comprobar en Prometheus `up{job="ktor"}` = 3 y que no hay alertas disparadas.

## Salud y humo (por orden)

```bash
C="docker compose --env-file /etc/oposibots/production/compose.env -f ops/deploy/compose.production.yaml"
$C ps                                                   # todos healthy
for s in ktor-bot-1 ktor-bot-2 ktor-control; do
  $C exec -T $s curl -fsS http://127.0.0.1:8080/health/live; echo
  $C exec -T $s curl -fsS http://127.0.0.1:8080/health/ready; echo      # 200 y {"checks":{"supabase":"UP"}}
done
# Version desplegada = imagen, no /health:
docker inspect $($C ps -q ktor-bot-1) --format '{{.Config.Image}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
# Metricas por red interna (con token), nunca por el borde:
$C exec -T prometheus sh -c 'wget -qO- --header="Authorization: Bearer $(cat /etc/prometheus/secrets/metrics_token)" http://ktor-bot-1:8080/metrics' | head -3
# Borde (tras apuntar DNS): ver CLOUDFLARE_RUNBOOK.md seccion 7. Esperado: /metrics 404, /admin/backend/restart 404, ruta desconocida 404.
# Reparto: dos peticiones seguidas a /health/live deben mostrar ambas replicas en `RPS por replica` (Grafana).
# Valkey fail-closed (opcional, ventana de pruebas): `$C stop valkey` => /v1/auth/login 503 con Retry-After; `$C start valkey` => se recupera.
```

## Rollback (imagen anterior)

1. Localizar el tag previo: `docker image ls oposicontrol-backend` (los tags son `<sha12>`; el desplegado esta en `BACKEND_IMAGE`).
2. Editar `BACKEND_IMAGE` en `/etc/oposibots/production/compose.env` al tag anterior (y `SENTRY_RELEASE` en `backend.common.env`).
3. Por replica, de una en una para no cortar el servicio: `$C up -d --no-deps ktor-bot-1` -> `healthy` -> `$C up -d --no-deps ktor-bot-2` -> `$C up -d --no-deps ktor-control`.
4. Verificar con la etiqueta `org.opencontainers.image.revision` y `/health/ready` = 200.
5. Las migraciones NO se revierten con este rollback: manda la clase de rollback por migracion del gate de produccion (`DEPLOYMENT.md`); el Ktor viejo esta prohibido desde la migracion 6.
6. Si falla Caddy: `$C up -d caddy` con el Caddyfile anterior (git); si es el certificado, ver `CLOUDFLARE_RUNBOOK.md` seccion 2.

## Operacion

* Logs: `docker compose ... logs -f --tail=200 <servicio>` (rotan a 5 x 20 MB por contenedor; JSON de Caddy sin cabeceras de credenciales).
* Reinicio de una replica bot: `$C restart ktor-bot-1` (Caddy la saca por health check; la otra sirve; `stop_grace_period` 35 s).
* Valkey caido = login/refresh/registro en 503 (ver `RUNTIME_DECISIONS.md`): prioridad alta.
* Actualizar rangos de Cloudflare: `Caddyfile.production` (`trusted_proxies`) y firewall a la vez.
