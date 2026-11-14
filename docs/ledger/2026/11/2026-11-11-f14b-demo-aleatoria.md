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

## Enmienda 2026-11-14 (decisión del propietario: "al azar, elegidas UNA vez y luego fijas")

Defecto del cambio anterior: una pregunta NUEVA con mejor md5 entraba en el conjunto, así que subir preguntas a un examen podía cambiar la demo. La migración no se había aplicado en ningún sitio, así que se enmienda en su sitio (misma versión `20261111100000`, blob nuevo, manifiesto y ensayo rehechos).

- Tabla de servicio `private.demo_set_fijo (examen_id, pregunta_id, fijada_en)` con el conjunto AUTOMÁTICO materializado. No se usan las marcas `preguntas_examen.es_demo` de Control a propósito: una pregunta marcada no se puede anular, pasar a reserva ni mover (DEMO_CONFLICT en el trigger de reglas, `admin_set_question_flags`, la importación y `official_rules_problem`), y eso rompería "si se anula, se sustituye".
- `private.demo_set_fijar(examen)`: la primera vez que un examen PUBLICADO (`publicado_en` no nulo) sin conjunto explícito completo tiene N elegibles, guarda las N primeras por md5. Después: un miembro que deja de ser elegible se BORRA y entra solo la siguiente del orden md5 que no es miembro; la sustituta se queda aunque la original vuelva. Con menos de N elegibles los miembros esperan.
- Cuándo: triggers de restricción DIFERIDOS (al COMMIT, ven la transacción entera: una importación completa cuenta como una) en `preguntas_examen` (alta, baja, `anulada`, `es_reserva`), `preguntas` (`activa`) y `examenes` (alta, `publicado_en`). Un borrador no se fija hasta publicarse. RELLENO en la migración para todos los exámenes ya publicados.
- `private.demo_preguntas_efectivas` (la que ya llaman `demo_listo`, el arranque y el congelado): miembros elegibles primero y después el orden md5; con conjunto guardado es exactamente el guardado; sin él (borrador) es la vista previa de lo que se fijará.
- Conjunto explícito completo de Control: sigue ganando. Ktor sin cambios.
- dbtest `V1DemoAleatoriaMigrationTest` (7): añadir 20 preguntas (con control negativo: el md5 puro SÍ cambiaría) no cambia la demo; anular una sustituye solo esa y la sustituta se queda al restaurarla; desactivar una igual; borrador solo se fija al publicar; relleno, VERIFY (12 filas) y ROLLBACK.

## Anexo al runbook (aplicación a MAIN, la hace el propietario)

1. Requisito: F20 (`20261109100000`) aplicada y registrada en MAIN (predecesora). F23/F20b, si van antes, no estorban.
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f14b.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261111100000_v1_demo_aleatoria.sql` (teclear la versión). Debe terminar en `aplicada, VERIFY = 0, registrada`.
3. Imagen: NO hace falta. El Ktor no cambia (lee `demo_listo` y llama a las mismas RPC). Efecto inmediato en el siguiente inicio de demo; las demos abiertas conservan su conjunto.
4. Rollback: bloque ROLLBACK de la migración (quita triggers, función y tabla; vuelve el cuerpo de F14 byte a byte) y `delete from supabase_migrations.schema_migrations where version = '20261111100000'`.
5. Efecto visible (enmienda): al aplicarla, cada examen publicado fija sus 5 (relleno); la demo cambia UNA vez respecto a F14 (primeras 5 por posición) y ya no vuelve a cambiar al subir preguntas.

## Verificación

dbtest `V1DemoAleatoriaMigrationTest` (7) y `V1AutoDemoMigrationTest` fijado a F14 (sin F14b); gate PG17; ensayo `docs/ledger/2026/11/rehearse-f14b-2026-11.sh` (resultados en el informe de F14b).
