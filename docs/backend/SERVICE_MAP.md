# Service Map

## Canonical V1 reconciliation — 2026-08-20

The service layer is `ADAPTER_REQUIRED` where UUID live tables coexist with integer DTO/repository parsing and legacy routes. Admin/import, PDF, commerce, news and OposiBot services must retain compatibility until consumer and live-policy evidence is complete. This audit made no service behavior change.

Readiness classification: route/service contracts are source-verified; live persistence, RLS, RPC behavior and deployed error semantics are not. Admin V1 has staging/import operations; la publicación de un lote es atómica (RPC `admin_import_lote_preguntas`, ver actualización 2026-09-19 abajo) — el «fallback no atómico» de esta nota es histórico. App V1 applies user-id filters in source, but role authorization and live policy enforcement remain partial/not verified.

Los servicios se catalogan desde el código de `backend/ktor` y la auditoría `10_BACKEND_MAP.md`. Cada servicio debe declarar input/output, auth, persistencia, errores y tests. No se afirma disponibilidad live ni atomicidad no probada.

## Importaciones y pregunta anulada (M10) — 2026-09-19

Verificado en código (`AdminV1Routes.kt`, `RafagaParser.kt`, `AppV1Routes.kt`, `SupabaseV1Gateway.kt`); no sustituye a lo anterior salvo lo que lo contradice.

- **Publicación de lote** (`AdminV1Gateway.importarLote`): Kotlin ya no escribe en `preguntas_parseadas_staging` ni en `lotes_importacion_preguntas` al publicar; llama a la RPC `admin_import_lote_preguntas(p_lote_id, p_confirmar_examen_publicado, p_actor_id)` (atómica, idempotente; devuelve `nuevas`, `vinculadas`, `omitidas`, `totalImportadas`, `anuladas`) y sólo audita (`lote_importado`) si `ejecutado=true`. Sin la RPC provisionada responde `IMPORT_WRITE_RPC_NOT_PROVISIONED`.
- **Creación de lote** (`crearLote`/`crearLoteDesdePdf`): parseo PDFBox → `RafagaParser` → filas de staging (incluidas `anulada`/`motivo_anulacion`); valida todo antes de escribir y compensa borrando el lote si falla la inserción de filas. No es una transacción única (DEBT-054). Sin dedupe: el mismo PDF dos veces crea dos lotes (DEBT-053).
- **Servicio de app** (`AppV1Routes.kt`): `examenDetalle` sirve las preguntas activas con `anulada`/`motivoAnulacion` y `totalEvaluables`; `iniciar` dimensiona el intento con las evaluables; `responder` devuelve `anulada=true`/`esCorrecta=false` sin puntuar; `finalizar` excluye respuestas de anuladas y calcula `total_sin_responder = max(0, evaluables − respondidas)`. Fórmula sin cambios (`porcentaje = correctas/respondidas`, `puntuacion = correctas`). `examenes.total_preguntas` sigue siendo posiciones activas.
- **Ráfaga público** (`SupabaseV1Gateway.getPreguntasExamenRafaga`): expone `anulada`/`motivoAnulacion`; no puntúa.
- **Orden de despliegue**: M10 primero en MAIN, Ktor después (RISKS.md IMP-003).
