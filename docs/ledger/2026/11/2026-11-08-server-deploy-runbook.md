# Despliegue servidor 2026-11 (antes de la APK) — runbook

Estado: `PREPARADO, NO EJECUTADO`. Rama local `integ/server-2026-11` (desde develop `04e5eb5e`), sin push. Preparado por SRV (Claude), que no se conecta a MAIN ni al VPS. Ejecuta el coordinador / propietario con GO.
Decision del propietario 2026-11-08: desplegar estos cambios de servidor ANTES de entregar la APK Android (lunes/martes).

## 0. Alcance

| Rama | Migracion | Contrato | Que cambia en produccion |
|---|---|---|---|
| `f3/avatar` | `20261108100000_v1_avatar` | FA42 | D1-14 `PUT` / D1-15 `DELETE /api/v1/app/me/avatar` (bytes crudos jpeg/png, 1..2.000.000 B); `avatarUrl` firmada (1 h) en `/me`, ranking top3 y autores del foro; bucket PRIVADO `avatars` |
| `f3/avatar` | `20261108100200_v1_onboarding_display_name` | — | `app_completar_onboarding` escribe `nombre_mostrar = username` (sin backfill) |
| `f2/level-ot-today` | `20261108100100_v1_home_level_ot_today` | FA43 | `GET /api/v1/app/racha` anade `liga` y `otHoy` |
| `f14/auto-demo` | `20261108100300_v1_auto_demo` | FA44 (solo texto) | Demo automatica: todo examen publicado ofrece ~5 preguntas (`demo_preguntas`) a quien no tiene acceso. **Cambia DATOS**: `examenes.demo_disponible` pasa a defecto `true` y se ENCIENDE en todos los examenes existentes (exclusion explicita = `false` desde Control). Columna calculada `demo_listo` |
| `f15/exam-access-type` | `20261108100500_v1_exam_turno_acceso` | FA45 | `examenes.turno_acceso` (nullable, CHECK `libre/promocion_interna/estabilizacion/discapacidad`) en D2-01/D2-02/D2-13 (`turnoAcceso`) y en el guardado admin de examen; desplegable "Tipo de acceso" del panel |

Sin variables de entorno nuevas, sin cambios en `ops/deploy/*` ni en Caddy. Imagen nueva para bot-1, bot-2 y control (la misma). Panel de Control nuevo DESPUES de la imagen (F15).

Orden obligatorio: las 5 migraciones ANTES de la imagen. La imagen nueva selecciona `demo_listo` (F14) y `turno_acceso` (F15) por PostgREST: contra una base sin ellas, las lecturas de catalogo D2-01/D2-02/D2-13 fallan (400 de PostgREST -> error del catalogo). La imagen vieja `0bb7efe0` no nombra ninguna de las dos, asi que aplicar primero es seguro.
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
   Esperado: contiene `20261023100600`; NO contiene ninguna `20261108*` (las cinco del manifiesto: 100000, 100100, 100200, 100300, 100500).
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

Orden obligatorio: migraciones primero, en este orden (cadena del manifiesto). Ninguna es CONTRACT; la imagen vieja `0bb7efe0` sigue funcionando con las cinco:
- F3/F2 (100000/100100/100200): create or replace de funciones, sin DDL de tablas ni datos. Entre este paso y el 6 la imagen vieja reenvia tal cual los campos nuevos (`liga`/`otHoy` en racha, `avatarPath` null en ranking/foro): inocuo.
- F14 (100300): ADEMAS de funciones, `alter column demo_disponible set default true` y `update` que enciende la demo en TODOS los examenes. Efecto inmediato incluso con la imagen vieja: los examenes pasan a anunciar DEMO a quien no tiene acceso; la imagen vieja no lee `demo_listo`, asi que un examen con menos de 5 preguntas validas anunciaria DEMO y su arranque fallaria cerrado (sin servir nada) hasta el paso 6. Minimizar la ventana. Antes de aplicar, guardar la foto: `select id, codigo_examen, demo_disponible from public.examenes order by codigo_examen;` (es la unica forma de volver a los valores previos; ver 9).
- F15 (100500): `ADD COLUMN turno_acceso` + CHECK (ACCESS EXCLUSIVE de milisegundos sobre `examenes`, `lock_timeout 5s`: si hay trafico y no obtiene el cerrojo, aborta sin cambios y se reintenta) y re-crea `app_my_exams_collection` con `turno_acceso` (verificado en el ensayo: ninguna migracion posterior redefine esa funcion; el cuerpo final la lleva).
No hace falta parar los Ktor. Cada una en su transaccion, `lock_timeout 5s`.

```bash
for f in supabase/migrations/20261108100000_v1_avatar.sql \
         supabase/migrations/20261108100100_v1_home_level_ot_today.sql \
         supabase/migrations/20261108100200_v1_onboarding_display_name.sql \
         supabase/migrations/20261108100300_v1_auto_demo.sql \
         supabase/migrations/20261108100500_v1_exam_turno_acceso.sql; do
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
6. Panel de Control (F15, desplegable "Tipo de acceso"), SOLO cuando control ya corre `<sha12>`: `bash scripts/publicar-panel-produccion.sh` (por defecto `origin/develop`, que debe ser el mismo SHA) y repartir el instalador. Un panel viejo contra el control nuevo sigue funcionando (no envia `turnoAcceso`); un panel nuevo contra un control viejo recibiria rechazo de clave desconocida al guardar con turno.

## 6b. Datos: canal unico del foro (P, GO expreso) — decision del propietario 2026-11-08

Foro V1 basico (solo texto, sin imagenes ni hilos). En produccion no hay ningun canal activo en `public.categorias_oposired`, asi que `GET /api/v1/app/oposired/categorias` devuelve `[]` y la app dice "El foro no esta disponible". Se crea UN canal general para todas las especialidades.

1. Solo lectura (SQL Editor):
   ```sql
   select id, nombre, slug, activa, solo_lectura, posicion, especialidad_id from public.categorias_oposired order by posicion, nombre;
   select clave, valor_integer from public.configuracion_v1 where clave like 'foro_%' order by clave;
   ```
   Esperado: ningun canal activo; `foro_activo = 1` (lo siembra `20261022101900` con `on conflict do nothing` y es el valor por defecto de `private.config_int('foro_activo', 1)`; si sale `0`, el foro esta apagado: ponerlo a 1 desde el panel, Configuracion V1, con GO).
2. Crear el canal por el endpoint de Control (queda auditado como `foro_canal_creado`), preferentemente desde el panel: **Foro > Canales > Nuevo canal** (`ForumOpsScreen`), con Nombre `General`, slug `general`, activo, no solo lectura, posicion 1, sin especialidad. Equivalente HTTP (token de un administrador de Control):
   ```bash
   curl -sS -X POST "$OPOSI_API_URL/api/v1/admin/foro/canales" -H "Authorization: Bearer $TOKEN_ADMIN" -H 'Content-Type: application/json' \
     -d '{"nombre":"General","slug":"general","activa":true,"soloLectura":false,"posicion":1,"especialidadId":null}'
   ```
   Claves admitidas exactamente: `nombre, slug, descripcion, activa, posicion, especialidadId, soloLectura` (`AdminControlForum.parseChannel`). Si el paso 1 mostro ya un canal con slug `general` inactivo, NO crear otro (`slug` es UNIQUE): `PATCH /api/v1/admin/foro/canales/{id}` con `{"activa":true,"soloLectura":false,"posicion":1,"especialidadId":null}`.
3. Solo si el panel y el endpoint no estan disponibles (sin auditoria; columnas verificadas contra la captura de MAIN 2026-10-14 + `20261022101900`, que anade `especialidad_id` y `solo_lectura`):
   ```sql
   insert into public.categorias_oposired (nombre, slug, activa, solo_lectura, posicion, especialidad_id)
   values ('General', 'general', true, false, 1, null)
   on conflict (slug) do nothing
   returning id, nombre, slug, activa, solo_lectura, posicion, especialidad_id;
   ```
4. Comprobacion: `GET /api/v1/app/oposired/categorias` con una cuenta de prueba con especialidad -> 1 canal `General`. Rollback: `PATCH ... {"activa":false}` (no borrar: las publicaciones lo referencian).

Notas del foro para el humo y para la APK:
- Para publicar, la cuenta debe tener especialidad, mas de `foro_edad_min_horas` = 24 h de antiguedad, y sus 3 primeras publicaciones van a premoderacion (`foro_premoderacion_posts` = 3): con una cuenta recien creada el POST se rechaza o queda "en revision". Usar `+prueba-a/-b` (2026-10-22).
- **Imagenes del foro: NO hay interruptor de servidor.** Las rutas `POST /api/v1/app/oposired/imagenes`, `PUT .../imagenes/{id}/contenido` y `GET .../imagenes/{id}` estan siempre activas si Storage esta configurado; `foro_imagenes_hora` tiene cota minima 1 (`20261022101500`), no se puede poner a 0. Hoy "sin imagenes" depende solo de que la APK no muestre el boton. Si se quiere cierre en servidor hay tres opciones (decision del propietario, no incluidas en este despliegue): (a) `respond @foroimg 404` en Caddy para `/api/v1/app/oposired/imagenes*` (edicion del Caddy del host con copia, `caddy validate` y reload; validar con 2.6.2); (b) una clave `foro_imagenes_activo` nueva (migracion + Ktor); (c) dejarlo y moderar.
- Hilos: la tabla tiene `comentario_padre_id`, pero las funciones V1 del foro no lo usan (comentarios planos): no hay hilos en servidor.

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
| 11 | Tras 6b: `GET /api/v1/app/oposired/categorias` (A) -> `General`; A publica un texto en `General` (puede quedar en revision: aprobarlo en el panel); `GET /api/v1/app/oposired/publicaciones` (B), con A teniendo avatar | `autor.avatarUrl` firmada para A; nunca `avatarPath`; cuenta B ve la foto de A |
| 12 | Onboarding de una cuenta nueva de prueba | `nombre_mostrar` = username elegido (SQL de solo lectura sobre esa cuenta) |
| 13 | Panel de Control: login y abrir un listado | 200 (control tambien cambio de imagen) |
| 15 | `GET /api/v1/app/examenes` (A, sin acceso a un examen de pago con >= 5 preguntas validas de su especialidad, especialidad con `permite_demos`) | 200; ese examen con `accessState: DEMO`; un examen con < 5 preguntas validas sale `LOCKED`, no DEMO |
| 16 | Arranque de demo: `POST /api/v1/app/examenes/{id}/intentos` (D2-03) con `Idempotency-Key: <uuid nuevo>` y cuerpo `{"modo":"DEMO"}` | 200/201 con `esDemo: true` y `totalPreguntas` = 5 (o el `demo_preguntas` de produccion); nunca preguntas fuera del conjunto; no cuenta para ranking/racha |
| 17 | Panel: Examenes > Ambito y clasificacion > "Tipo de acceso" = Promocion interna en un examen de prueba; guardar | 200; `GET /api/v1/app/examenes/{id}` (A) -> `turnoAcceso: "promocion_interna"`; `GET /api/v1/app/examenes` y `/api/v1/app/me/examenes` lo traen igual; volver a "Sin indicar" -> campo ausente/null |
| 18 | Guardado admin con `turnoAcceso: "otro"` | 422 `EXAM_ACCESS_ROUTE_UNKNOWN` |
| 14 | Logs (`docker logs --since 15m bot-1 | grep -E 'OC-KTOR-AVATAR|ERROR'`) | sin `avatar signing failed` repetidos ni 5xx |

Abortar y revertir (8) si: `ready` != 200 sostenido, 5xx en `/v1/auth/*` o en las rutas de arriba, o el bucket queda publico.

## 8. Supabase: almacenamiento y egress de los avatares

- Tamano: Ktor re-codifica cada foto a JPEG <= 512x512 sin metadatos (tipicamente 30-90 KB). 1.000 usuarios con foto ~ 50-90 MB de Storage; los objetos sustituidos se borran (mejor esfuerzo; un fallo deja un huerfano sin URL).
- Egress: la app descarga la imagen DIRECTAMENTE de Supabase Storage con la URL firmada (no pasa por el VPS). Cada vista de foro/ranking puede bajar varias fotos. La URL firmada se reutiliza mientras le quede >= la mitad de su hora (cache del proceso), asi que un cliente con cache de imagenes por URL vuelve a bajar cada foto como mucho ~1-2 veces por hora. La APK debe cachear por URL (pedir confirmacion al equipo cliente).
- Plan FREE (si aplica): 1 GB de Storage y 5 GB/mes de egress TOTAL del proyecto (BD + Storage + Auth), sin ampliacion: la cuota se comparte con las portadas, imagenes del foro, recursos y la sonda de readiness (incidente 2026-10-09). Con 5 GB, a ~60 KB por foto son ~80.000 descargas de avatar al mes en total. Revisar Dashboard > Usage > Egress/Storage antes del despliegue y a las 24 h y 7 dias; si el egress se acerca al 80 %, opciones: subir a PRO, bajar el lado a 256 px o alargar la vida de la URL. Plan PRO: 100 GB de Storage y 250 GB de egress incluidos.
- Pendiente conocido (no bloquea): la purga de cuentas borradas no borra `avatars/{uid}/` (deuda de F3); el detalle admin muestra el valor crudo de `avatar_url`.

## 9. Rollback

Imagen: `ssh "$OPOSI_SSH_HOST" 'bash ~/redeploy-rolling.sh oposicontrol-backend:0bb7efe02086'` y `release.sh verify`; restaurar `env.bak-*` si se toco `compose.env`; volver al panel anterior si se repartio el nuevo. Valido con las cinco migraciones aplicadas (ninguna es CONTRACT; la imagen vieja no tiene las rutas de avatar (404), no lee `demo_listo` ni `turno_acceso`, y la demo encendida por F14 sigue anunciandose con la caveat de < 5 preguntas del paso 5).
Migraciones (solo si hiciera falta, con GO, en orden INVERSO 100500 -> 100000, DESPUES de volver a la imagen vieja: la imagen nueva contra una base sin `demo_listo`/`turno_acceso` rompe el catalogo): el bloque `ROLLBACK-BEGIN/END` de cada fichero y borrar su fila de `supabase_migrations.schema_migrations`.
- 100500 (F15): su bloque restaura el cuerpo de `app_my_exams_collection` y BORRA la columna: se pierden los turnos puestos en Control. Exportar antes: `select id, codigo_examen, turno_acceso from public.examenes where turno_acceso is not null;`.
- 100300 (F14): **NO usar el procedimiento de su cabecera** (bloque + re-aplicar `20261022101000` y `20261022101300` enteras): ensayado, deja `app_record_attempt_answer` con el cuerpo de 101300 y pierde el de `20261022101600` (estadisticas). Usar `docs/ledger/2026/11/rollback-20261108100300-complete.sql` (bloque + solo los cuatro cuerpos sustituidos, copiados de su ultima version; ensayado: huella == punto de partida, cuerpos incluidos). NO apaga la demo encendida: `update public.examenes set demo_disponible = <valor de la foto del paso 5>` (o `false` en todos) con GO.
- 100000/100100/100200: bloques ensayados, devuelven el catalogo exacto (cuerpos incluidos). No se deshace (datos): `avatar_url` guardados y objetos del bucket (nadie los sirve tras el rollback), `nombre_mostrar` de onboardings nuevos.
Forma: extraer el bloque con el mismo `awk` de `apply-main-migration.sh` o copiarlo del fichero quitando el `-- ` inicial; ejecutarlo en el SQL Editor.

## 10. Hallazgos que afectan al despliegue (revision Control)

- Caddy 2.6.2: el avatar va en el pool `@bot` y bajo `@bounded` (`max_size 2MB` = 2.000.000 B, unidades SI, igual que `AVATAR_MAX_BYTES`). Sin cambio de configuracion. En 2.6.x el exceso con `buffer_requests` puede responder 502 en vez de 413 (el mapeo a 413 llego en versiones posteriores): no es un riesgo de seguridad, pero la APK debe comprobar el tamano antes de subir (ya re-escala). Anotar el codigo real en el humo 6.
- Ktor exige `Content-Length` en el PUT (Caddy con `buffer_requests` lo pone); un cliente chunked sin Caddy recibiria 400.
- Si el bucket `avatars` existiera publico, `ensureBucket` no lo corrige (paso 3.3).
- F14 es un cambio de producto visible al aplicar la migracion (todas las demos encendidas), no al desplegar la imagen. Si el propietario quiere excluir algun examen, se marca `demo_disponible = false` en Control despues del paso 5.
- `ops/local-exam/*` (recorridos E2E locales, fuera del gate de CI) siembran examenes sin `demo_disponible` y pueden ver DEMO donde esperaban LOCKED tras F14; no forman parte de la verificacion de este despliegue. Ajustarlos en una tarea aparte.

## 11. Cierre

C actualiza `docs/brain/CURRENT_STATE.md` con SHA, hora, migraciones registradas y resultado del humo, y pide 24 h de observacion (Grafana/Alertmanager, egress de Supabase).
