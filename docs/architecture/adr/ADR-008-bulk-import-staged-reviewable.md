# ADR-008: Bulk import is staged and reviewable

Status: `CONFIRMED_PRODUCT_DECISION` · Date: `2026-08-19`

Decision: bulk import passes through session, staging, validation, review and duplicate decision before final persistence/publication.

Evidence: confirmed product direction; Screen 09 implements the session/staging/review path, integrated into `integration/mobile-finish` (originally `feature/mobile-content-upload` at commit `9eea7da`).
Consequence: no auto-replace or silent success is allowed. **Estado actual (2026-09-19):** la publicación es UNA transacción atómica e idempotente (`public.admin_import_lote_preguntas`, IM3 `20260918113100`, rehecha por M8 `20260919085404` y M10 `20260919122139`, aplicadas a MAIN `ndiooyyqtaeysnedywer`); no existe el estado `parcial`. Los duplicados del banco (por TIPO, sin especialidad) se detectan y una persona decide: vincular a la existente (sin duplicarla) u omitir, nunca auto-reemplazo. Hay confirmación explícita si la importación cambia un examen ya publicado. La anulación por el tribunal pertenece a la relación pregunta↔examen (`preguntas_examen.anulada`), no a la pregunta. El candidato `supabase/canonical/content_import_atomicity_candidate_v1/` (2026-08-27) es histórico y NO se aplica. Detalle: `docs/product/domains/PDF_IMPORT.md`.
