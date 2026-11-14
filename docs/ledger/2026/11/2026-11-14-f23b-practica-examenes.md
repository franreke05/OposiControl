# F23b — Generar examen: práctica de los exámenes elegidos (2026-11-14)

Rama local `f23/quick-quiz` (sobre F23 `c89d788d`). Sin push. MAIN/VPS sin tocar. Plan aprobado por el propietario: `ancient-baking-melody.md`, sección 2. Cliente: OposiBot (agentes de la app).

## Antes de empezar: pruebas de F23 en rojo

La ejecución completa de `:backend:ktor:test` sobre `c89d788d` daba 3 fallos (2538 pruebas): `ServerAuthoritativeTest` (falta `QuickQuizStartRequest` en la lista de comandos canónicos) y dos de `RouteProfileGateTest` (las dos rutas de práctica no estaban en `ops/inventory/route-profiles.tsv`). Arreglado en un commit propio antes de FA47b. Los fallos citados de `OpenApiContractTest` (x-idempotency D2-68) y `Slice1ContractConformanceTest` ya no aparecían en esa ejecución.

## Decisión (FA47b, aditivo)

- `POST /api/v1/app/practicas` acepta `examenIds` opcional: 1..50 uuids distintos. Cada uno debe estar en el alcance de la práctica de F23 (activo, publicado, de la especialidad del usuario, veredicto FREE / GRANTED / SUBSCRIPTION; los gratuitos cuentan como propios). Si alguno no lo está: 422 `PRACTICE_EXAM_NOT_ACCESSIBLE` y no se crea nada (inexistente, borrador, de otra especialidad o cerrado son indistinguibles). Forma mala (vacío, >50, no uuid, repetidos): 400 `VALIDATION` en `examenIds` (`OUT_OF_RANGE` / `INVALID_FORMAT` / `DUPLICATE`). Elegidos sin preguntas servibles: 409 `EXAM_EMPTY`. Sin `examenIds`: igual que FA47.
- `GET /api/v1/app/practicas/disponibilidad` añade `examenesAdquiridos`: exámenes DISTINTOS con concesión VIGENTE (cualquier motivo), activos, publicados y de la especialidad = el conjunto de `GET /api/v1/app/me/examenes?vista=ADQUIRIDOS` (total del servidor, no una página). Un gratuito sin concesión NO cuenta. El cliente enseña "Generar examen" con 3 o más.
- Sin cambios: cupo diario (cuenta salvo que TODAS las preguntas vengan de exámenes GRANTED), tope 50 inicios / 24 h, `es_demo = true` y `es_practica = true` en el intento de práctica (no se toca el marcado; las funciones de estadísticas tampoco). La elección se guarda en `intentos_examen.metadatos.practicaExamenes`.

## Migración `20261114200100_v1_practica_examenes` (predecesora F23 `20261109100100`)

- Nuevas: `private.practica_examenes(usuario, ahora)` (el alcance), `private.practica_pool_examenes(usuario, ahora, examenes[])` (el pool restringido).
- `public.app_practice_availability(uuid)` devuelve también `examenes_adquiridos`.
- `public.app_start_practice_attempt(uuid, integer)` se sustituye por `(uuid, integer, uuid[] default null)` (la llamada con dos argumentos con nombre de una imagen anterior sigue igual). Resultados nuevos: `INVALID_EXAMS`, `EXAM_NOT_ACCESSIBLE` (+ `examen_ids`).
- ROLLBACK: restaura literalmente los dos cuerpos de F23 y borra las auxiliares. VERIFY: 6 filas.

## Anexo al runbook (lo hace el propietario)

1. Requisito: F20 (`20261109100000`) y F23 (`20261109100100`) aplicadas y registradas en MAIN.
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f23b.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261114200100_v1_practica_examenes.sql` → `aplicada, VERIFY = 0, registrada`.
3. DESPUÉS, imagen nueva de Ktor (la misma que F23; build con el script de siempre). La imagen nueva exige `examenes_adquiridos` (sin la migración, D2-67 responde 502).
4. Rollback: imagen anterior primero; luego el bloque ROLLBACK y `delete from supabase_migrations.schema_migrations where version = '20261114200100'`.

## Verificación

Ktor (`AttemptRoutesTest` FA47b: disponibilidad, elección enviada a la base, validación previa, 422, 409; `ServerAuthoritativeTest`, `RouteProfileGateTest`, `OpenApi*`, `RouteInventoryTest`), dbtest `V1PracticaExamenesMigrationTest` (+ controles negativos de `V1PracticaMigrationTest` y `V1AutoDemoMigrationTest` dejan fuera también esta migración), gate PG17 y ensayo `docs/ledger/2026/11/rehearse-f23b-2026-11.sh`. Resultados en el informe de S2.
