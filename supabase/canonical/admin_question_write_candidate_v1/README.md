# admin_question_write_candidate_v1

`STATUS: CANDIDATE / APPLY_PENDING_APPROVAL` · Escrito 2026-09-04 · No aplicado a ninguna base.

## Qué resuelve

`AdminV1Routes.kt` llama a `admin_create_question` y `admin_replace_question_options`.
**Ninguna de las dos existe en la base viva.** Verificado el 2026-09-04 por introspección
de PostgREST (`GET /rest/v1/` → paths `/rpc/*`) del proyecto real: las únicas RPC
administrativas expuestas son `admin_grant_tickets` y las de estadísticas/roles/acceso.

Efecto hoy: **crear una pregunta y reemplazar sus opciones fallan en producción**. La
edición de campos base (enunciado, tipo, fuente, dificultad, activa) sí funciona, porque
`updatePregunta` hace un PATCH directo a `preguntas` sin pasar por RPC.

## Esquema contra el que está escrito (verificado en vivo, no supuesto)

| Tabla | Columnas relevantes |
|---|---|
| `preguntas` | `id`, `codigo_pregunta` NOT NULL, `especialidad_id` NOT NULL, `tipo_pregunta` NOT NULL, `titulo` NOT NULL, `fuente_texto`, `dificultad`, `activa` NOT NULL, `lote_importacion_id`, `creado_por`, `creado_en`, `actualizado_en` |
| `opciones_pregunta` | `id`, `pregunta_id`, `letra`, `texto_opcion`, `posicion` — **sin `es_correcta`** |
| `soluciones_pregunta` | `id`, `pregunta_id`, `opcion_correcta_id` FK, `razonamiento` NOT NULL |
| `preguntas_examen` | PK compuesta (`examen_id`,`pregunta_id`), `posicion` NOT NULL, **`numero_en_examen` NOT NULL** |

## Antes de aplicar — confirmar

1. **`codigo_pregunta`**: la función lo EXIGE como parámetro y no lo genera. La única fila
   existente en la base usa `TCAE-TEST-RAF-P001`, que no sigue el patrón `PREG-000001` de
   la plantilla propuesta. Decidir el formato y quién lo genera es una decisión de producto
   pendiente. Derivarlo del UUID está prohibido.
2. **`dificultad`**: se pasa tal cual; su CHECK exacto no está verificado.
3. **Regla de 4 opciones**: la RPC exige ≥2 opciones, letras únicas y exactamente una
   correcta. El "exactamente 4" lo aplican cliente y Ktor, no la base, porque no se ha
   verificado que exista tal restricción en el esquema.

## Cambio acompañante OBLIGATORIO en Kotlin

Aplicar sólo este SQL **no arregla el flujo**. El cuerpo que envía hoy `AdminV1Routes.kt`
(`AdminCreateQuestionRpc { question, options, solution, examId, position }`) no coincide con
los parámetros de estas funciones. PostgREST mapea claves del JSON a nombres de parámetro,
así que hace falta adaptar el llamante para enviar
`{p_codigo_pregunta, p_especialidad_id, p_tipo_pregunta, p_titulo, p_opciones, p_razonamiento, ...}`.

Alternativa si se prefiere no tocar Kotlin: reescribir la firma para aceptar
`question jsonb, options jsonb, solution jsonb, exam_id uuid, position integer`. Se descartó
porque duplica la validación dentro de blobs JSON opacos y deja la firma peor documentada.

## Defecto relacionado, sin migración

`upsertPreguntaExamen` (Kotlin) inserta en `preguntas_examen` **sin `numero_en_examen`**,
que es NOT NULL. Si esa columna no tiene DEFAULT, toda asociación pregunta↔examen falla en
vivo. No verificable sin escritura (`MAIN_WRITES: NONE` sigue vigente). El candidato
`content_import_atomicity_candidate_v1/002` sí la incluye — su autor ya lo había detectado.

## Rollback

Al final del `.sql`, comentado. No borra datos: estas funciones no existían, así que
eliminarlas devuelve la base al estado exacto anterior.
