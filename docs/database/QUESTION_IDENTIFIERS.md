# Cómo se generan los identificadores de las preguntas

Fecha: 2026-09-25 · Verificado en lectura contra Supabase MAIN (`ndiooyyqtaeysnedywer`) y el código de la rama `feature/question-resources-audio-video`.

## Resumen

Cada pregunta tiene **dos identificadores** con papeles distintos:

| | `id` | `codigo_pregunta` |
|---|---|---|
| Ejemplo | `3f2b8c1e-…` (UUID) | `PREG-000123` |
| Quién lo crea | El **cliente** (la app) lo genera antes de guardar | La **base de datos**, siempre |
| Para qué sirve | Clave interna: relaciones (opciones, recursos, exámenes) y reintentos seguros | Nombre legible para personas: buscar, citar, hablar de una pregunta |
| ¿Se puede elegir? | Sí, quien crea la pregunta lo propone | **No**: cualquier código que llegue se ignora |
| ¿Cambia? | Nunca | Nunca (editarlo da error `QUESTION_CODE_IMMUTABLE`) |

## 1. El `id` (UUID)

- Columna `preguntas.id`, tipo `uuid`, con `gen_random_uuid()` por defecto en la tabla.
- En la práctica lo genera la app al empezar el alta y lo manda a la RPC `admin_create_question(p_pregunta_id, …)`. Esto es lo que hace **idempotente** el guardado: si la respuesta se pierde y la app reintenta con el mismo `id`, la base no duplica la pregunta.
- Reglas de la RPC con ese `id`:
  - Sin `id` → error `QUESTION_ID_REQUIRED`.
  - `id` nuevo → se inserta la pregunta.
  - `id` ya existente **con los mismos datos** → devuelve la existente (`creada: false`), sin error.
  - `id` ya existente **con datos distintos** → error `QUESTION_ID_CONFLICT` (no pisa nada).

## 2. El `codigo_pregunta` (`PREG-NNNNNN`)

Lo asigna el trigger `trg_preguntas_codigo_pregunta_insert` (función `private.tg_preguntas_codigo_pregunta`) **antes de cada INSERT**:

```
v_n := nextval('private.preguntas_codigo_pregunta_seq');
codigo_pregunta := 'PREG-' || lpad(v_n, max(6, longitud de v_n), '0');
```

- Es una **secuencia global** de Postgres: `PREG-000001`, `PREG-000002`, … Con más de 999 999 preguntas el número simplemente crece (`PREG-1000000`).
- **Todas las vías pasan por el mismo trigger**: alta manual, importación de PDF (`admin_import_lote_preguntas`) y cualquier escritura directa. Nadie puede fijar el código: el valor que mande el cliente, la RPC o la importación se sobrescribe.
- La columna tiene restricción `UNIQUE (codigo_pregunta)`.
- Un trigger `BEFORE UPDATE OF codigo_pregunta` lo blinda: reenviar el mismo valor vale, cambiarlo lanza `QUESTION_CODE_IMMUTABLE`.

## 3. Cosas que Arturo debe saber (comportamientos no obvios)

1. **La numeración tiene huecos, y es normal.** Una secuencia nunca devuelve números. Si un alta se reintenta con el mismo `id` (`on conflict do nothing`) o la transacción falla, el número ya se consumió. Estado real hoy: la secuencia va por **182** con **104** preguntas en la tabla. Los huecos no indican pérdida de datos.
2. **El código no es el orden en un examen.** El orden dentro de un examen es `preguntas_examen.posicion`/`numero_en_examen`; el código sólo identifica la pregunta del banco.
3. **Borrar no libera el código.** Archivar una pregunta (`activa = false`) mantiene su código; la secuencia no retrocede.
4. **Hay 1 fila anómala en MAIN:** `TCAE-TEST-RAF-P001`, de antes del trigger (dato de prueba). No cumple el formato `PREG-NNNNNN`; cualquier código que asuma ese formato debe tolerarla o eliminarla.
5. **Si se vacía la tabla, la secuencia NO se reinicia sola.** Las siguientes preguntas seguirían desde el 183 salvo que se reinicie a propósito (`ALTER SEQUENCE … RESTART` o `TRUNCATE … RESTART IDENTITY` con la secuencia asociada). Decisión a tomar de forma explícita.
6. **Recursos**: cada recurso de una pregunta tiene su propio `id` UUID (lo genera el cliente como clave de idempotencia de la subida) y un `codigo_recurso` `R-<id corto>` que asigna el backend. Ruta en Storage: `preguntas/<pregunta_id>/<recurso_id>.<ext>`.

## 4. Dónde está en el código

- Trigger y secuencia: base de datos (`private.tg_preguntas_codigo_pregunta`, `private.preguntas_codigo_pregunta_seq`).
- Alta atómica: RPC `public.admin_create_question`.
- Backend: `backend/ktor/.../AdminV1Routes.kt` (`createPregunta`) y `BackendErrors.kt` (mensajes de `QUESTION_ID_*`, `QUESTION_CODE_IMMUTABLE`).
- Cliente: `domain/.../QuestionBank.kt` (`QuestionDetail.code`), pantallas en `features/questions`.

## 5. Qué NO se ha verificado

- Que exista un fichero de migración versionado en el repo equivalente al trigger de MAIN (hay deriva documentada entre migraciones y base real; la fuente de verdad aquí es la base viva).
- Los códigos de exámenes y otras entidades: este informe cubre sólo preguntas y sus recursos.
