# Deployment

Estado: `NOT_VERIFIED` (runbook escrito, aun no ejercitado; ningun despliegue realizado desde este repo).
El despliegue lo ejecuta un humano con el gate de produccion; nada de esto corre en CI.

## Orden obligatorio: migrar, luego desplegar

1. Aplicar migraciones de `supabase/migrations/` a la base (con backup/PITR verificado).
2. Desplegar Ktor. Sin las migraciones la pasarela responde 503 (falla cerrado), asi que
   el orden inverso rompe el trafico.
3. Verificar la version desplegada por la imagen, no por `/health` (hoy devuelve una cadena `build` fija):
   `docker inspect <contenedor> --format '{{.Config.Image}} {{index .Config.Labels "org.opencontainers.image.revision"}}'`
   debe dar `oposicontrol-backend:<sha12>` y el commit completo esperado; ademas `/health/ready` = 200.
   Slice 1 (migraciones `20260930100000..100900`): orden, hashes y clase de rollback en
   `docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` del repo OposiBot.

## Construir

```bash
bash scripts/build-backend-image.sh   # exige arbol limpio (tambien sin archivos sin rastrear); etiqueta oposicontrol-backend:<sha12>
```

- `ops/deploy/Dockerfile.backend` copia `backend/ktor/build/install/ktor` (resultado de `installDist`);
  `.dockerignore` deja fuera todo lo demas del contexto.
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
   Migraciones antes del Ktor de loop 2: `20260930101600_admin_sync_roles_actor_guard.sql` (el Ktor nuevo
   llama a `admin_sync_roles` con `p_actor_id`); en rollback, primero el Ktor y despues la migracion.
3. `/health/ready` debe dar 200. Desde el host publico
   `curl -s -o /dev/null -w '%{http_code}' https://<host>/metrics` debe dar `404` (Caddy,
   `ops/caddy/metrics-block.caddy`); un `200` sin token significa que el bloqueo no esta activo y es un fallo del despliegue.
   MASTER2-SEC-002: el sitio del API importa tambien `ops/caddy/body-limit.caddy` (`request_body { max_size 2MB }` salvo
   multipart); Ktor rechaza ademas con 413 un `Content-Length` > 2 MB (`RequestBodyLimit`). Comprobar con un POST JSON de
   3 MB a `/api/v1/app/eventos`: debe dar `413`.

## Rollback

Reejecutar el contenedor con la imagen `rollback-<fecha>` (solo valido mientras las migraciones de
contract de Slice 1 no esten aplicadas). Las migraciones de Slice 1 NO son todas expand: `20260930100500`
redefine funciones (rollback manual verbatim) y `20260930100800` retira el indice unico global de
`eventos_app` (contract; el rollback de BD solo es posible mientras `cross_user_shared_keys = 0`, si no,
roll forward). `20260930100900` es compatible y reversible. Clasificacion por fichero, orden y hashes:
`docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` (repo OposiBot). Ninguna se revierte automaticamente.

## Pendiente (registrado, no ejercitado)

- `/health` aun devuelve una cadena `build` fija (`20260512-no-legacy-columns`), por eso el paso 3 verifica la imagen con `docker inspect`; inyectar el SHA es un cambio de codigo de Ktor.
- Nombre de imagen canonico: `oposicontrol-backend:<sha12>` (el servicio/contenedor se llama `oposicontrol-ktor`).
- `/health/ready` solo comprueba Supabase; una sonda de marcador de esquema requiere aprobacion
  del owner del contrato (D8-08 congelado).
- CI (`.github/workflows/ci.yml`) verifica tests, lint OpenAPI y `scripts/verify-ops-config.sh`;
  `dbtest` corre sobre PostgreSQL 16 embebido (zonky); la validacion PG17 sigue siendo el gate local Supabase.
