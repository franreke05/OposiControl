# Product Scope

## CURRENT_IMPLEMENTATION

- Cliente actual confirmado: `ARTURO`.
- Aplicación administrativa/operativa OposiControl con Compose Multiplatform y backend Ktor.
- PDF backend con PDFBox; UI con formatos Aiken/GIFT; existe API de staging documentada en el audit.
- Exámenes verificados en código: `rafaga` e `inmersivo`; `razonado` no está verificado.
- Login temporalmente deshabilitado por `TEMPORARILY_DISABLE_LOGIN = true`.
- Supabase live outside the verified Product Metrics/wallet scope: `LIVE_VERIFIED: NO`.

## TARGET_PRODUCT

- Admin authentication: email/password + Google + Sign in with Apple on iOS.
- Exam types: `inmersivo`, `razonado`, `rafaga`.
- Bulk PDF: staged/reviewable import with operator review.
- News: segmentation by `SPECIALTY`.
- Support: bidirectional admin↔user support.
- Platforms: desktop + mobile.
- Product philosophy: professional admin console with operational usability.

## CONFIRMED_PRODUCT_DECISIONS

- Autenticación objetivo: email/password, Google y Sign in with Apple en iOS.
- Tipos objetivo: `INMERSIVOS`, `RAZONADOS`, `RAFAGAS`.
- PDF actual de alcance: texto digital/selectable; OCR `NOT_REQUIRED_CURRENT_SCOPE` salvo cambio posterior.
- Recursos inmersivos pueden mostrarse después de responder; documentar comportamiento de dominio sin congelar DTO nuevo.
- Importación con `PREVIEW/REVIEW` antes de consolidación final.
- Flujo preferido: `IMPORT SESSION → STAGING → VALIDATION → REVIEW → DUPLICATE DECISION → FINAL PERSISTENCE`.
- Errores parciales: válidos pueden continuar; warnings requieren revisión; errores quedan en draft/corrección; nunca inserción directa en tablas finales.
- Duplicados: `WARN_AND_DECIDE`, default `SKIP`, nunca auto-replace sin decisión explícita.
- Importación no publica en silencio; estados candidatos `IMPORTED`, `DRAFT`, `READY`, `PUBLISHED`, `ERROR`, pendientes de cierre exacto.
- News segmentada solo por `SPECIALTY`.
- Support objetivo: `NEW → IN_REVIEW → IN_PROGRESS → RESOLVED → CLOSED`, mapeado a enums actuales sin sustituirlos; flujo bidireccional admin↔usuario.
- Plataformas: desktop y mobile, con alta densidad desktop y adaptación móvil; no tabla desktop de 15 columnas directamente en teléfono.
- OposiBot y OposiControl se documentan como apps/repos separados.

## PENDING_PRODUCT_DECISIONS

- `ADMIN_ROLE_MODEL`: pendiente; no asumir `Arturo == admin`.
- Contrato/formato final PDF de Arturo: `PDF_FORMAT_CONTRACT: STAGED / WAITING_CLIENT_INPUT`; no inventar regex, delimitadores ni plantilla.
- Estados exactos de publicación/importación.
- Fuente canónica de migraciones después de resolver drift.

## OUT_OF_SCOPE

No incluye en esta fase login, DTOs/endpoints nuevos, Ktor refactor, navegación/UI, roles, noticias/tickets, conexión OposiBot, migraciones/SQL/RPC/RLS/storage, Supabase writes ni cambios Kotlin funcionales.
