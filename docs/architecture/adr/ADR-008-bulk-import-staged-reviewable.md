# ADR-008: Bulk import is staged and reviewable

Status: `CONFIRMED_PRODUCT_DECISION` · Date: `2026-08-19`

Decision: bulk import passes through session, staging, validation, review and duplicate decision before final persistence/publication.

Evidence: confirmed product direction; Screen 09 implements the session/staging/review path, integrated into `integration/mobile-finish` (originally `feature/mobile-content-upload` at commit `9eea7da`).
Consequence: no auto-replace or silent success is allowed. The current publish boundary is row-level, reports `parcial` when errors or skips exist. A candidate database decision now exists (2026-08-27): `public.admin_import_lote_preguntas`, atomic, duplicate candidates flagged for review — see `supabase/canonical/content_import_atomicity_candidate_v1/`, status `APPLY_PENDING_APPROVAL`, not applied.
