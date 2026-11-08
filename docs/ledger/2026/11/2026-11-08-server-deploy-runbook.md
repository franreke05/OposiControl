# Despliegue servidor 2026-11 (antes de la APK) — runbook

Estado: `PREPARADO, NO EJECUTADO`. Rama local `integ/server-2026-11` (desde develop `04e5eb5e`), sin push. Preparado por SRV (Claude), que no se conecta a MAIN ni al VPS. Ejecuta el coordinador / propietario con GO.
Decision del propietario 2026-11-08: desplegar estos cambios de servidor ANTES de entregar la APK Android (lunes/martes).

## 0. Alcance

| Rama | Migracion | Contrato | Que cambia en produccion |
|---|---|---|---|
| `f3/avatar` | `20261108100000_v1_avatar` | FA42 | D1-14 `PUT` / D1-15 `DELETE /api/v1/app/me/avatar` (bytes crudos jpeg/png, 1..2.000.000 B); `avatarUrl` firmada (1 h) en `/me`, ranking top3 y autores del foro; bucket PRIVADO `avatars` |
| `f3/avatar` | `20261108100200_v1_onboarding_display_name` | — | `app_completar_onboarding` escribe `nombre_mostrar = username` (sin backfill) |
| `f2/level-ot-today` | `20261108100100_v1_home_level_ot_today` | FA43 | `GET /api/v1/app/racha` anade `liga` y `otHoy` |
| `f14/auto-demo` | `20261108100300_v1_auto_demo` | FA44 (solo texto) | *(pendiente de integrar; ver seccion 9)* |
| `f15/exam-access-type` | `20261108100500_v1_exam_turno_acceso` | FA45 | *(pendiente de integrar; ver seccion 9)* |

Sin variables de entorno nuevas, sin cambios en `ops/deploy/*` ni en Caddy. Imagen nueva para bot-1, bot-2 y control (la misma).
Produccion hoy (CURRENT_STATE, 2026-10-22/27): los tres Ktor en `oposicontrol-backend:0bb7efe02086`, `CONTROL_IMAGE=` vacio; Caddy del host 2.6.2; MAIN con las 40 de la ventana V1 registradas (ultima de la cadena: `20261023100600`).

## 1. Antes del dia (C, local — hecho en esta rama)

- Gate PG17 (`scripts/db-pg17-gate.sh` + `.test.sh`), ensayo de la ventana (`docs/ledger/2026/11/rehearse-server-2026-11.sh`), `:backend:ktor:test`, `:backend:dbtest:test`, `verify-ops-config(.test)`, `redocly lint`, manifiestos. Resultados en el informe SRV (ledger de este dia).
- Manifiesto de la ventana: `ops/deploy/v1/main-migrations-2026-11.manifest` (version -> blob -> predecesora -> clase). Si cambia un blob, se rehace y se repite el ensayo.
- Requisito de `release.sh`: publica SOLO desde `develop == origin/develop`, arbol limpio. Por tanto el propietario fusiona `integ/server-2026-11` en `develop` y hace push ANTES del paso 3 (Claude no hace push). El SHA desplegado = HEAD de develop tras esa fusion; los blobs de migracion deben seguir siendo los del manifiesto (`apply-main-migration.sh` lo comprueba).

## 2. Variables de sesion (propietario, Git Bash)

```bash
export OPOSI_SSH_HOST='opsadmin@49.12.232.217'
export OPOSI_API_URL='https://api.oposibots.es'
export MAIN_DB_URL='postgresql://...session pooler de MAIN...'   # solo en esta terminal, nunca en un fichero
export MANIFEST=ops/deploy/v1/main-migrations-2026-11.manifest
cd /c/Users/franr/AndroidStudioProjects/OposiControl && git fetch origin && git checkout develop && git merge --ff-only origin/develop
```
Docker Desktop arrancado (psql corre en la imagen local `public.ecr.aws/supabase/postgres:17.6.1.171`).

## 3. Comprobaciones previas de solo lectura (P)

1. `bash ops/deploy/release.sh preflight` -> `PREFLIGHT OK: oposicontrol-backend:<sha12>`. Anotar `<sha12>`.
2. Historial de MAIN (SQL Editor, solo lectura) guardado como `main-history.txt`:
   `select version from supabase_migrations.schema_migrations order by version;`
   Esperado: contiene `20261023100600`; NO contiene ninguna `20261108*`.
   `bash ops/deploy/release.sh preflight --applied-list main-history.txt` -> pendientes = las `20261108*` del manifiesto. Lineas `<-- ANTERIOR` esperadas y NO se aplican: `20260730120000`, `20260923103000` (divergencias conocidas de historial) y `20261023100100` (RB-16, clase `never`). Cualquier otra = parar.
3. Bucket `avatars` (Storage, SQL Editor, solo lectura):
   ```sql
   select id, public, file_size_limit, allowed_mime_types from storage.buckets where id = 'avatars';
   select policyname, cmd, roles from pg_policies where schemaname = 'storage' and tablename = 'objects'
     and (coalesce(qual,'') ilike '%avatars%' or coalesce(with_check,'') ilike '%avatars%');
   ```
   Esperado: 0 filas en ambas (Ktor crea el bucket privado en la primera subida, `storageEnsureBucket(public=false)`). **Si el bucket ya existe y `public = true`: NO-GO** hasta ponerlo privado (Storage > Buckets > avatars > Make private): `ensureBucket` acepta un bucket existente sin cambiar su visibilidad. Si hay politicas que dejan escribir a `authenticated`/`anon` en `avatars`: borrarlas antes (los clientes nunca suben directo).
4. Plan de Supabase y consumo (Dashboard > Usage): ver seccion 8. CURRENT_STATE dice plan PRO con backups diarios (2026-10-22); la memoria del equipo habla de FREE (incidente de egress 2026-10-09). Confirmar cual es antes de seguir; los limites de la seccion 8 dependen de ello.
5. Estado del servidor (solo lectura):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}"' | tee docker-ps-antes.txt
   ssh "$OPOSI_SSH_HOST" 'caddy version; docker exec $(docker ps -qf name=valkey | head -1) true 2>/dev/null; echo ok'
   ```
   Esperado: bot-1, bot-2, control `healthy` en `0bb7efe02086` (= imagen de ROLLBACK); Caddy `v2.6.2`. Si la version de Caddy no es 2.6.2, avisar a C antes de seguir (no hay cambios de Caddy, pero el comportamiento de 413 se valido contra 2.6.2, ver 10).

## 4. Copia de seguridad (P)

```bash
ssh "$OPOSI_SSH_HOST" 'cp -a ~/oposibots-stack/env ~/oposibots-stack/env.bak-$(date +%Y%m%d%H%M)'
V1_SHA=<sha12> bash ops/deploy/v1/lanzar-v1.sh backup     # volcado logico de MAIN a ~/.oposibots-gate/backups/ con SHA256SUMS (lee MAIN)
```
(`backup` exige estar en el SHA aprobado y arbol limpio.) Con plan PRO, anotar ademas la hora del ultimo backup diario del Dashboard. Esperado: volcado y SHA256SUMS sin error. NO-GO si falla.

## 5. Migraciones a MAIN (P, GO expreso) — ANTES de la imagen

Orden obligatorio: migraciones primero. Las tres de F3/F2 son aditivas (create or replace de funciones, sin DDL de tablas, sin datos): la imagen vieja `0bb7efe0` sigue funcionando con ellas. Entre este paso y el 6 la imagen vieja reenvia tal cual los campos nuevos (`liga`/`otHoy` en racha y `avatarPath` en ranking/foro, siempre null porque nadie tiene aun avatar propio): inocuo, pero hacer el paso 6 justo despues.
No hace falta parar los Ktor (no es CONTRACT). Cada una en su transaccion, `lock_timeout 5s`.

```bash
for f in supabase/migrations/20261108100000_v1_avatar.sql \
         supabase/migrations/20261108100100_v1_home_level_ot_today.sql \
         supabase/migrations/20261108100200_v1_onboarding_display_name.sql; do
  bash ops/deploy/v1/apply-main-migration.sh --verify-only "$f"    # antes: informativo (lo normal es != 0)
  bash ops/deploy/v1/apply-main-migration.sh "$f" || break         # teclear la version; aplica, VERIFY = 0, registra
done
```
Esperado por cada una: `OK <version>_...: aplicada, VERIFY = 0, registrada.` El script se niega (sin conectar) si el blob no es el del manifiesto, si la predecesora no esta registrada o si ya esta aplicada.
NO-GO / parar: cualquier `FALLO`, `STOP` o `NO-GO`. No aplicar las siguientes. Ver 7.
Despues: repetir la exportacion del historial y `release.sh preflight --applied-list` -> solo las tres lineas `ANTERIOR` conocidas.

## 6. Imagen y despliegue rodante (P, GO expreso)

1. `bash ops/deploy/release.sh build` -> `ok: imagen oposicontrol-backend:<sha12> con jar <hash>` (SOLO con este comando: usa `scripts/build-backend-image.sh` y compara el jar; nunca un `docker build` a mano).
2. `bash ops/deploy/release.sh ship` (dry-run, revisa el plan).
3. `bash ops/deploy/release.sh ship --i-have-the-go` y teclear `<sha12>` -> `~/redeploy-rolling.sh` (bot-1, sano, bot-2, sano, control).
4. Version: `ssh "$OPOSI_SSH_HOST" 'docker inspect bot-1 bot-2 control --format "{{.Name}} {{.Config.Image}}"'` (nombres reales segun `docker ps`) -> los tres en `<sha12>`.
5. `compose.env` del servidor: `BACKEND_IMAGE=oposicontrol-backend:<sha12>` y `CONTROL_IMAGE=` vacio, para que un `compose up` posterior no devuelva la imagen vieja (si `redeploy-rolling.sh` no lo actualiza, lo edita el propietario; copia del paso 4).

## 7. Pruebas de humo (P)

Generales: `bash ops/deploy/release.sh verify` (health/live 200, health/ready 200, /metrics 404 en el borde). Rutas nuevas, con una cuenta de prueba (`+prueba-a`, `+prueba-b`) y su token (`TOKEN_A`, `TOKEN_B`), nunca con datos reales:

| # | Comprobacion | Esperado |
|---|---|---|
| 1 | `GET /api/v1/app/me` (A) | 200; `avatarUrl` presente y `null` |
| 2 | `PUT /api/v1/app/me/avatar` (A), `Content-Type: image/jpeg`, foto de ~200 KB: `curl -sS -X PUT -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: image/jpeg' --data-binary @foto.jpg $OPOSI_API_URL/api/v1/app/me/avatar` | 200 `{avatarUrl, expiresAt}`; la URL abre la imagen (cuadrada, <= 512 px) |
| 3 | Supabase Storage | bucket `avatars` creado, **privado**; objeto `avatars/<uidA>/<sha256>.jpg` |
| 4 | `GET /api/v1/app/me` (A) | `avatarUrl` firmada (no la ruta) |
| 5 | Mismo PUT con `Content-Type: image/png` y cuerpo JPEG | 422 `IMAGE_REJECTED`; con `text/plain` -> 415; sin cuerpo -> 400 |
| 6 | PUT de un fichero de ~2,5 MB | rechazo sin 5xx de Ktor. Con Caddy 2.6.2 el corte de `max_size 2MB` puede salir como 413 o como 502 del borde (ver 10); anotar cual |
| 7 | Segundo PUT con otra foto | 200, URL nueva; el objeto anterior desaparece del bucket |
| 8 | `DELETE /api/v1/app/me/avatar` (A) dos veces | 204 y 204; objeto borrado; `/me` -> `avatarUrl: null` |
| 9 | `GET /api/v1/app/racha` (A) | 200; `actual`, `maxima` y ademas `liga` (`{codigo,nombre,orden}` o `null`) y `otHoy` `{ganados, tope}` |
| 10 | `GET /api/v1/app/ranking` (A) | 200; si hay top3, cada entrada trae `avatarUrl` (null o firmada); nunca `avatarPath` |
| 11 | `GET /api/v1/app/oposired/publicaciones` (B), con un post de A teniendo avatar | `autor.avatarUrl` firmada para A; nunca `avatarPath`; cuenta B ve la foto de A |
| 12 | Onboarding de una cuenta nueva de prueba | `nombre_mostrar` = username elegido (SQL de solo lectura sobre esa cuenta) |
| 13 | Panel de Control: login y abrir un listado | 200 (control tambien cambio de imagen) |
| 14 | Logs (`docker logs --since 15m bot-1 | grep -E 'OC-KTOR-AVATAR|ERROR'`) | sin `avatar signing failed` repetidos ni 5xx |

Abortar y revertir (8) si: `ready` != 200 sostenido, 5xx en `/v1/auth/*` o en las rutas de arriba, o el bucket queda publico.

## 8. Supabase: almacenamiento y egress de los avatares

- Tamano: Ktor re-codifica cada foto a JPEG <= 512x512 sin metadatos (tipicamente 30-90 KB). 1.000 usuarios con foto ~ 50-90 MB de Storage; los objetos sustituidos se borran (mejor esfuerzo; un fallo deja un huerfano sin URL).
- Egress: la app descarga la imagen DIRECTAMENTE de Supabase Storage con la URL firmada (no pasa por el VPS). Cada vista de foro/ranking puede bajar varias fotos. La URL firmada se reutiliza mientras le quede >= la mitad de su hora (cache del proceso), asi que un cliente con cache de imagenes por URL vuelve a bajar cada foto como mucho ~1-2 veces por hora. La APK debe cachear por URL (pedir confirmacion al equipo cliente).
- Plan FREE (si aplica): 1 GB de Storage y 5 GB/mes de egress TOTAL del proyecto (BD + Storage + Auth), sin ampliacion: la cuota se comparte con las portadas, imagenes del foro, recursos y la sonda de readiness (incidente 2026-10-09). Con 5 GB, a ~60 KB por foto son ~80.000 descargas de avatar al mes en total. Revisar Dashboard > Usage > Egress/Storage antes del despliegue y a las 24 h y 7 dias; si el egress se acerca al 80 %, opciones: subir a PRO, bajar el lado a 256 px o alargar la vida de la URL. Plan PRO: 100 GB de Storage y 250 GB de egress incluidos.
- Pendiente conocido (no bloquea): la purga de cuentas borradas no borra `avatars/{uid}/` (deuda de F3); el detalle admin muestra el valor crudo de `avatar_url`.

## 9. Rollback

Imagen: `ssh "$OPOSI_SSH_HOST" 'bash ~/redeploy-rolling.sh oposicontrol-backend:0bb7efe02086'` y `release.sh verify`; restaurar `env.bak-*` si se toco `compose.env`. Valido con las tres migraciones aplicadas (son aditivas; la imagen vieja ignora/reenvia los campos nuevos y no tiene las rutas de avatar: 404).
Migraciones (solo si hiciera falta, con GO, en orden INVERSO, despues de volver a la imagen vieja): el bloque `ROLLBACK-BEGIN/END` de cada fichero (ensayado: devuelve el catalogo exacto, cuerpos incluidos) y borrar su fila de `supabase_migrations.schema_migrations`. Lo que NO se deshace (datos): `avatar_url` guardados y objetos del bucket (nadie los sirve tras el rollback), `nombre_mostrar` escritos por onboardings nuevos.
Forma: extraer el bloque con el mismo `awk` de `apply-main-migration.sh` o copiarlo del fichero quitando el `-- ` inicial; ejecutarlo en el SQL Editor.

## 10. Hallazgos que afectan al despliegue (revision Control)

- Caddy 2.6.2: el avatar va en el pool `@bot` y bajo `@bounded` (`max_size 2MB` = 2.000.000 B, unidades SI, igual que `AVATAR_MAX_BYTES`). Sin cambio de configuracion. En 2.6.x el exceso con `buffer_requests` puede responder 502 en vez de 413 (el mapeo a 413 llego en versiones posteriores): no es un riesgo de seguridad, pero la APK debe comprobar el tamano antes de subir (ya re-escala). Anotar el codigo real en el humo 6.
- Ktor exige `Content-Length` en el PUT (Caddy con `buffer_requests` lo pone); un cliente chunked sin Caddy recibiria 400.
- Si el bucket `avatars` existiera publico, `ensureBucket` no lo corrige (paso 3.3).

## 11. Cierre

C actualiza `docs/brain/CURRENT_STATE.md` con SHA, hora, migraciones registradas y resultado del humo, y pide 24 h de observacion (Grafana/Alertmanager, egress de Supabase).
