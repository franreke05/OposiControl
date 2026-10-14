# Stack local del motor de examen (EX16)

Solo local. Sin MAIN, sin secretos de produccion, sin coste. Imagenes ya fijadas en el repo (Postgres de Supabase
17.6.1.171 del gate PG17, PostgREST v16.4, Storage v1.79.36, nginx).

```
node ops/local-exam/up.mjs            # crea/reutiliza el stack; --reset lo rehace desde cero
node ops/local-exam/run-ktor.mjs      # Ktor de este worktree en http://127.0.0.1:8089 (--no-build para no recompilar)
node ops/local-exam/run-ktor.mjs --detach   # igual, en segundo plano e independiente de la terminal (log .local/ktor.log)
node ops/local-exam/run-ktor.mjs --stop     # para el de --detach
node ops/local-exam/e2e.mjs           # E2E: versionado X->Y, seguridad, recursos, concurrencia, metricas (necesita Ktor recien arrancado: el limitador es en memoria)
docker compose --env-file ops/local-exam/.local/compose.env -f ops/local-exam/docker-compose.yaml exec -T db \
  psql -U postgres -h localhost -X -f - < ops/local-exam/sql/95_explain_volume.sql   # volumen sintetico + EXPLAIN
docker compose --env-file ops/local-exam/.local/compose.env -f ops/local-exam/docker-compose.yaml down -v   # borrar todo
```

| Pieza | Donde |
|---|---|
| Pasarela (rest, storage, JWKS) | `http://localhost:54321` (`/rest/v1`, `/storage/v1`, `/auth/v1/.well-known/jwks.json`) |
| Postgres | `127.0.0.1:54329`, usuario `postgres` / `postgres` |
| Esquema | `supabase/baseline/main_shape_subset.sql` + `sql/00_overlay_main_objects.sql` (objetos de MAIN fuera del subconjunto) + TODAS las migraciones de `supabase/baseline/PENDING_MIGRATIONS.txt` en orden, `ON_ERROR_STOP` |
| Fixtures | `sql/90_fixtures.sql` (usuarios A/B, examen normal, razonada, inmersiva imagen (PNG 1200x800 generado por `up.mjs` y creado por la ingesta, recurso 5e...07), inmersiva audio con envolvente, recurso invalido pdf, audio ausente, premium bloqueado, examen de 200) |
| Cartera y derechos (PHASE B) | `sql/01_overlay_wallet_entitlement.sql` (tablas de D6-01/D6-02/D7-02 fuera del subconjunto) + `sql/92_phase_b_wallet_entitlement.sql` (A: 35 tickets, 5 movimientos de 5 categorias, potenciador activo, creditos razonado 5/2, sin premium; B vacio; P premium activa -> SUBSCRIPTION). Idempotentes: `up.mjs` los aplica siempre, sin reset |
| Idempotencia | `sql/02_overlay_idempotency_finalize.sql`: `idem_complete`/`idem_release` (el subconjunto solo trae `idem_begin`) |
| Revision Y | `sql/91_publish_revision_y.sql` (edicion simulada de EX_NORMAL tras empezar un intento; la autoria real es protegida) |
| Claves, JWT, env | `.local/` (gitignored): `ktor.env`, `tokens.json` (JWT ES256 de A, B y P, 12 h), `secrets.json` |

Los JWT de usuario se firman con una clave EC local que la pasarela publica como JWKS: Ktor los verifica igual que en
produccion (ES256, `iss = <SUPABASE_URL>/auth/v1`). La clave de servicio es un JWT HS256 local que PostgREST y Storage
aceptan. `APP_ENV=local` habilita el limitador en memoria.

Stub de Auth (E5-I01, decision del owner 2026-10-14): la pasarela responde `GET /auth/v1/user` (lo que Ktor pide en
`GET /v1/auth/me` y `GET /api/v1/app/me`) con njs (`auth-stub.js`, `nginx.main.conf`), SOLO desde los claims de un JWT ES256
firmado por la clave local del JWKS de este stack (firma, `kid`, `iss`, `exp` y `role` verificados; cualquier otro token = 401).
No emite tokens ni concede nada: no hay login, registro, refresh ni ninguna otra ruta `/auth/v1/*` (404; PUT = 405). Escucha solo
en 127.0.0.1 y solo existe en ops/local-exam: nunca en la imagen de Ktor ni en produccion. Los JWT de `tokens.json` llevan `email`
y `app_metadata.providers` como los de GoTrue. Prueba: `e2e.mjs` escenario AUTH_STUB.

Fixtures binarios (E5-A1): `e2e.mjs` DECODIFICA de verdad la imagen (PNG 1200x800), el audio (PCM 2 s), el audio lento (30 s) y el
video (H.264 160x120) con el ffmpeg/ffprobe de la imagen local `oposicontrol-backend` (el mismo de produccion), no solo la cabecera.

Limites: no hay GoTrue (los JWT se acuñan aqui; solo el stub de /auth/v1/user), no hay Valkey (limitador en memoria), no hay Caddy. Los triggers de
AUTORIA de MAIN no estan en el subconjunto: el contenido de los fixtures se siembra con `session_replication_role = replica`.

## Un solo worktree arranca el stack

`docker compose` recrea los contenedores si cambian `.local/secrets.json` o las rutas de los ficheros montados, y la base NO tiene volumen: lanzar `up.mjs` desde otro worktree (otro `.local/`) BORRA la base. Lanza `up.mjs` solo desde el worktree que posee el stack (STACK_LOCK.md) y, si cambias de worktree, copia antes su `.local/secrets.json`.

## STACK_LOCK (obligatorio)

`up.mjs`, `run-ktor.mjs` y `e2e.mjs` llaman a `stack-lock.mjs` antes de tocar nada: si existe `STACK_LOCK.md` (por defecto `%LOCALAPPDATA%\OposiBotPhaseB\handoffs\STACK_LOCK.md`, o `STACK_LOCK_FILE`) y su `HOLDER:` no es `STACK_HOLDER`, salen con codigo 3 sin escribir. Para trabajar: crear el bloqueo con tu `HOLDER`, exportar `STACK_HOLDER=<tu nombre>`, y borrar el bloqueo al terminar.
