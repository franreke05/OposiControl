# Ventana servidor 1.3 — orden de despliegue consolidado (2026-11-14)

Rama local `integ/v1-3-server` desde develop `08ea7f0f` (= origin/develop = código de producción). Sin push, sin merge a develop, sin despliegue. MAIN/VPS sin tocar. Plan aprobado: `ancient-baking-melody.md`.

Ramas fusionadas, en este orden (todas con `--no-ff`): `f20b/reto-tres` (`c8b86c0f`), `f14b/demo-aleatoria` (`17d554c9`), `f23/quick-quiz` (`a230ce78`, incluye F23 y F23b/FA47b), `f27/stats-fix` (`c1278113`), `f25/news-app` (`34e404db`), `f29/avatar-preset` (`4f16c7a7`), `f30/biblioteca-desde` (`e1d9b0e6`), `f28/consumibles` (`540e540f`). Más una migración correctiva nueva de esta integración: `20261114500000_v1_ranking_lotes_shape` (hallazgo S3, abajo).

## Conflictos resueltos

- `supabase/V1_MIGRATIONS.txt`, `supabase/baseline/PENDING_MIGRATIONS.txt`: unión, en orden de versión (el gate exige orden y sin duplicados).
- `backend/ktor/src/test/resources/contract-freeze/freeze-manifest.sha256`: la cabecera junta las enmiendas de cada rama (cada una inserta su "plus FAxx" antes de la frase final) y los hashes se recalculan del `openapi/` fusionado (mismo cálculo que `OpenApiFreezeManifestTest`: sha256 con LF). Fichero nuevo: `openapi/news/news-app.yaml`.
- `openapi/root.yaml`: rutas de práctica (F23) y de noticias de la app (F25), ambas.
- `openapi/attempts/attempts.yaml`: la descripción de D2-03 conserva el texto FA48 de F14b e inserta el FA51b de F30; `AttemptStart.preguntas` (FA51b) y los esquemas `QuickQuiz*` (FA47/47b), ambos.
- `OpenApiContractTest` (idempotencia REQUIRED: D2-68 y D6-40), `ServerAuthoritativeTest` (comandos `QuickQuizStartRequest` y `AvatarPresetRequest`).
- dbtest: `V1AutoDemoMigrationTest` (relleno: deja fuera F14b, F23, F23b y F27, que se apoyan en auto_demo) y `V1PracticaMigrationTest` (control negativo: deja fuera F23b y F27).
- `supabase/baseline/assertions.sql`: el bloque F27 se cierra con su propio `rollback;` y el de F28 va detrás.
- `ops/inventory/route-profiles.tsv`: filas de noticias (F25), Market (F28) y práctica (F23). `routes.*.txt` se fusionaron solos; regenerados con `INVENTORY_UPDATE=true ... RouteInventoryTest`: sin diferencias.
- **Choque de identificador de contrato (lo detecto `OpenApiContractTest`, `x-contract-id` unico):** F25 y F30 usaban las dos `D4-27` (detalle de noticia de la app / pregunta guardada de la Biblioteca). Se queda `D4-27` = noticia (F25: el catalogo del cliente OposiBot ya la cita asi) y la pregunta guardada pasa a **`D4-29`** (`openapi/opositeca/opositeca.yaml`, `SavedItems.kt`, `SavedItemsRoutesTest`, `OpenApiContractTest`, `route-profiles.tsv`, acta F30, cabecera del manifiesto de congelado). `D4-28` (recurso de la pregunta guardada) no cambia. Solo etiqueta: ninguna ruta ni forma cambia.
- `docs/brain/CURRENT_STATE.md`, `docs/engineering/DEPLOYMENT.md`: todas las entradas; quitada la sección F20b duplicada que traía `f27/stats-fix`.

## Hallazgo S3: `lotes_actualizacion_ranking` en MAIN (migración nueva `20261114500000`)

La captura de MAIN (2026-10-14, `baseline_main.sql` sha256 `07acead5…`) tiene la forma PRE-V1 de la tabla: `(id, liga_id, estado default 'pendiente' CHECK pendiente/ejecutando/completado/fallido, iniciado_en NULL sin default, finalizado_en, total_usuarios_procesados, mensaje_error, creado_en)`. Ninguna migración V1 la altera. Los cuerpos V1 de `app_job_dificultad` (101600), `app_job_ranking` (101700) y `admin_recompute_stats` usan `tipo`, `filas`, `detalle`, estado `en_curso`/`ok` e `iniciado_en` con default (forma del overlay local). Se crearon sin error en la ventana del 2026-10-22 porque plpgsql no resuelve columnas al crear. Consecuencia en esa forma: el `DailyChallengeJob` de Ktor llama a los dos trabajos en cada vuelta y ambos fallan (`42703`, columna inexistente): sin refresco de dificultad ni posiciones de ranking. El ensayo combinado lo reproduce (sonda ANTES `ERR:42703 ERR:42703`).

Valoración: corrección pequeña, aditiva y con guardas → se añade en esta ventana. Solo actúa si existe la columna marcador `total_usuarios_procesados` (forma de MAIN): añade `tipo` (nullable: las filas antiguas no lo tienen; todo escritor V1 lo fija), `filas`, `detalle jsonb not null default '{}'`, `error`; defaults `estado = 'en_curso'` e `iniciado_en = now()`; el CHECK de estado conserva los cuatro valores antiguos y admite `en_curso`/`ok`/`error`. No reescribe datos. En la forma V1 (gate, dbtest) es un no-op. ROLLBACK (solo en la forma de MAIN): borra las filas escritas por los trabajos V1 (`tipo is not null`), quita las cuatro columnas y repone CHECK y defaults; después los trabajos vuelven a fallar como hoy. Pendiente de confirmar en MAIN (lectura de solo catálogo, la hace el propietario): que la tabla sigue con la forma de la captura; si ya tuviera la forma V1, la migración no hace nada.

Pruebas: dbtest `V1RankingLotesShapeMigrationTest` (forma V1: no-op y rollback no-op; forma de MAIN recreada con el DDL literal de la captura: control negativo del trabajo, aplicar, VERIFY = 0, trabajos OK, cooldown `RECIENTE`, fila antigua intacta, ROLLBACK = forma de MAIN exacta, re-aplicar); gate PG17 (VERIFY/ROLLBACK/huella); ensayo combinado (sonda de trabajos ANTES/DESPUÉS/tras ROLLBACK).

## Orden de despliegue (lo hace el propietario, con GO expreso)

Antes: inventario como en `2026-11-08-server-deploy-runbook.md` §4 (anotar la imagen que corre hoy en bot-1, bot-2 y control: es la imagen de ROLLBACK) y comprobar que MAIN tiene F20 `20261109100000` registrada (`select max(version) from supabase_migrations.schema_migrations` = `20261109100000`).

### 1. Migraciones a MAIN — TODAS antes de la imagen

Manifiesto consolidado (cadena lineal, cada una exige la anterior): `ops/deploy/v1/main-migrations-2026-11-v1-3.manifest`. Para cada fila, en este orden, teclear la versión cuando el script la pida y esperar `aplicada, VERIFY = 0, registrada`:

```
MANIFEST=ops/deploy/v1/main-migrations-2026-11-v1-3.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/<fichero>
```

| # | Versión a teclear | Fichero | Funcionalidad | Manifiesto propio (predecesora técnica) | Blob |
|---|---|---|---|---|---|
| 1 | `20261109100100` | `20261109100100_v1_practica.sql` | F23 OposiQuizzes (FA47) | `main-migrations-2026-11-f23.manifest` (20261109100000) | `cce505461dbd` |
| 2 | `20261109100200` | `20261109100200_v1_reto_tres.sql` | F20b reto siempre de 3 (FA55, antes FA48 duplicado) | `main-migrations-2026-11-f20b.manifest` (20261109100000) | `2823abf889be` |
| 3 | `20261111100000` | `20261111100000_v1_demo_aleatoria.sql` | F14b demo aleatoria fija (FA48) | `main-migrations-2026-11-f14b.manifest` (20261109100000) | `09d756281e34` |
| 4 | `20261114100000` | `20261114100000_v1_avatar_preset.sql` | F29 avatares predefinidos (FA53) | `main-migrations-2026-11-f29.manifest` (20261109100000) | `26061b231391` |
| 5 | `20261114200000` | `20261114200000_v1_biblioteca_pregunta.sql` | F30 pregunta guardada (FA50) | `main-migrations-2026-11-f30.manifest` (20261109100000) | `f05303781b2b` |
| 6 | `20261114200100` | `20261114200100_v1_practica_examenes.sql` | F23b práctica de exámenes elegidos (FA47b) | `main-migrations-2026-11-f23b.manifest` (20261109100100) | `0a8157b5328f` |
| 7 | `20261114300000` | `20261114300000_v1_stats_fix.sql` | F27 estadísticas (FA54) | `main-migrations-2026-11-f27.manifest` (20261109100100) | `5d904c99a3f3` |
| 8 | `20261114400000` | `20261114400000_v1_servicios_catalogo.sql` | F28 servicios/Market (FA52) | `main-migrations-2026-11-f28.manifest` (20261109100000) | `3628dd8594bb` |
| 9 | `20261114400100` | `20261114400100_v1_servicios_canje.sql` | F28 canje con OT | `main-migrations-2026-11-f28.manifest` (20261114400000) | `ddb751bf0ccc` |
| 10 | `20261114400200` | `20261114400200_v1_consumibles_efectos.sql` | F28 consumibles en el reto | `main-migrations-2026-11-f28.manifest` (20261114400100) | `d50853b0a4ac` |
| 11 | `20261114500000` | `20261114500000_v1_ranking_lotes_shape.sql` | forma de lotes del ranking (S3) | `main-migrations-2026-11-lotes.manifest` (20261022101700) | `7608aa1990e8` |

Por qué este orden: es el orden de versión, el MISMO en que el gate PG17, `backend/dbtest` y el ensayo combinado aplican las migraciones (lo probado). Cumple todas las predecesoras técnicas de los manifiestos por funcionalidad (F23 antes que F23b y F27; F28 en su cadena 400000→400100→400200). Difiere del orden de fusión de ramas (F20b, F14b, F23…) solo en que F23 (`…100100`) va antes que F20b (`…100200`): no comparten objetos (F23 redefine el arranque de intento y la práctica; F20b solo `private.ensure_challenge_row`). Los manifiestos por funcionalidad siguen siendo válidos para aplicar una suelta, pero en esta ventana se usa el consolidado.

Compatibilidad durante la ventana (imagen vieja + base nueva): según cada acta, la imagen de hoy sigue funcionando con cada migración aplicada (F28: la respuesta del reto con 4 argumentos con nombre encaja con la de 5, cuyo 5º tiene default; F29: la imagen actual ignora `avatar_id`; F27, F20b, F14b, lotes: solo base). Efectos visibles ya con la imagen vieja: F20b (reto de 3), F14b (demo aleatoria fija), F27 (relleno de estadísticas y nota media penalizada), lotes (los trabajos de dificultad y ranking empiezan a funcionar en la siguiente vuelta del job).

Sobrecarga del reto (aviso S4): tras la ventana existe exactamente UNA `public.app_answer_daily_challenge`, la de 5 argumentos `(uuid, uuid, uuid, uuid, boolean)`; 400200 hace `drop function if exists ...(uuid, uuid, uuid, uuid)` antes de crearla y ninguna migración posterior la redefine. Comprobado en el ensayo combinado (sonda DESPUÉS). Cualquier `create or replace` futuro debe usar la firma de 5.

### 2. Imagen Ktor nueva — UNA, después de las 11

Desde `integ/v1-3-server` (o desde develop cuando se fusione, mismo árbol): `bash ops/deploy/release.sh build` (solo con este comando; usa `scripts/build-backend-image.sh`) y despliegue rodante bot-1, bot-2, control como en `2026-11-08-server-deploy-runbook.md` §6, actualizando `BACKEND_IMAGE` en `compose.env`. La imagen nueva necesita la base nueva (F23/F23b: rutas D2-67/D2-68 y `examenes_adquiridos`; F29: `avatar_id` en `/me`; F30: `app_saved_question_detail`; F28: D6-39/D6-40 y `usarOportunidad`); F25 (noticias D4-18/D4-27) no tiene migración. Sin variables de entorno nuevas.

### 3. Panel de Control

Después de la imagen, el panel nuevo (F28: `visible`/`posicion`/sección de servicios). Un panel anterior sigue funcionando (el servidor conserva lo guardado).

### 4. Rollback

Imagen anterior primero; después los bloques ROLLBACK en orden INVERSO (11 → 1) y `delete from supabase_migrations.schema_migrations where version = '<v>'` tras cada uno. Lo que se queda (datos): contadores rellenados por F27, demo sets fijados por F14b, activaciones gastadas por F28, avatares predefinidos elegidos (se pierden con el ROLLBACK de F29). El ensayo combinado prueba el ROLLBACK inverso completo con huella == punto de partida.

## Verificación (árbol fusionado)

- `:backend:ktor:test`: 2577 pruebas, 0 fallos, 1 omitida (arbol final, tras D4-29 y la huella FA51b).
- `:backend:dbtest:test`: 421 pruebas, 0 fallos (incluye `V1RankingLotesShapeMigrationTest`, 2).
- `:features:store:jvmTest` 143 y `:data:jvmTest` 299: 0 fallos.
- `scripts/db-pg17-gate.sh`: `db-pg17-gate OK` (55 pendientes, VERIFY = 0, assertions, ROLLBACK inverso == bootstrap, carreras). `db-pg17-gate.test.sh`: OK (20 escenarios).
- Ensayo combinado `rehearse-v1-3-2026-11.sh` sobre la captura de MAIN: `V1-3-2026-11-REHEARSAL OK` (11 aplicadas con el script y el manifiesto consolidado, VERIFY = 0; negativas STOP; sondas F27 y de trabajos ANTES/DESPUES/tras ROLLBACK; una sola sobrecarga de 5 argumentos; ROLLBACK inverso == punto de partida; re-aplicar == primera aplicacion).
- `verify-migrations-manifest.sh` OK (V1: 51) + test (47 escenarios); `verify-ops-config.sh` OK + test: OK (106 mutaciones); `redocly lint` valido; inventario de rutas regenerado sin diferencias.

## FA51b: idempotencia de D2-03 con la pagina en linea (peticion del coordinador)

Antes, `incluirPreguntas` / `desde` no formaban parte de la huella de `Idempotency-Key`: la misma clave con otra consulta repetia en silencio la primera respuesta. Ahora la pagina pedida, en forma semantica (sin pagina o `incluirPreguntas=false` = arranque normal, huella de siempre; `incluirPreguntas=true` = `desde=1`; `desde=N`), se anade a la huella: misma clave + misma consulta = repeticion (`Idempotent-Replayed: true`, cuerpo guardado, el comando no se ejecuta); misma clave + otra pagina = 422 `IDEMPOTENCY_KEY_REUSED`. Documentado en `attempts.yaml` (solo texto, nota FA51b-idem en el manifiesto de congelado). Prueba: `AttemptRoutesTest.theInlineQueryIsPartOfTheIdempotencyFingerprint`.

## Arreglos de la revision Control (2026-11-14)

Migraciones de la ventana enmendadas EN SU SITIO (ninguna esta en MAIN): F20b, F14b, F30, F27, F28 canje y efectos y lotes; blobs nuevos en la tabla de arriba y en los manifiestos (consolidado y por funcionalidad). Detalle, pruebas y contrato para el cliente: `docs/ledger/2026/11/2026-11-14-v1-3-review-fixes.md`. El orden de despliegue NO cambia.

## Dudas abiertas

- ~~FA48 duplicado~~ RESUELTO 2026-11-14 (revision Control): F20b pasa a FA55, F14b conserva FA48 (texto original abajo).
- FA48 duplicado: F20b y F14b registran ambas su enmienda de contrato como FA48 (cada rama eligió el siguiente libre por separado). En `freeze-manifest.sha256` y en los YAML quedan las dos con el mismo número; renumerar una (p. ej. F14b → FA55) en CONTRACT_FREEZE.md (repo OposiBot) y aquí es decisión del propietario.
- `20261114500000`: confirmar con una lectura de solo catálogo en MAIN la forma real de `lotes_actualizacion_ranking` antes de la ventana (la migración es correcta en las dos formas, pero el hallazgo sale de una captura de octubre).
