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
  despues `bash scripts/smoke-backend-image.sh` (etiqueta `org.opencontainers.image.revision` = commit, uid 10001, `/app` no
  escribible, `/tmp` escribible y `/health/live` = 200 en los perfiles `combined` y `SERVER_PROFILE=consumer`, este ultimo con
  `APP_ENV=development` solo para la comprobacion de arranque; falla al momento si el contenedor muere; variables de relleno,
  sin secretos, sin push ni despliegue). No sustituye la comprobacion con el entorno REAL de la seccion
  Desplegar (`/health/ready` = 200).
- Dependencias (MASTER7-CICD-005): `gradle/verification-metadata.xml` fija el sha256 de cada artefacto y la verificacion es
  estricta. Tras subir una dependencia se regenera EN LINUX (CI-GRADLE-VERIF; en Windows faltan variantes Linux), p. ej. en
  `eclipse-temurin:21-jdk-jammy` como uid no root (embedded-postgres de `:backend:dbtest` no arranca como root), con
  `ANDROID_HOME` apuntando a un SDK y un `GRADLE_USER_HOME` vacio, con EXACTAMENTE la lista del paso de deriva del job `tests`:
  `./gradlew --no-configuration-cache --write-verification-metadata sha256 :backend:ktor:dependencies --configuration runtimeClasspath :composeApp:dependencies --configuration jvmRuntimeClasspath :backend:ktor:installDist :backend:ktor:test :backend:dbtest:test :composeApp:compileKotlinJvm :data:jvmTest :domain:jvmTest :features:users:jvmTest :features:reports:jvmTest :core:database:jvmTest :composeApp:jvmTest :core:common:jvmTest :core:network:jvmTest :core:ui:jvmTest :features:analytics:jvmTest :features:auth:jvmTest :features:dashboard:jvmTest :features:exam-access:jvmTest :features:forum:jvmTest :features:imports:jvmTest :features:news:jvmTest :features:opositester:jvmTest :features:questions:jvmTest :features:rankings:jvmTest :features:settings:jvmTest :features:specialties:jvmTest :features:store:jvmTest :features:support:jvmTest`
  (si `dependency-audit` audita mas configuraciones se anaden aqui y en CI). `dependency-audit` audita contra OSV el
  `runtimeClasspath` del backend y el `jvmRuntimeClasspath` del cliente admin (`scripts/osv-ignore.json` vacio); el
  `releaseRuntimeClasspath` Android queda como seguimiento y las klibs de iOS fuera de alcance (no son coordenadas Maven).
  El diff debe ser solo de altas: un sha256 distinto para una coordenada existente es una senal de cadena de suministro que se
  investiga, no se acepta. `scripts/verify-ci-jvmtest-coverage.sh` (job `ops-static`, con control negativo) exige que TODO modulo con
  `src/jvmTest` o `src/commonTest` figure como `:modulo:jvmTest` en la linea de pruebas del job `tests`, en la lista de deriva y en
  esta; las excepciones solo van, justificadas, en `scripts/ci-jvmtest-allowlist.tsv`. CI repite ese comando y exige `git diff --exit-code`. Fuera de esa lista quedan iOS, MSI/DMG y el
  release Android: necesitan su propio `--write-verification-metadata` la primera vez que se usen. `:composeApp:createDistributable`
  (jlink + jpackage app-image, CI-OC-RELEASE) ya tiene sus artefactos (generados en Linux) y lo verifica en estricto el job
  `desktop-release-linux`; si cambia la version de Compose se regenera tambien con
  `./gradlew --no-configuration-cache --write-verification-metadata sha256 :composeApp:createDistributable -PoposiProduction=true`.
  `scripts/verify-gradle-drift-negative-control.sh` (job `tests`) es el control negativo FUNCIONAL del paso de deriva: en una copia
  temporal corrompe un sha256 / quita un componente y exige que `git diff --exit-code` falle.
- Flavors Android (INV-NETWORK-COMPLETE): `staging`/`prod` hornean `SUPABASE_URL`/`SUPABASE_ANON_KEY` desde el mismo origen que `dev` (variable
  de entorno o `.env`) y `verifyNonDevSupabaseConfig` hace FALLAR el build (`pre{Staging,Prod}{Debug,Release}Build`) si falta, no es https
  o apunta a localhost, porque `AppConfigResolver` lanza fuera de DEV sin ella. Esos flavors no se construyen en CI (sus BASE_URL siguen
  siendo marcadores `*.local`); `scripts/verify-android-flavor-config.sh` ejecuta el guard y comprueba su cableado.
  `tools/redocly/package-lock.json` fija la herramienta de lint de OpenAPI.
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
   (`resolverReporte` marca `reportes_contenido.accion_pendiente` en su compare-and-set y escribe la accion con
   `admin_record_report_action`, MASTER6-API-001); sin ellas: PGRST202 en D1-26 y el compare-and-set de la resolucion falla
   (columna desconocida) antes de escribir. En rollback, primero ese Ktor y despues 102000/101900 (el ROLLBACK de 102000 borra
   tambien la columna `accion_pendiente`, despues de restaurar el WITH CHECK de MAIN de `reportes_contenido_insert_auth`).
   Los cierres historicos (Ktor viejo: accion ANTES del PATCH con `revisado_en`) leen la marca en false y nunca se reanudan.
   La 102000 estrecha ademas la politica `reportes_contenido_insert_auth`: un cliente autenticado solo inserta un reporte
   pendiente propio con las columnas del servidor en su default (Ktor escribe como service_role: sin efecto en Ktor).
   Su PREFLIGHT falla cerrado (`REPORT_ACTION_PREFLIGHT`) si `reportes_contenido` tiene otra politica permisiva de
   INSERT, UPDATE, DELETE o ALL: "ningun cliente escribe la marca" se comprueba al aplicar, no se supone.
   Tras la cadena COMPLETA de ROLLBACK la huella esperada es 54/55 (cuerpos de `handle_new_auth_user` y `record_event`,
   solo espacios en blanco; `SLICE_1_PRODUCTION_GATE.md`, seccion 8); cualquier otra diferencia es un fallo.
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
roll forward). `20260930100900`, `20260930101500` y `20260930101600` son compatibles y reversibles (la 101600 solo anade un overload: se revierte el Ktor de loop 2 ANTES de retirarla). `20260930101730` (indice unico parcial de `tickets_grant`, MASTER3-OBS-002) es aditiva y compatible con cualquier Ktor; su rollback solo borra el indice. `20260930101850` (MASTER4-OBS-001) solo anade `admin_sync_roles_audited`: compatible con el Ktor de loop 2/3; se revierte el Ktor de loop 4 ANTES de retirarla. `20260930101900` (MASTER6-IDEM-003) solo anade la sobrecarga de 4 argumentos de `admin_sync_roles_audited` y `20260930102000` (MASTER6-API-001) anade `admin_record_report_action` y la columna `accion_pendiente` y solo ESTRECHA la politica de INSERT de clientes `reportes_contenido_insert_auth`: compatibles con el Ktor anterior (service_role); se revierte ANTES el Ktor que las llama. Clasificacion por fichero, orden y hashes:
`docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` (repo OposiBot). Ninguna se revierte automaticamente.

## Pendiente (registrado, no ejercitado)

- `/health` aun devuelve una cadena `build` fija (`20260512-no-legacy-columns`), por eso el paso 3 verifica la imagen con `docker inspect`; inyectar el SHA es un cambio de codigo de Ktor.
- Nombre de imagen canonico: `oposicontrol-backend:<sha12>` (el servicio/contenedor se llama `oposicontrol-ktor`).
- `/health/ready` solo comprueba Supabase; una sonda de marcador de esquema requiere aprobacion
  del owner del contrato (D8-08 congelado).
- CI (`.github/workflows/ci.yml`) verifica tests, lint OpenAPI y `scripts/verify-ops-config.sh`;
  `dbtest` corre sobre PostgreSQL 16 embebido (zonky). DB-CI-PG17: el job `db-pg17` aplica las migraciones pendientes
  (`supabase/baseline/PENDING_MIGRATIONS.txt` = `2026092810*` + `SLICE1_MIGRATIONS.txt`) sobre PostgreSQL 17 (imagen Supabase
  fijada por digest) cargado SOLO con la forma de MAIN que tocan (`supabase/baseline/main_shape_subset.sql`), con VERIFY = 0,
  `assertions.sql` y rollback/re-aplicacion, mas su control negativo; NO carga el baseline completo de MAIN (decision del owner
  pendiente), asi que el gate local Supabase completo (baseline 55/55, schemadiff, e2e) sigue siendo el gate previo al despliegue.
  Cobertura y limites: `supabase/baseline/README.md`. CI-OC-RELEASE: el job `desktop-release-linux` construye
  `:composeApp:createDistributable` y exige java.sql/java.management/java.instrument/jdk.unsupported/jdk.crypto.ec en el runtime
  embebido (`scripts/verify-desktop-runtime-modules.sh`); MSI (Windows), DMG y el release Android (R8) siguen fuera de CI.

## Notas de la ronda 3 (remediacion completa, 2026-10-01)

- Migraciones `20260930101900` y `20260930102000`: son NUEVAS y no estan publicadas, asi que se enmendaron EN SITIO (respecto al historial publicado siguen siendo solo hacia delante). Sus hashes de `SLICE_1_PRODUCTION_GATE.md` se calculan UNA vez sobre el blob del commit a desplegar (`NEW_SHA`); antes de desplegar se repite el gate PG17 local completo. La 101900 ya no lee `auth.users` (delega en la guarda de `admin_sync_roles(uuid, uuid[])`); la 102000 usa la columna `reportes_contenido.accion_pendiente` y no una clave de tiempo.
- Ktor viejo y la 102000 (hechos verificados con `git show main:`): `resolverReporte` viejo insertaba la accion PRIMERO y despues hacia el PATCH con `revisado_en` (reloj de Ktor), sin guarda de estado; por eso todo cierre historico lee `accion_pendiente = false` y nunca se reanuda. Parar el Ktor viejo ANTES de migrar y no rearrancarlo: un Ktor viejo que re-resolviera un reporte en vuelo anadiria una accion sin limpiar la marca.
- `OPOSICONTROL_ENABLE_BACKEND_RESTART=true` ya no habilita el reinicio fuera de un `APP_ENV` de desarrollo y `/health` solo publica `restartEnabled` en desarrollo. Tras desplegar el Ktor nuevo, comprobar que `/health` no lleva esa clave y quitar la variable del entorno del servidor.
- Los jobs `db-pg17`, `desktop-release-linux`, la deriva de `verification-metadata.xml` y las pruebas POSIX solo se ejercitan en el CI de Linux; hasta su primera ejecucion no cuentan como prueba (B-28 en `SLICE_1_OPEN_BLOCKERS.md` de OposiBot).
