# PDF Import Domain

## CURRENT_IMPLEMENTATION

- Screen 09 status: `PASS_WITH_GAPS`; `CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA: NO`.
- Backend parser uses PDFBox.
- UI supports/mentions Aiken and GIFT.
- A staging API exists in the current code/docs, including the authenticated mobile multipart PDF entrypoint.
- Current PDF scope is digital/selectable text; OCR is `NOT_REQUIRED_CURRENT_SCOPE`.
- Existing audit documents parser failures, validation, transactions and duplicate concerns.

## CURRENT RISKS

**SUPERADO 2026-09-18/19.** La persistencia no atómica, los resultados parciales y la duplicidad que este apartado describía (ruta `importarLote` por filas, estado `parcial`) ya no existen: la publicación es UNA RPC atómica e idempotente (ver «Estado vigente» abajo). Se conserva el párrafo siguiente sólo como historia.

_Histórico (2026-08-27):_ el candidato `content_import_atomicity_candidate_v1/` (`APPLY_PENDING_APPROVAL`) quedó SUPERADO por `admin_import_lote_preguntas` (IM3, `20260918113100`), aplicada a MAIN y reescrita por M8 y M10. El paquete candidato es histórico y **no se aplica** (firma antigua).

## TARGET_PRODUCT

`IMPORT SESSION → STAGING → VALIDATION → REVIEW → DUPLICATE DECISION → FINAL PERSISTENCE`; valid rows can continue, warnings require review, errors remain draft/correction. Duplicate policy is `WARN_AND_DECIDE`, default `SKIP`, never silent replace. Import must not silently publish.

## WAITING CLIENT INPUT

`PDF_FORMAT_CONTRACT: STAGED / WAITING_CLIENT_CONFIRMATION Arturo` (actualizado 2026-09-19). La gramática que **ya implementa** el parser real (`backend/ktor/src/main/kotlin/com/oposicontrol/backend/RafagaParser.kt`) está descrita en «Formato vigente del PDF»; lo que falta es que el cliente CONFIRME esa plantilla. No cambiar la gramática sin esa confirmación.

Owner of missing information: `Arturo / Client`.

## IMPLEMENTED IN SCREEN 09 BRANCH

- Mobile route and guided UI for exam selection, PDF upload, staging review, field correction, approval, skip and explicit publish.
- PDFBox selectable-text extraction with a 20 MB request limit and humane rejection for blank/unsupported or malformed PDFs.
- Canonical exam vocabulary mapping for `rafaga`, `razonado` and `inmersivo`; legacy `guiado` is accepted as a compatibility alias.
- No OCR, no new Storage bucket, and no schema/migration/RPC/RLS change. No live MAIN seed/write was executed during implementation or validation; explicit publish remains the user-controlled final-content write through the existing admin boundary.
- Verification: branch `feature/mobile-content-upload`, commit `9eea7da`; JVM tests, `:composeApp:assembleDevDebug` and `git diff --check` passed. Runtime remains pending because no ADB device was connected.

La ruta legacy `/api/v1/admin/rafagas/import` está RETIRADA (DEC-IMP-05: publicaba sin staging ni revisión); el flujo canónico es `POST .../importaciones/preguntas/pdf` + `POST .../importaciones/{loteId}/importar`.

## Backend readiness note

El backend expone staging de importación y procesado con PDFBox tras rutas de administración autenticadas. La nota de 2026-08 sobre un «fallback no atómico» está **superada**: la publicación es atómica (RPC de base de datos) y la compatibilidad con el esquema vivo de MAIN se verificó por E2E LIVE el 2026-09-19 (ver «Estado vigente»).

## Estado vigente — 2026-09-19 (Importaciones final + pregunta anulada, M10)

Fuente de verdad: el código (`RafagaParser.kt`, `AdminV1Routes.kt`) y las migraciones `20260918113100_import_lote_rpc` (IM3) y `20260919122139_annulled_questions_in_exams` (M10). Nada de lo anterior en este documento describe ya el estado actual si lo contradice.

### Estados vivos

- Lote (`lotes_importacion_preguntas.estado`): `pendiente | parseado | revisado | importado | fallido`. No existe `parcial`.
- Fila de staging (`estado_parseo`): `ok | advertencia | error | importada | aprobada | omitida`. La anulación (`anulada`, `motivo_anulacion`) es **ortogonal** al estado de parseo: una pregunta anulada y bien formada es una fila `ok`.
- Publicar (`admin_import_lote_preguntas`) es **atómico e idempotente**: una sola transacción, claim `FOR UPDATE` del lote y advisory lock por TIPO; un lote ya `importado` es un no-op (`ejecutado:false`). Si cambia una relación de un examen publicado pide confirmación (`IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED`). Devuelve `nuevas`, `vinculadas`, `omitidas`, `totalImportadas` y `anuladas`.
- Omitir una fila no exige completitud y borra el puntero de vínculo (`vincular_pregunta_id = NULL`). «Vincular a existente» (duplicado del banco) reutiliza la pregunta sin duplicarla y crea sólo la relación.
- Reutilización: la misma pregunta puede estar anulada en un examen y normal en otro (una fila en `preguntas`, dos en `preguntas_examen`).

### Formato vigente del PDF (gramática del parser real)

Pendiente de confirmación por el cliente (véase arriba), pero es lo que el sistema acepta hoy:

1. **Separador único**: una línea propia que, recortada, es exactamente `--- PREGUNTA ---` (sensible a mayúsculas). Un separador parecido (`--- PREGUNTA 1 ---`, `--- pregunta ---`, guiones largos `— PREGUNTA —`) abre una fila de **ERROR** y nunca fusiona preguntas.
2. Todo lo anterior al primer separador es **preámbulo** y se ignora; si tenía forma de pregunta (TITULO y opciones o RESPUESTA) la primera pregunta lleva un AVISO.
3. Claves exactas en mayúsculas: `TITULO`, `A`, `B`, `C`, `D`, `RESPUESTA`, `RAZONAMIENTO`, `FUENTE`, `ETIQUETA`, más `ESTADO` y `MOTIVO_ANULACION` (opcionales). Una clave mal escrita (`Fuente:`, `TÍTULO:`) es un error, no una continuación. Las claves antiguas con guion bajo (`ID_GLOBAL:`…) se rechazan como «campo no admitido». Un campo repetido en un bloque es un error.
4. `RESPUESTA` es A, B, C o D. `TITULO` y las cuatro opciones son obligatorios.
5. `ESTADO: ANULADA` marca la pregunta anulada **en este examen**. Sin `ESTADO` = normal. Cualquier otro valor (incluido `ESTADO: NORMAL`) es error. `ESTADO ANULADA` sin dos puntos (o una línea `ANULADA` suelta) es error. `MOTIVO_ANULACION` exige `ESTADO: ANULADA`, máx. 500 caracteres.
6. `razonada` e `inmersiva` exigen `RAZONAMIENTO`. En `rafaga`, `RAZONAMIENTO`/`FUENTE` dejan la fila en `advertencia` (se descartan al publicar).
7. Un marcador de página suelto dentro de un campo (`Página 3`, `12`) es una advertencia.
8. Límites: PDF digital (texto seleccionable) ≤ 20 MB, sin OCR; máximo **200 preguntas por examen** (tope de la RPC, `EXAM_QUESTION_LIMIT_REACHED`). El examen destino se elige en la pantalla, no en el PDF.
9. `ETIQUETA` se lee y se guarda en staging pero **no** llega a `etiquetas_pregunta` (DEBT-052).

Ejemplo Ráfaga (copiar/pegar):

```
--- PREGUNTA ---
TITULO: ¿Cuál es el plazo para interponer un recurso de alzada?
A: Diez días
B: Un mes
C: Tres meses
D: Un año
RESPUESTA: B
ETIQUETA: Procedimiento administrativo
```

Ejemplo Razonada (exige `RAZONAMIENTO`; `FUENTE` opcional):

```
--- PREGUNTA ---
TITULO: ¿Qué artículo de la Constitución regula la reforma constitucional?
A: El 166
B: El 168
C: El 169
D: El 171
RESPUESTA: B
RAZONAMIENTO: El Título X regula la reforma; el 168 fija el procedimiento agravado.
FUENTE: Constitución Española, Título X
```

Ejemplo Inmersiva (mismo formato que Razonada; los recursos se vinculan después desde Preguntas, DEBT-051):

```
--- PREGUNTA ---
TITULO: Según el documento mostrado, ¿qué órgano emite el informe?
A: El Consejo de Estado
B: La Intervención General
C: El Tribunal de Cuentas
D: El Defensor del Pueblo
RESPUESTA: A
RAZONAMIENTO: El informe del texto es un dictamen del Consejo de Estado.
```

Ejemplo con anulación (cualquier tipo; el motivo es opcional):

```
--- PREGUNTA ---
TITULO: ¿Qué órgano resuelve el recurso de reposición?
A: El superior jerárquico
B: El Consejo de Estado
C: El mismo órgano que dictó el acto
D: El Defensor del Pueblo
RESPUESTA: C
ESTADO: ANULADA
MOTIVO_ANULACION: Anulada por el tribunal por enunciado ambiguo.
```

Una pregunta anulada se sirve en su posición con `anulada=true` y no puntúa (véase `docs/integration/ENDPOINT_CATALOG.md`, «Pregunta anulada por examen (M10)»).

### UI de escritorio (Importaciones)

Estados `importado`/`importada` correctos; preflight que espeja la RPC (errores, advertencias, duplicados sin resolver, filas incompletas, razonamiento, tipo, tope de 200 conservador, examen publicado con confirmación, lote pendiente/importado); «Vincular a existente»; chip «Anulada»; historial con el `resumen` del servidor (sin ceros falsos); sin copy «no atómica». La pantalla compacta/móvil (`ContentUploadScreen`) no tiene el diálogo de confirmación de examen publicado (DEBT-058; la RPC lo exige igualmente).

### Verificación (2026-09-19)

- Tests contados por XML, 0 fallos/errores/omitidos: `:backend:ktor` 804, `:domain` 81, `:data` 104, `:features:imports` 105, `:features:exams` 313, `:features:questions` 72.
- 13 PDF binarios reales en `backend/ktor/src/test/resources/pdf/` pasan por PDFBox → parser.
- E2E LIVE contra MAIN (`ndiooyyqtaeysnedywer`) vía Ktor real, PDF → lote → staging → revisión → publicación → BD: **74/74 checks** — los tres tipos con anulada y motivo; reutilización (misma pregunta anulada en un examen y normal en otro); puntuación con 5 preguntas/1 anulada (responder la anulada no puntúa: 4 evaluables, 3 respondidas, 2 correctas, 1 fallada, 1 sin responder, 66,67 %); atomicidad (fila en error ⇒ 409 y 0 escrituras; omitir sin completar ⇒ publica); retry (no-op) y dos publicaciones simultáneas (1 ejecutada + 1 no-op); omitir borra el vínculo. Usuario admin desechable y datos `E2E_TEST_M10_*` borrados: MAIN volvió a 3 preguntas / 2 vínculos / 3 exámenes / 0 lotes. No reproducible en CI (DEBT-062).
- Dos revisiones independientes de sólo lectura sin P0/P1 abiertos. Thermos: `NOT_AVAILABLE` (skill inexistente en la sesión).

### Deuda y riesgos abiertos

Véase `docs/registry/TECH_DEBT.md` (DEBT-051 a DEBT-065) y `docs/registry/RISKS.md` (PDF-001, PDF-006, PDF-007, PDF-008, IMP-002, IMP-003, INT-001, STAT-001, TEST-001). `CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA` sigue en `NO` hasta que el cliente confirme el formato de plantilla.
