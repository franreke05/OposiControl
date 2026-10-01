# Deployment

Estado: `NOT_VERIFIED` (runbook escrito, aun no ejercitado; ningun despliegue realizado desde este repo).
El despliegue lo ejecuta un humano con el gate de produccion; nada de esto corre en CI.

## Orden obligatorio: parar el Ktor viejo, migrar, luego desplegar

0. (MASTER6-CICD-001) Ventana de mantenimiento: las migraciones 6-9 de Slice 1 (`20260930100800..101600`) son contract y el Ktor VIEJO
   (previo a Slice 1) queda PROHIBIDO contra ellas (gate de produccion, seccion 2). Por tanto, ANTES del paso 1 se PARA el Ktor viejo
   (`docker stop oposicontrol-ktor`; Caddy responde 502 durante la ventana) y no se rearranca esa imagen: el siguiente arranque es el
   Ktor nuevo del paso 2. Anotar en el registro del gate la hora de parada y de arranque nuevo (ventana de caida ACEPTADA, minutos).
   Las migraciones que solo anaden objetos (1-4, 10, 11) no exigen la parada, pero se sigue el mismo orden por simplicidad.
1. Aplicar migraciones de `supabase/migrations/` a la base (con backup/PITR verificado).
2. Desplegar Ktor. Sin las migraciones la pasarela responde 503 (falla cerrado), asi que
   el orden inverso rompe el trafico.
3. Verificar la version desplegada por la imagen, no por `/health` (hoy devuelve una cadena `build` fija):
   `docker inspect <contenedor> --format '{{.Config.Image}} {{index .Config.Labels "org.opencontainers.image.revision"}}'`
   debe dar `oposicontrol-backend:<sha12>` y el commit completo esperado; ademas `/health/ready` = 200.
   MASTER6-CICD-002 (imagen de loop 5+, `/app` de root y no escribible; la imagen aun NO se ha construido ni arrancado en este entorno, sin Docker):
   con la imagen construida, comprobar que corre como `app` y que el codigo no es escribible pero `/tmp` si:
   `docker run --rm --entrypoint sh oposicontrol-backend:<sha12> -c 'test "$(id -un)" = app && ! touch /app/x 2>/dev/null && touch /tmp/x'`
   debe terminar con codigo 0; despues arrancar el contenedor con el entorno real y esperar `/health/ready` = 200.
   Slice 1 (migraciones `20260930100000..102000`): orden, hashes y clase de rollback en
   `docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` del repo OposiBot.

## Construir

```bash
bash scripts/build-backend-image.sh   # exige arbol limpio (sin cambios, sin archivos sin rastrear y sin archivos IGNORADOS en backend/core/domain/data/build-logic salvo build/.gradle/.kotlin: `scripts/assert-clean-tree.sh`); etiqueta oposicontrol-backend:<sha12>
```

- `ops/deploy/Dockerfile.backend` copia `backend/ktor/build/install/ktor` (resultado de `installDist`);
  `.dockerignore` deja fuera todo lo demas del contexto.
- CI (MASTER7-CICD-002): el job `backend-image` de `.github/workflows/ci.yml` ejecuta `bash scripts/build-backend-image.sh` y
  despues `bash scripts/smoke-backend-image.sh` (uid 10001, `/app` no escribible, `/tmp` escribible y `/health/live` = 200 con
  variables de relleno, sin secretos, sin push ni despliegue). No sustituye la comprobacion con el entorno REAL de la seccion
  Desplegar (`/health/ready` = 200).
- Dependencias (MASTER7-CICD-005): `gradle/verification-metadata.xml` fija el sha256 de cada artefacto y la verificacion es
  estricta. Tras subir una dependencia: `./gradlew --write-verification-metadata sha256 <tareas de CI> --no-configuration-cache`
  y revisar el diff. `tools/redocly/package-lock.json` fija la herramienta de lint de OpenAPI.
- La base `eclipse-temurin:21-jre-jammy` esta fijada por digest. Para actualizarla:
  `docker buildx imagetools inspect eclipse-temurin:21-jre-jammy`, cambiar el digest y revisar.

## Desplegar

1. Registrar `OLD_SHA`/`OLD_IMAGE` del contenedor en marcha y etiquetar la imagen actual como
   `oposicontrol-backend:rollback-<fecha>`.
2. Ejecutar el contenedor con `SENTRY_RELEASE=<sha12>` (y `BUILD_SHA=<sha12>` cuando el backend
   lo exponga en `/health`), `METRICS_SCRAPE_TOKEN` y el resto de variables de `.env.example`.
   `SENTRY_ENVIRONMENT` (MASTER2-OBS-005) etiqueta los eventos de Sentry; en blanco hereda `APP_ENV`.
   Preflight de entorno con `APP_ENV=production` (la imagen ya lo fija por defecto; `APP_ENV` en blanco o
   desconocido NO es desarrollo): `APP_ENV`, variables de Supabase, `METRICS_SCRAPE_TOKEN`,
   `TRUSTED_PROXY_HOSTS` (solo IPs literales) y la URL de Valkey del limitador (fuera de desarrollo el
   limitador es Valkey compartido). Lista completa en `.env.example`.
   MASTER2-AUTH-002: el backend corre detras de Caddy, asi que fuera de desarrollo el arranque FALLA sin
   `TRUST_PROXY_HEADERS=true` y `TRUSTED_PROXY_HOSTS=<IP literal de Caddy en la red de Docker>` (fijarla con
   una IP estatica en la red). Sin ello la IP de cliente seria la del proxy: un unico cupo de limite para todos.
   Migraciones antes del Ktor de loop 2: `20260930101500_slice1_session_revocation_monotonic.sql` (sin ella un `fallida`
   tardio puede sobrescribir un `hecha`) y `20260930101600_admin_sync_roles_actor_guard.sql` (el Ktor nuevo
   llama a `admin_sync_roles` con `p_actor_id`; sin ella recibe PGRST202); en rollback, primero el Ktor y despues las migraciones 101600/101500.
   Migracion antes del Ktor de loop 4: `20260930101850_admin_sync_roles_audited.sql` (D1-26 llama a `admin_sync_roles_audited`;
   sin ella recibe PGRST202); en rollback, primero el Ktor de loop 4 y despues la 101850.
   Migraciones antes del Ktor de la remediacion completa: `20260930101900_admin_sync_roles_audited_expected.sql` (D1-26 llama a la
   sobrecarga de 4 argumentos con `p_expected_role_ids`, MASTER6-IDEM-003) y `20260930102000_slice1_report_action_once.sql`
   (`resolverReporte` escribe la accion con `admin_record_report_action`, MASTER6-API-001); sin ellas: PGRST202 en D1-26 y la
   resolucion queda incompleta (reanudable) en la ultima escritura. En rollback, primero ese Ktor y despues 102000/101900.
   La garantia "una accion por resolucion" de 102000 solo vale cuando el Ktor nuevo es el unico escritor de resoluciones: el
   Ktor viejo (POST directo a `acciones_moderacion`) y el staff por la politica `acciones_moderacion_staff_all` no toman el
   cerrojo del reporte. Por eso el Ktor viejo se para ANTES de migrar y no se rearranca despues (residual en
   `SLICE_1_PRODUCTION_GATE.md`, seccion 8).
   Orden backend ANTES que el cliente admin (MASTER4-API-005): el `AdminV1UserDto` actual emite `ticketsBalance = null`
   (sin wallet) e `isActive` explicito (`@EncodeDefault`); un backend anterior omite `ticketsBalance = 0` (default) y el
   cliente nuevo lo pintaria "Sin dato" en saldos a cero durante el rollout mixto. Desplegar primero el Ktor y despues
   el cliente admin; el cliente tolera ambas formas de `isActive`.
3. `/health/ready` debe dar 200. Desde el host publico
   `curl -s -o /dev/null -w '%{http_code}' https://<host>/metrics` debe dar `404` (Caddy,
   `ops/caddy/metrics-block.caddy`); un `200` sin token significa que el bloqueo no esta activo y es un fallo del despliegue.
   MASTER2-SEC-002: el sitio del API importa tambien `ops/caddy/body-limit.caddy` (`request_body { max_size 2MB }` salvo
   multipart); Ktor rechaza ademas con 413 un `Content-Length` > 2 MB (`RequestBodyLimit`). Comprobar con un POST JSON de
   3 MB a `/api/v1/app/eventos`: debe dar `413`. Excepcion (MASTER5-API-007): `/v1/auth/*` tiene su propio tope de 16 KB y
   responde 400 `VALIDATION` (el estado declarado en `openapi/auth`), no el 413 de Ktor.

## Rollback

Reejecutar el contenedor con la imagen `rollback-<fecha>` (solo valido mientras las migraciones de
contract de Slice 1 no esten aplicadas). Las migraciones de Slice 1 NO son todas expand: `20260930100500`
redefine funciones (rollback manual verbatim) y `20260930100800` retira el indice unico global de
`eventos_app` (contract; el rollback de BD solo es posible mientras `cross_user_shared_keys = 0`, si no,
roll forward). `20260930100900`, `20260930101500` y `20260930101600` son compatibles y reversibles (la 101600 solo anade un overload: se revierte el Ktor de loop 2 ANTES de retirarla). `20260930101730` (indice unico parcial de `tickets_grant`, MASTER3-OBS-002) es aditiva y compatible con cualquier Ktor; su rollback solo borra el indice. `20260930101850` (MASTER4-OBS-001) solo anade `admin_sync_roles_audited`: compatible con el Ktor de loop 2/3; se revierte el Ktor de loop 4 ANTES de retirarla. `20260930101900` (MASTER6-IDEM-003) solo anade la sobrecarga de 4 argumentos de `admin_sync_roles_audited` y `20260930102000` (MASTER6-API-001) solo anade `admin_record_report_action`: aditivas, compatibles con el Ktor anterior; se revierte ANTES el Ktor que las llama. Clasificacion por fichero, orden y hashes:
`docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` (repo OposiBot). Ninguna se revierte automaticamente.

## Pendiente (registrado, no ejercitado)

- `/health` aun devuelve una cadena `build` fija (`20260512-no-legacy-columns`), por eso el paso 3 verifica la imagen con `docker inspect`; inyectar el SHA es un cambio de codigo de Ktor.
- Nombre de imagen canonico: `oposicontrol-backend:<sha12>` (el servicio/contenedor se llama `oposicontrol-ktor`).
- `/health/ready` solo comprueba Supabase; una sonda de marcador de esquema requiere aprobacion
  del owner del contrato (D8-08 congelado).
- CI (`.github/workflows/ci.yml`) verifica tests, lint OpenAPI y `scripts/verify-ops-config.sh`;
  `dbtest` corre sobre PostgreSQL 16 embebido (zonky); la validacion PG17 sigue siendo el gate local Supabase.
