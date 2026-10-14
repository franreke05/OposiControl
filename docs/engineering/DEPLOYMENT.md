# Deployment

Estado: `NOT_VERIFIED` (runbook escrito, aun no ejercitado; ningun despliegue realizado desde este repo).
El despliegue lo ejecuta un humano con el gate de produccion; CI solo VERIFICA (tests, build y humo de la imagen, db-pg17 en PostgreSQL 17...) y nunca despliega ni toca MAIN. El gate de produccion de OposiBot (`SLICE_1_PRODUCTION_GATE.md`) es la autoridad unica de clasificacion y rollback de las migraciones.

## Orden obligatorio: parar el Ktor viejo, migrar, luego desplegar

0. (MASTER6-CICD-001, RC-14) Ventana de mantenimiento: la clase de CADA migracion 1-16 (una sola por migracion) esta en la tabla de la seccion 1 del gate de produccion, la autoridad unica; este runbook solo la resume (EXPAND 1-4 y 9-13; EXPAND-COMPATIBLE 7-8; CONTRACT 5 y 6; HARDENING 14-16, que estrechan privilegios/politicas de clientes; la 13, `20260930102000`, es EXPAND y ademas ESTRECHA la politica INSERT de clientes de `reportes_contenido` sin cambiar de clase) y no puede contradecirla ni contradecir el parrafo Rollback. El Ktor VIEJO
   (previo a Slice 1) queda PROHIBIDO desde la 6 (contract; cadena 6-9, gate de produccion, seccion 2). Por tanto, ANTES del paso 1 se PARA el Ktor viejo
   (`docker stop oposicontrol-ktor`; Caddy responde 502 durante la ventana) y no se rearranca esa imagen: el siguiente arranque es el
   Ktor nuevo del paso 2. Anotar en el registro del gate la hora de parada y de arranque nuevo (ventana de caida ACEPTADA, minutos).
   Las migraciones 1-5 y 10-16 son compatibles con el Ktor viejo y no exigen por si solas la parada, pero se sigue el mismo orden por simplicidad. Si algo falla a mitad, manda la regla de roll-forward con ventana maxima (30 minutos desde la parada) de la seccion 9 del gate.
1. Aplicar migraciones de `supabase/migrations/` a la base (con backup/PITR verificado).
2. Desplegar Ktor. Sin las migraciones la pasarela responde 503 (falla cerrado), asi que
   el orden inverso rompe el trafico.
3. Verificar la version desplegada por la imagen, no por `/health` (hoy devuelve una cadena `build` fija):
   `docker inspect <contenedor> --format '{{.Config.Image}} {{index .Config.Labels "org.opencontainers.image.revision"}}'`
   debe dar `oposicontrol-backend:<sha12>` y el commit completo esperado; ademas `/health/ready` = 200.
   MASTER6-CICD-002 (imagen de loop 5+, `/app` de root y no escribible; la construye y arranca en CI el job `backend-image` con variables de relleno (run `37002108682` en verde); queda la comprobacion con el entorno REAL):
   con la imagen construida, comprobar que corre como `app` y que el codigo no es escribible pero `/tmp` si:
   `docker run --rm --entrypoint sh oposicontrol-backend:<sha12> -c 'test "$(id -un)" = app && ! touch /app/x 2>/dev/null && touch /tmp/x'`
   debe terminar con codigo 0; despues arrancar el contenedor con el entorno real y esperar `/health/ready` = 200.
   Slice 1 (migraciones `20260930100000..102300`): orden, hashes y clase de rollback en
   `docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` del repo OposiBot.
   Slice 3 (migraciones `20261008100000..100200`, runtime de examen, y las posteriores `20261008100000..20261010100000`: el intento
   congela tambien las preguntas anuladas, marcadas y sin puntuar, decision del propietario del 2026-10-10; y `20261008100000..20261016100000`: revision
   inmutable de cada pregunta ligada al intento, decision del propietario del 2026-10-14, UNICA pendiente en MAIN segun
   `supabase/baseline/MAIN_MIGRATION_HISTORY.txt`, se aplica ANTES del Ktor de `feature/december-exam-server`; manifiesto `supabase/SLICE3_MIGRATIONS.txt`): se
   aplican DESPUES de todas las de Slice 0/1 y ANTES de desplegar el Ktor de Slice 3. Prechequeo obligatorio en MAIN: cero
   pares (usuario, examen) con mas de un intento `en_progreso` (si no, la 100000 se detiene con
   `SLICE3_OPEN_ATTEMPT_DUPLICATES_GATE` sin aplicar nada). La 100200 (endurecimiento del gate: marcador
   `intentos_examen.creado_por_comando`, RPC redefinidas sin congelado tardio, dos indices de FK) es OBLIGATORIA antes del
   Ktor de Slice 3: ese build lee todo intento filtrando por `creado_por_comando`. Su prechequeo solo INFORMA
   (`SLICE3_HARDENING_PREFLIGHT_REPORT`) de cuantos intentos abiertos no llevan el marcador (esperado en MAIN: 0; los que
   haya dejan de ser visibles para las rutas de Slice 3). Rollback: bloque ROLLBACK de la 100200, despues el de la 100100 y
   despues el de la 100000 (si solo se revierte la 100200, re-aplicar la 100100 para recuperar los cuerpos anteriores de las RPC).
   Slice 2 (migraciones `20261008100300`, lectura de "mis examenes" D2-13 `vista=COLECCION`, y `20261008100400`, que retira la escritura directa de clientes sobre `contenido_desbloqueado_usuario`: politica `contenido_desbloqueado_admin_all` y INSERT/UPDATE/DELETE de `anon`/`authenticated`; manifiesto
   `supabase/SLICE2_MIGRATIONS.txt`): solo crea la funcion de LECTURA `public.app_my_exams_collection` (STABLE, SECURITY
   DEFINER, EXECUTE solo `service_role`); ni tablas, ni indices, ni filas. Se aplica DESPUES de la 100200 (su PREFLIGHT
   `SLICE2_MY_EXAMS_PREFLIGHT` se detiene sin `intentos_examen.creado_por_comando`) y ANTES de desplegar el Ktor que la
   llama: sin ella ese build responde 503 `UPSTREAM_UNAVAILABLE` en `vista=COLECCION` (no hay respaldo parcial); `EN_CURSO`
   y `ADQUIRIDOS` no la usan. Su prechequeo solo INFORMA (`SLICE2_MY_EXAMS_PREFLIGHT_REPORT`) de si existen los dos indices
   de MAIN en los que se apoya (`idx_contenido_desbloqueado_usuario_usuario_id`, `idx_intentos_examen_usuario_examen`;
   esperado: 2). Rollback: su bloque ROLLBACK (borra la funcion), antes que los de Slice 3.
   Slice 7 (migracion `20261008100500`, indice keyset `idx_transacciones_cartera_usuario_creado_id` sobre `transacciones_cartera (usuario_id, creado_en desc, id desc)` para D6-02 `GET /api/v1/app/wallet/movimientos`; manifiesto
   `supabase/SLICE7_MIGRATIONS.txt`): solo crea ese indice (SHARE lock breve: el libro es pequeno); ni columnas, ni politicas, ni filas. Es independiente del orden de despliegue del Ktor (la lectura funciona sin el indice, mas lenta). Su prechequeo `WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT`
   se detiene si el indice existe con otra definicion. Rollback: su bloque ROLLBACK (borra el indice), antes que los de Slice 2.

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
  `./gradlew --no-configuration-cache --write-verification-metadata sha256 :backend:ktor:dependencies --configuration runtimeClasspath :composeApp:dependencies --configuration jvmRuntimeClasspath :backend:ktor:installDist :backend:ktor:test :backend:dbtest:test :composeApp:compileKotlinJvm :data:jvmTest :domain:jvmTest :features:users:jvmTest :features:reports:jvmTest :core:database:jvmTest :composeApp:jvmTest :core:common:jvmTest :core:network:jvmTest :core:ui:jvmTest :features:analytics:jvmTest :features:audit:jvmTest :features:auth:jvmTest :features:dashboard:jvmTest :features:exam-access:jvmTest :features:exams:jvmTest :features:forum:jvmTest :features:imports:jvmTest :features:news:jvmTest :features:opositester:jvmTest :features:questions:jvmTest :features:rankings:jvmTest :features:settings:jvmTest :features:specialties:jvmTest :features:store:jvmTest :features:tickets:jvmTest :features:support:jvmTest`
  (si `dependency-audit` audita mas configuraciones se anaden aqui y en CI). `dependency-audit` audita contra OSV el
  `runtimeClasspath` del backend y el `jvmRuntimeClasspath` del cliente admin (`scripts/osv-ignore.json` vacio); el
  `releaseRuntimeClasspath` Android queda como seguimiento y las klibs de iOS fuera de alcance (no son coordenadas Maven).
  El diff debe ser solo de altas: un sha256 distinto para una coordenada existente es una senal de cadena de suministro que se
  investiga, no se acepta. `scripts/verify-ci-jvmtest-coverage.sh` (job `ops-static`, con control negativo) exige que TODO modulo con
  `src/jvmTest` o `src/commonTest` figure como `:modulo:jvmTest` en la linea de pruebas del job `tests`, en la lista de deriva y en
  esta; las excepciones solo van, justificadas, en `scripts/ci-jvmtest-allowlist.tsv` (hoy VACIA: `:features:exams:jvmTest` corre en CI con `-Pci.quarantine=true`, que cuarentena SOLO `ExamWorkspaceResponsiveTest.en1366LaBarraDeGuardarSeVeEnteraYAlBajarElFormularioLlegaASuMinimo`, que falla en Linux y es verde en Windows; DEBT-CI-001 en `docs/registry/TECH_DEBT.md`; el script rechaza cualquier comodin en `excludeTestsMatching`). CI repite ese comando y exige `git diff --exit-code`. Fuera de esa lista quedan iOS, MSI/DMG y el
  release Android: necesitan su propio `--write-verification-metadata` la primera vez que se usen. `:composeApp:createDistributable`
  (jlink + jpackage app-image, CI-OC-RELEASE) ya tiene sus artefactos (generados en Linux) y lo verifica en estricto el job
  `desktop-release-linux`; si cambia la version de Compose se regenera tambien con
  `./gradlew --no-configuration-cache --write-verification-metadata sha256 :composeApp:createDistributable -PoposiProduction=true`.
  `scripts/verify-gradle-drift-negative-control.sh` (job `tests`) es el control negativo FUNCIONAL del paso de deriva: en una copia
  temporal corrompe un sha256 / quita un componente y exige que `git diff --exit-code` falle.
- Flavors Android (INV-NETWORK-COMPLETE): `staging`/`prod` hornean `SUPABASE_URL`/`SUPABASE_ANON_KEY` desde el mismo origen que `dev` (variable
  de entorno o `.env`) y `verifyNonDevSupabaseConfig` hace FALLAR el build (`pre{Staging,Prod}{Debug,Release}Build`) si falta, no es https
  o apunta a localhost, porque `AppConfigResolver` lanza fuera de DEV sin ella. Esos flavors no se construyen en CI (sus BASE_URL siguen
  siendo marcadores `*.local`); `scripts/verify-android-flavor-config.sh` ejecuta el guard y comprueba su cableado. El mismo guard
  cuelga de `createDistributable`/`createReleaseDistributable`/`package*` cuando `-PoposiProduction=true` (APP_ENV=prod): sin
  `SUPABASE_URL` https la distribucion de escritorio NO se empaqueta (antes horneaba `-Doposi.SUPABASE_URL=` vacio). El job
  `desktop-release-linux` pasa una URL publica de relleno (no secreta, solo verificacion); un release real la toma del entorno. El
  script exige el MENSAJE especifico del guard (un fallo de Gradle ajeno no cuenta, con control negativo) y reutiliza el daemon del job.
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
roll forward). `20260930100900`, `20260930101500` y `20260930101600` son compatibles y reversibles (la 101600 solo anade un overload: se revierte el Ktor de loop 2 ANTES de retirarla). `20260930101730` (indice unico parcial de `tickets_grant`, MASTER3-OBS-002) es aditiva y compatible con cualquier Ktor; su rollback solo borra el indice. `20260930101850` (MASTER4-OBS-001) solo anade `admin_sync_roles_audited`: compatible con el Ktor de loop 2/3; se revierte el Ktor de loop 4 ANTES de retirarla. `20260930101900` (MASTER6-IDEM-003) solo anade la sobrecarga de 4 argumentos de `admin_sync_roles_audited` y `20260930102000` (MASTER6-API-001) anade `admin_record_report_action` y la columna `accion_pendiente` y solo ESTRECHA la politica de INSERT de clientes `reportes_contenido_insert_auth`: compatibles con el Ktor anterior (service_role); se revierte ANTES el Ktor que las llama. `20260930102100`, `20260930102200` y `20260930102300` (HARDENING, solo hacia delante, independientes del Ktor porque Ktor escribe como service_role) estrechan privilegios y politicas de clientes: su ROLLBACK de BD (orden inverso 16..1, gate seccion 4) existe pero reabre lo que cerraron, y con la 6 aplicada el rollback del Ktor ya no es una opcion (roll forward con ventana maxima, gate seccion 9). Una sola clase por migracion, la de la tabla del gate. Clasificacion por fichero, orden y hashes:
`docs/integration/production-rollout/SLICE_1_PRODUCTION_GATE.md` (repo OposiBot). Ninguna se revierte automaticamente.

## Pendiente (registrado, no ejercitado)

- `/health` aun devuelve una cadena `build` fija (`20260512-no-legacy-columns`), por eso el paso 3 verifica la imagen con `docker inspect`; inyectar el SHA es un cambio de codigo de Ktor.
- Nombre de imagen canonico: `oposicontrol-backend:<sha12>` (el servicio/contenedor se llama `oposicontrol-ktor`).
- `/health/ready` solo comprueba Supabase; una sonda de marcador de esquema requiere aprobacion
  del owner del contrato (D8-08 congelado).
- CI (`.github/workflows/ci.yml`) verifica tests, lint OpenAPI y `scripts/verify-ops-config.sh`;
  `dbtest` corre sobre PostgreSQL 16 embebido (zonky). DB-CI-PG17: el job `db-pg17` aplica las migraciones pendientes
  (`supabase/baseline/PENDING_MIGRATIONS.txt` = `2026092810*` + `SLICE1_MIGRATIONS.txt` + `SLICE3_MIGRATIONS.txt` + `SLICE2_MIGRATIONS.txt` + `SLICE7_MIGRATIONS.txt`) sobre PostgreSQL 17 (imagen Supabase
  fijada por digest) cargado SOLO con la forma de MAIN que tocan (`supabase/baseline/main_shape_subset.sql`), con VERIFY = 0,
  `assertions.sql` y rollback/re-aplicacion, mas su control negativo; NO carga el baseline completo de MAIN (decision del owner
  pendiente), asi que el gate local Supabase completo (baseline 55/55, schemadiff, e2e) sigue siendo el gate previo al despliegue.
  Cobertura y limites: `supabase/baseline/README.md`. CI-OC-RELEASE: el job `desktop-release-linux` construye
  `:composeApp:createDistributable` y exige java.sql/java.management/java.instrument/jdk.unsupported/jdk.crypto.ec en el runtime
  embebido (`scripts/verify-desktop-runtime-modules.sh`); MSI (Windows), DMG y el release Android (R8) siguen fuera de CI.

## Notas de la ronda 3 (remediacion completa, 2026-10-01)

- Migraciones `20260930101900` y `20260930102000`: HISTORICO (superado por la ronda 8): eran NUEVAS y no estaban publicadas, asi que se enmendaron EN SITIO; HOY estan PUBLICADAS e INMUTABLES (solo hacia delante, sin enmienda en sitio, ni siquiera de comentarios). Sus hashes de `SLICE_1_PRODUCTION_GATE.md` se calculan UNA vez sobre el blob del commit a desplegar (`NEW_SHA`); antes de desplegar se repite el gate PG17 local completo. La 101900 ya no lee `auth.users` (delega en la guarda de `admin_sync_roles(uuid, uuid[])`); la 102000 usa la columna `reportes_contenido.accion_pendiente` y no una clave de tiempo.
- Ktor viejo y la 102000 (hechos verificados con `git show main:`): `resolverReporte` viejo insertaba la accion PRIMERO y despues hacia el PATCH con `revisado_en` (reloj de Ktor), sin guarda de estado; por eso todo cierre historico lee `accion_pendiente = false` y nunca se reanuda. Parar el Ktor viejo ANTES de migrar y no rearrancarlo: un Ktor viejo que re-resolviera un reporte en vuelo anadiria una accion sin limpiar la marca.
- `OPOSICONTROL_ENABLE_BACKEND_RESTART=true` ya no habilita el reinicio fuera de un `APP_ENV` de desarrollo y `/health` solo publica `restartEnabled` en desarrollo. Tras desplegar el Ktor nuevo, comprobar que `/health` no lleva esa clave y quitar la variable del entorno del servidor.
- Los jobs `db-pg17`, `desktop-release-linux`, la deriva de `verification-metadata.xml` y las pruebas POSIX solo se ejercitan en el CI de Linux; se ejecutaron en verde en el CI de Linux (run `37002108682`, SHA `f0e8ee0`; B-28 cerrado para ese SHA y se repite tras la ronda 8).

## Nota de la ronda 6 (decision del owner 4, 2026-10-02)

- Migracion NUEVA `20260930102100_slice1_client_ddl_privileges_revoke.sql` (solo hacia delante, independiente del Ktor): revoca TRUNCATE, TRIGGER y REFERENCES de `anon`/`authenticated` en las relaciones de `public` (en MAIN, 42 tablas para `authenticated` y `noticia`/`tipo_noticia` para `anon`), las registra en `private.slice1_client_ddl_grants_revoked` y su ROLLBACK restaura exactamente lo registrado. Su PREFLIGHT aborta (`CLIENT_DDL_PRIVILEGES_PREFLIGHT`) si un grant no lo concedio el dueno o lleva grant option. Residual: los privilegios por defecto de `postgres`/`supabase_admin` vuelven a conceder todo en una tabla NUEVA; el VERIFY `client_ddl_privilege_effective` la senala; re-ejecutar la migracion la revoca solo si su dueno es `postgres`: una tabla de `supabase_admin` hace que la re-ejecucion falle cerrada en el PREFLIGHT sin revocar nada, y el operador la revoca en una sesion que actue como `supabase_admin` (gate seccion 8, punto 14).

## Nota de la ronda 7 (registro veraz, 2026-10-02)

- NO PROBADO en Linux hasta el primer CI verde (SUPERADO: el run `37002108682` los ejecuto en verde; `jvmRun`/`runDistributable` siguen sin cubrirse): la guarda de la distribucion de escritorio de produccion (`verifyNonDevSupabaseConfig` con `-PoposiProduction=true`; solo mensaje y cableado en seco, `jvmRun`/`runDistributable` no estan cubiertas), la guarda de flavors Android (`scripts/verify-android-flavor-config.sh`; no se construyen en CI) y el control negativo de la deriva de Gradle (`scripts/verify-gradle-drift-negative-control.sh`). No se valida `SUPABASE_ANON_KEY` (la decision del owner cubre la URL).
- Residual de la migracion `20260930102100`: volver a ejecutarla revoca y registra una tabla futura solo si es de `postgres`; una tabla de `supabase_admin` hace FALLAR CERRADA la re-ejecucion (`CLIENT_DDL_PRIVILEGES_PREFLIGHT`, dueno no utilizable) y se corrige en la migracion de esa tabla o por el propietario de la plataforma. MAINTAIN sigue concedido. Detalle: DELTA KO13 de OposiBot.

## Nota de la ronda 8 (R8b-DB, 2026-10-06)

- Migracion NUEVA `20260930102200_slice1_roles_usuario_client_write_revoke.sql` (RC-02, MASTERF1-DB-001; solo hacia delante, independiente del Ktor, despues de la 102100): borra la politica `roles_usuario_admin_all` y revoca INSERT/UPDATE/DELETE de `roles_usuario` a `anon`/`authenticated`. Un admin normal ya no puede darse `superadmin` ni borrar el ultimo superadmin por la Data API; los roles solo se escriben con las RPC SECURITY DEFINER de sincronizacion (Ktor, `service_role`). Lectura propia/admin intacta (`roles_usuario_select_propio`). Antes de aplicar, revisar los titulares de `superadmin`/`admin` (consulta de solo lectura en la cabecera, HISTORICAL DATA).
- Migracion NUEVA `20260930102300_slice1_report_insert_bounds.sql` (RC-08, MASTERF2-DB-002; despues de la 102000): la politica `reportes_contenido_insert_auth` exige ademas `creado_en = now()` y `actualizado_en = now()` (un valor explicito distinto se rechaza con 42501) y dos CHECK de 2000 caracteres en `motivo`/`descripcion`. Si hay filas historicas fuera de rango el CHECK queda NOT VALID (aplica a filas nuevas) y la fila VERIFY informativa `report_text_bound_not_validated` lo dice.

## Nota de la ronda 8 (R8b-CICD, 2026-10-06)

- Estado publicado: las migraciones `20260930100000..102100` estan PUBLICADAS en `origin/feature/canonical-api-slice-1` (SHA `f0e8ee0d5562a5f19383d1806be7cf4bdc2b2006`) y son INMUTABLES: nada se edita (ni comentarios); todo SQL nuevo va en un fichero NUEVO posterior al ultimo del manifiesto. La `20260930102200` y la `20260930102300` lo son desde su primer commit. Se retira cualquier licencia de enmienda en sitio.
- CI verde registrado: OposiControl run `37002108682` (`f0e8ee0`, jobs `ops-static`, `tests`, `dependency-audit`, `secret-scan`, `openapi-lint`, `desktop-release-linux`, `backend-image`, `db-pg17`) y OposiBot run `37002096225` (`bf6f144`). Cubren 1..14; la ronda 8 necesita un run verde nuevo sobre sus SHA (se anota en el gate, seccion 9).
- Roll-forward con ventana maxima: el reloj arranca en `docker stop oposicontrol-ktor`; antes de aplicar la 6 se puede volver a la imagen `rollback-<fecha>`; con la 6 aplicada la imagen vieja no se rearranca nunca (se corrige y se sigue adelante) y la ventana maxima de caida es de 30 minutos (decision escrita a los 15 entre roll forward y rollback completo de BD 16..1, este ultimo solo con `cross_user_shared_keys = 0`); pasada la ventana decide el owner. Detalle: gate de OposiBot, seccion 9.
- Precondicion de gate: el propietario debe haber ratificado FA7 y FA9 (TECHNICAL_CORRECTION; FA8 ya esta APPROVED) o decidido su reversion ANTES del apply en MAIN (gate seccion 9; no bloquea fusionar la rama).
- CI: `:features:exams:jvmTest` entra en la linea de pruebas y en la de deriva de `verification-metadata.xml` (la deriva se regenera EN LINUX en el job `tests`); `scripts/verify-no-direct-supabase.sh` excluye solo los source sets de test exactos (`src/test`, `src/<x>Test`), de modo que `features/opositester` y `core/testing` en `src/commonMain` ya se escanean (RC-03).
