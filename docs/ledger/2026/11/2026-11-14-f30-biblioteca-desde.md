# F30 — Biblioteca: pregunta guardada (FA50) y `desde` en D2-08 (FA51) (2026-11-14)

Rama local `f30/biblioteca-desde` desde develop `08ea7f0f`. Sin push. MAIN/VPS sin tocar. Plan aprobado por el propietario: `ancient-baking-melody.md`, secciones 3 y 4.

## FA50 — la pregunta guardada en la hoja de la Biblioteca (aditivo)

- D4-27 `GET /api/v1/app/opositeca/guardados/pregunta/{preguntaId}`: solo si el usuario la guardó Y puede abrir alguno de sus exámenes (la regla de D4-24: activo, publicado, veredicto FREE / GRANTED / SUBSCRIPTION, la especialidad primero). Si no: 404 `NOT_FOUND` (no guardada, sin acceso, inactiva o inexistente son lo mismo). No toca el limitador diario: no gasta cupo.
- Cuerpo = el MISMO esquema que un elemento de la revisión D2-65 (`ReviewItem`) para una pregunta fuera de un intento: contenido y solución VIVOS (las funciones que congelan las revisiones), `posicion` en el examen elegido, `status` `ANNULLED` (anulada en ese examen: sin `correctAnswer` ni `explanation`, como la revisión) o `UNANSWERED`, respuestas y tiempos vacíos; más `examenId`, `examenTitulo`, `tipoExamen` (`rafaga` | `razonado` | `inmersivo`, el enum canónico `TipoExamen` del contrato) y `anulada`. `explanation` sigue la MISMA política que D2-65 (`EXPLANATIONS_VISIBLE`).
- Examen elegido: un enlace donde la pregunta NO está anulada primero, luego la publicación más reciente.
- D4-28 `GET /api/v1/app/opositeca/guardados/pregunta/{preguntaId}/recursos/{recursoId}`: misma autorización que D4-27 y luego el recurso debe ser uno de los recursos V1 vivos de la pregunta; URL firmada de vida corta como D2-66 (`AttemptResourceAccess`). Necesario para que el inmersivo abra su recurso sin intento.
- D4-24 (lista): `pregunta.tipoExamen` (opcional) y la MISMA elección de examen; consecuencia: una pregunta anulada en todos sus exámenes accesibles ahora sale `accesible` (su detalle dice `anulada`). Antes salía no accesible.

## FA51 — `desde` en D2-08 (aditivo, sin migración)

- `GET /api/v1/app/intentos/{id}/preguntas?desde=N`: la página empieza en la posición N (1-based, la del grid de D2-07). Alternativa al cursor: los dos a la vez = 400 `VALIDATION` (`desde`, `MUTUALLY_EXCLUSIVE`); no entero = `INVALID_FORMAT`; <1 o >200 = `OUT_OF_RANGE`; más allá de la última = página vacía sin `nextCursor`. El `nextCursor` devuelto sigue como siempre.
- Cupo idéntico: solo se revelan (y cuentan) las preguntas de ESA página, igual que llegando a ella por cursor. Retomar en la 87 = 1 petición en vez de 5.
- Ojo cliente: una imagen anterior ignora `desde` (D2-08 no rechazaba parámetros desconocidos) y devuelve la página 1; el cliente debe comprobar que la primera posición devuelta es N.

## Migración `20261114200000_v1_biblioteca_pregunta` (predecesora F20 `20261109100000`)

- Nueva `public.app_saved_question_detail(uuid, uuid)` (service_role). `public.app_list_saved` se borra y se recrea con `tipo_examen` y la nueva elección (cambia el tipo devuelto). ROLLBACK: restaura literalmente el cuerpo de `20261022100800`. VERIFY: 3 filas.

## Anexo al runbook (lo hace el propietario)

1. Requisito: F20 (`20261109100000`) aplicada y registrada en MAIN (independiente de F23).
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f30.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261114200000_v1_biblioteca_pregunta.sql` → `aplicada, VERIFY = 0, registrada`.
3. DESPUÉS, imagen nueva de Ktor (D4-27 / D4-28 llaman a la función nueva; `desde` es solo Ktor).
4. Rollback: imagen anterior primero; luego el bloque ROLLBACK y `delete from supabase_migrations.schema_migrations where version = '20261114200000'`.

## Verificación

Ktor (`SavedItemsRoutesTest` FA50 contra el OpenAPI congelado; `AttemptRoutesTest` `desde`: arranque, cupo de la página, cursor siguiente, límites, validación; `OpenApi*`, `RouteInventoryTest`, `RouteProfileGateTest`), dbtest `V1BibliotecaPreguntaMigrationTest`, gate PG17 y ensayo `docs/ledger/2026/11/rehearse-f30-2026-11.sh`. Resultados en el informe de S2.

## FA51b — primera página en línea en D2-03 (aditivo, sin migración)

El cliente midió que un intento NUEVO seguía costando 2 viajes (D2-03 y luego D2-08 página 1, ≈1200 ms a 600 ms por petición).

- `POST /api/v1/app/examenes/{examId}/intentos?incluirPreguntas=true` (o `?desde=N`, que implica incluir): la respuesta 201/200 añade `preguntas` = EXACTAMENTE la página de D2-08 para esa posición (`items` + `nextCursor`, tamaño por defecto 10; se sigue con D2-08 y ese cursor). Mismo cupo: solo se revelan y cuentan las preguntas de esa página (es la misma llamada interna).
- Sin el parámetro (o `incluirPreguntas=false`): respuesta idéntica a la de siempre.
- Si esa página no se puede servir (p. ej. 429 `DAILY_LIMIT_REACHED`), el arranque NO falla: `preguntas` ausente y el cliente llama a D2-08, que devuelve el error con su Retry-After.
- `incluirPreguntas` que no sea true/false o `desde` inválido: 400 `VALIDATION` ANTES de crear el intento. Una imagen anterior ignora los parámetros (respuesta sin `preguntas`): el cliente debe tolerar su ausencia.
