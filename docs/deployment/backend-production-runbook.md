# Runbook de producción: Ktor, Caddy y Supabase

Fecha: 2026-07-29
Alcance: administración completa excepto Noticias.

## Topología

El tráfico público termina en Caddy por HTTPS. Ktor no publica puertos del host
y solo es accesible como `backend:8080` dentro de la red Docker. Supabase Auth
emite la identidad; Ktor valida el JWT y resuelve permisos mediante la RPC
`admin_permissions_for_subject`. La clave de servicio permanece únicamente en
el contenedor backend. El espacio `/v1/auth` de OposiBots V2 también termina en
Ktor, pero queda separado de las rutas administrativas protegidas.

## Variables requeridas

Inyectar desde el gestor de secretos del entorno, nunca desde la imagen:

- `ADMIN_DOMAIN`
- `SUPABASE_URL`
- `SUPABASE_PUBLISHABLE_KEY`, identificador público del proyecto usado por
  Ktor al llamar a Supabase Auth
- `SUPABASE_SECRET_KEY`, solo en el contenedor Ktor
- `SUPABASE_JWT_AUDIENCE`, normalmente `authenticated`
- `OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL`, deep link o URL exacta de la
  pantalla de cambio de contraseña, incluida en la allowlist de Supabase Auth
- `APP_VERSION`

`APPLE_CLIENT_ID` y `APPLE_CLIENT_SECRET` son obligatorias únicamente al
habilitar `POST /v1/auth/oauth/apple`. El secreto se inyecta desde el gestor de
secretos y nunca se guarda en el repositorio ni se registra.

En este Compose, `TRUST_PROXY_HEADERS=true` es seguro porque Ktor solo recibe
tráfico desde Caddy en `backend_internal`. Mantenerlo en `false` al ejecutar
Ktor directamente o detrás de un proxy que no sea de confianza; de lo
contrario un cliente podría falsificar la IP usada por rate limiting y logs.

`OPOSICONTROL_ENABLE_BACKEND_RESTART` debe permanecer en `false`. No registrar
valores de variables, JWT, emails, cuerpos de soporte ni claves.

`SUPABASE_SERVICE_ROLE_KEY` existe en Compose solo como alias server-side de
`SUPABASE_SECRET_KEY` para `NewsGateway.kt`. `NEWS=FROZEN`: no reutilizar ese
alias en código nuevo y retirarlo en el plan específico de Noticias.

`BOOTSTRAP_ADMIN_EMAIL` es una ayuda de inicialización para staging. Invita al
usuario sin contraseña, enlaza `usuario.auth_user_id` y asigna `superadmin` de
forma idempotente. Debe retirarse después del bootstrap: mientras permanezca,
`/health/ready` informa estado degradado. En producción el bootstrap no se
ejecuta.

## Auth pública de OposiBots V2

El namespace público es `/v1/auth`; no exige permisos del panel administrativo
y no debe introducir un bypass global sobre el resto de `/v1`. Expone:

- `POST /v1/auth/login`
- `POST /v1/auth/register`
- `POST /v1/auth/register/profile`
- `POST /v1/auth/recover`
- `POST /v1/auth/reset-password`
- `POST /v1/auth/oauth/google`
- `POST /v1/auth/oauth/apple`

Que el namespace sea público significa que Caddy permite llegar a sus handlers.
Cada operación conserva sus requisitos propios: completar perfil y cambiar la
contraseña validan el token de usuario correspondiente. Las rutas
administrativas continúan exigiendo JWT y permisos.

El contrato se integró desde el handoff fechado el 2026-07-29 porque el
repositorio fuente de OposiBots V2 no está disponible en este workspace. Antes
de congelar la API hay que compararlo con `AuthApi.kt`, `DomainError.kt` y
`AUTH_API_CONTRACT.md` del repositorio vivo. Hasta entonces se mantienen
explícitas estas decisiones provisionales:

- `sessionToken` contiene únicamente el access JWT de Supabase;
  `keepSignedIn` decide la persistencia en el cliente. El handoff no define
  refresh ni logout, por lo que el backend no inventa esos endpoints.
- El registro crea y confirma la identidad en servidor para poder devolver una
  sesión inmediata. Hay que ratificar esta política frente a la verificación
  real de email/teléfono del producto.
- Supabase Recovery es email-only. Un identificador no-email produce el mismo
  `204` indistinguible, pero no envía SMS hasta que producto defina el flujo.

Antes de probar estos endpoints:

1. Aplicar la migración
   `20260729115752_oposibots_v2_auth_profile.sql` y confirmar que sus RPC y
   columnas existen.
2. Habilitar Email/Password, Google y Apple —solo los proveedores que se vayan
   a ofrecer— en Supabase Auth. Configurar allí los Client ID, secretos,
   callbacks y orígenes autorizados de cada entorno.
3. Añadir `OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL` a las Redirect URLs de
   Supabase Auth y comprobar que abre la pantalla de reset de la aplicación.
4. Configurar SMTP propio para staging y producción. El correo de recuperación
   no se considera operativo usando el servicio de prueba de Supabase.
5. Programar la rotación del secreto OAuth de Apple como máximo cada seis
   meses y siempre antes de su caducidad.

## Preparación única

1. Apuntar DNS de `ADMIN_DOMAIN` al host.
2. Abrir TCP `80`, TCP `443` y UDP `443`.
3. Instalar Docker Engine con Compose v2.
4. Instalar Supabase CLI en el equipo de despliegue.
5. Confirmar que `supabase/config.toml` usa la major version PostgreSQL del
   remoto.
6. Guardar un backup y comprobar que puede restaurarse.

## Preflight de base de datos

Ejecutar contra staging y revisar el resultado antes de aplicar migraciones:

```sql
select current_database(), current_user, version();

select schemaname, tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;

select auth_user_id, count(*)
from public.usuario
where auth_user_id is not null
group by auth_user_id
having count(*) > 1;

select usuario_id, role_id, count(*)
from public.user_role
group by usuario_id, role_id
having count(*) > 1;

select usuario_id, examen_id, count(*)
from public.acceso_examen_usuario
group by usuario_id, examen_id
having count(*) > 1;
```

También se deben guardar conteos de todas las tablas administrativas. No
continuar si el backup o el inventario no están disponibles.

## Validación local

```powershell
supabase start
supabase db reset
supabase test db
supabase db lint --level warning

docker compose --env-file .env -f deploy/compose.yaml config
docker compose --env-file .env -f deploy/compose.yaml build backend

$env:ADMIN_DOMAIN = "localhost"
caddy validate --adapter caddyfile --config deploy/caddy/Caddyfile
```

La CLI y Docker no deben conectarse al remoto durante estas validaciones.

## Migración de staging

```powershell
supabase link --project-ref <STAGING_PROJECT_REF>
supabase db push --dry-run
supabase db push
```

Después:

```sql
select code, details, detected_at
from private.migration_guardrail
where resolved_at is null
order by detected_at;

select id, name, public, file_size_limit
from storage.buckets
where id in ('exam-covers', 'exam-resources')
order by id;

select count(*) as questions_with_legacy_backup
from public.pregunta
where legacy_payload is not null;

select count(*) as normalized_options
from public.opcion_pregunta
where deleted_at is null;
```

Todo guardrail abierto bloquea producción. Las tablas UUID/plurales no se
eliminan: primero se confirma quién las consume y se diseña un backfill
específico.

## Despliegue

```powershell
docker compose --env-file .env -f deploy/compose.yaml build --pull backend
docker compose --env-file .env -f deploy/compose.yaml up -d
docker compose --env-file .env -f deploy/compose.yaml ps
docker compose --env-file .env -f deploy/compose.yaml logs --tail 200 backend caddy
```

Comprobaciones:

```powershell
curl.exe --fail --silent --show-error "https://$env:ADMIN_DOMAIN/health/live"
curl.exe --include --request POST "https://$env:ADMIN_DOMAIN/v1/auth/login" `
  --header "Content-Type: application/json" `
  --data '{"identifier":"","password":""}'
curl.exe --include --request POST "https://$env:ADMIN_DOMAIN/admin/backend/restart"
```

La llamada de login vacía debe responder `400` con el envelope
`DomainError.Validation`; demuestra que la ruta pública llega a Ktor sin
realizar un intento de autenticación. La llamada de reinicio debe responder
`404`. Probar además:

- ruta administrativa sin JWT: `401`;
- login, registro y recuperación con una cuenta de prueba;
- Google y Apple con credenciales de cada entorno, si están habilitados;
- recuperación de contraseña desde el correo hasta el cambio efectivo;
- JWT válido sin permiso: `403`;
- JWT autorizado: `2xx`;
- repetición con el mismo `Idempotency-Key`: misma respuesta;
- misma clave con otro cuerpo: `409`;
- carga mayor de 55 MB: `413`;
- recurso premium sin autorización: sin URL pública.

## Observabilidad mínima

- Caddy escribe access logs JSON en stdout y redacta `Authorization` por
  defecto.
- Ktor debe incluir `X-Request-ID`, ruta normalizada, estado, latencia,
  reintentos y actor UUID; nunca PII ni cuerpos.
- Alertar por disponibilidad, latencia p95, `401/403/429/5xx`, fallos Supabase,
  guardrails y elementos pendientes de `private.storage_cleanup_outbox`.
- `/health/live` y `/v1/auth/*` son las superficies públicas. La primera solo
  comprueba el proceso; Auth aplica validación, rate limiting y errores seguros
  por operación.
- `/health/ready` exige JWT y permiso `maintenance`; refleja bootstrap
  pendiente/degradado y dependencias necesarias para operar.

## Rollback

Las migraciones son aditivas. No intentar revertirlas borrando columnas o
tablas.

1. Detener nuevas escrituras administrativas.
2. Volver a la imagen backend anterior por digest.
3. Mantener tablas/columnas nuevas.
4. Si el cambio de bucket privado rompe un consumidor confirmado, restaurar
   temporalmente su acceso mediante una policy limitada; no hacer público todo
   `exam-resources`.
5. Restaurar el backup solo ante corrupción confirmada y con aprobación
   explícita, porque reemplaza datos posteriores al backup.
6. Registrar incidente, ventana temporal y request IDs afectados.

## Promoción a producción

Solo promover cuando:

- migraciones, pgTAP y lint pasan en staging;
- no quedan guardrails abiertos;
- conteos y FKs son coherentes;
- RLS se prueba para superadmin, rol limitado y usuario sin rol;
- ajustes de tickets y accesos pasan pruebas de concurrencia/idempotencia;
- recursos premium son privados;
- migración de OposiBots V2 aplicada, Redirect URLs revisadas y SMTP operativo;
- proveedores OAuth habilitados superan su smoke test, o sus endpoints
  permanecen explícitamente deshabilitados;
- Caddy y la imagen están fijados por versión/digest;
- rollback fue ensayado.
