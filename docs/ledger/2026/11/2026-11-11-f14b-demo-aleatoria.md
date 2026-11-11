# F14b — Demo aleatoria fija por examen (2026-11-11)

Rama local `f14b/demo-aleatoria` desde develop `08ea7f0f` (F14 desplegada). Sin push. MAIN/VPS sin tocar.

## Decisión del propietario (2026-11-11)

El conjunto AUTOMÁTICO de demo de un examen son 5 preguntas al azar, FIJAS por examen (las mismas para todo usuario, siempre), en vez de las 5 primeras por posición. El conjunto explícito de Control sigue ganando; reglas de cierre iguales (principal, no reserva, no anulada, pregunta activa; menos de N elegibles = sin demo).

## Cambio (migración `20261111100000_v1_demo_aleatoria`)

- Solo `private.demo_preguntas_efectivas`: las elegibles se ordenan por `md5(examen_id::text || pregunta_id::text)` (desempate `pregunta_id`) y se toman las N primeras (`demo_preguntas`, 5). Se devuelven en orden del examen. Sin azar en ejecución: mismo conjunto para todo usuario, llamada y réplica.
- `demo_listo`, `exam_start_verdict`, `app_start_exam_attempt` y el congelado de demo ya la llaman: sin cambios.
- Estabilidad: el conjunto solo cambia si cambian las elegibles. Una que deja de serlo (anulada, desactivada, pasada a reserva, desvinculada) sale y entra la siguiente del orden fijo; si vuelve a ser elegible recupera su sitio. Una pregunta NUEVA entra solo si su md5 queda por delante de la N-ésima. Los intentos ya empezados no se mueven (congelados).
- Contrato: forma intacta. FA48 solo texto (`attempts.yaml` D2-03); manifiesto de congelación actualizado. CONTRACT_FREEZE.md (repo OposiBot): PENDING_OWNER.
- Cliente OposiBot: sin cambios (sigue leyendo `accessState == DEMO` y el intento congelado).

## Anexo al runbook (aplicación a MAIN, la hace el propietario)

1. Requisito: F20 (`20261109100000`) aplicada y registrada en MAIN (predecesora). F23/F20b, si van antes, no estorban.
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f14b.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261111100000_v1_demo_aleatoria.sql` (teclear la versión). Debe terminar en `aplicada, VERIFY = 0, registrada`.
3. Imagen: NO hace falta. El Ktor no cambia (lee `demo_listo` y llama a las mismas RPC). Efecto inmediato en el siguiente inicio de demo; las demos abiertas conservan su conjunto.
4. Rollback: bloque ROLLBACK de la migración (vuelve el cuerpo de F14 byte a byte) y `delete from supabase_migrations.schema_migrations where version = '20261111100000'`.

## Verificación

dbtest `V1DemoAleatoriaMigrationTest` (5) y `V1AutoDemoMigrationTest` fijado a F14 (sin F14b); gate PG17; ensayo `docs/ledger/2026/11/rehearse-f14b-2026-11.sh` (resultados en el informe de F14b).
