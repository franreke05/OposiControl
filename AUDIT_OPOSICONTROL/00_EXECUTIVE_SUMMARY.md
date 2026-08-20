# OposiControl — Executive Summary

Fecha de auditoría: 2026-08-19. Alcance: solo lectura, inspección, trazado, verificación y documentación.

## Veredicto

`AUDIT_PARTIAL`: el repositorio permite reconstruir con bastante precisión la aplicación, sus rutas y sus contratos, pero no fue posible consultar la base Supabase en runtime ni ejecutar una UI real en esta sesión. Todo lo que depende de esas dos fuentes queda `NOT_VERIFIED`.

## Qué existe realmente

- Aplicación Kotlin Multiplatform/Compose para Android, iOS y Desktop JVM.
- Panel admin desktop con 17 secciones y experiencia mobile distinta.
- Backend Ktor JVM con cuatro superficies superpuestas: `/content/*` legacy, `/v1/*` público, `/api/v1/admin/*` admin y `/api/v1/app/*` consumidor.
- Auth de OposiBots en `/v1/auth/*` y CRUD admin de usuarios OposiBots.
- Integración PostgREST mediante service role en backend; el cliente no debería escribir directamente a Supabase.
- CRUD real de contenido, preguntas, opciones, recursos, noticias, catálogos, usuarios, tickets, soporte, moderación, auditoría y tienda en distintos grados.
- Dos importadores diferentes: texto Aiken/GIFT en UI y PDFBox para ráfagas en backend.

## Hallazgos prioritarios

1. El login del panel está explícitamente desactivado (`TEMPORARILY_DISABLE_LOGIN = true`), aunque el backend sí protege sus rutas.
2. La fuente de verdad de esquema es inestable: solo hay cuatro migrations locales recientes, mientras gran parte del DDL vive en SQL documental e histórico; la documentación registra aplicación manual directa a Supabase.
3. La importación PDF de ráfagas es síncrona y persiste pregunta por pregunta; no hay rollback visible, preview, hash/idempotencia, job ni OCR.
4. Coexisten rutas y modelos legacy en inglés/singular/int y V1 en español/plural/UUID, creando drift contractual.
5. La suite global falla en compilación por `AuditViewModel.kt:35`; las pruebas backend y parser de exámenes ejecutadas por separado pasan.

## Etiquetas de evidencia

`VERIFIED` = observado en código o prueba ejecutada. `DOCUMENTED_ONLY` = afirmado por documentación/historial. `INFERRED` = deducción explícita. `NOT_VERIFIED` = requiere runtime/BD no disponible. `PARTIAL`, `LEGACY`, `DEAD_CODE` se usan cuando corresponde.

No se modificó código funcional, Gradle, Supabase ni datos. Solo se creó esta carpeta de auditoría.
