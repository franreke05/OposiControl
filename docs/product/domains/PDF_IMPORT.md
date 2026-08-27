# PDF Import Domain

## CURRENT_IMPLEMENTATION

- Screen 09 status: `PASS_WITH_GAPS`; `CONTENT_IMPORT_READY_FOR_REAL_CLIENT_DATA: NO`.
- Backend parser uses PDFBox.
- UI supports/mentions Aiken and GIFT.
- A staging API exists in the current code/docs, including the authenticated mobile multipart PDF entrypoint.
- Current PDF scope is digital/selectable text; OCR is `NOT_REQUIRED_CURRENT_SCOPE`.
- Existing audit documents parser failures, validation, transactions and duplicate concerns.

## CURRENT RISKS

Non-atomic persistence, partial errors and duplicate handling remain open **in the live route** — confirmed, not silently accepted. The mobile flow inserts into staging first; final publication uses the existing row-level admin import boundary and reports partial results instead of claiming atomic success.

A candidate fix is designed (2026-08-27): `public.admin_import_lote_preguntas(p_lote_id)`, one Postgres transaction, all-or-nothing, with duplicate candidates flagged (`estado_parseo = 'duplicada_candidata'`) for human review — never auto-skipped, never auto-replacing an existing question. Status `APPLY_PENDING_APPROVAL`: written without live Supabase schema access, not applied to any database, not wired into the active Kotlin route. See `supabase/canonical/content_import_atomicity_candidate_v1/README.md` for the exact gaps that must be confirmed before applying (real `admin_create_question` signature, `preguntas`/staging column shapes, `estado_parseo` constraint).

## TARGET_PRODUCT

`IMPORT SESSION → STAGING → VALIDATION → REVIEW → DUPLICATE DECISION → FINAL PERSISTENCE`; valid rows can continue, warnings require review, errors remain draft/correction. Duplicate policy is `WARN_AND_DECIDE`, default `SKIP`, never silent replace. Import must not silently publish.

## WAITING CLIENT INPUT

`PDF_FORMAT_CONTRACT: STAGED / WAITING_CLIENT_INPUT Arturo`. The final format is not available. Do not invent regex, template, delimiters or definitive parser rules.

Owner of missing information: `Arturo / Client`.

## IMPLEMENTED IN SCREEN 09 BRANCH

- Mobile route and guided UI for exam selection, PDF upload, staging review, field correction, approval, skip and explicit publish.
- PDFBox selectable-text extraction with a 20 MB request limit and humane rejection for blank/unsupported or malformed PDFs.
- Canonical exam vocabulary mapping for `rafaga`, `razonado` and `inmersivo`; legacy `guiado` is accepted as a compatibility alias.
- No OCR, no new Storage bucket, and no schema/migration/RPC/RLS change. No live MAIN seed/write was executed during implementation or validation; explicit publish remains the user-controlled final-content write through the existing admin boundary.
- Verification: branch `feature/mobile-content-upload`, commit `9eea7da`; JVM tests, `:composeApp:assembleDevDebug` and `git diff --check` passed. Runtime remains pending because no ADB device was connected.

The legacy `/api/v1/admin/rafagas/import` route remains separate and is not reused by Screen 09.

## Backend readiness note

The backend exposes PDF/import staging and PDFBox-based processing behind authenticated admin routes, now integrated into `integration/mobile-finish`. Source review found a documented non-atomic fallback in admin operations; it remains a P1 boundary and is not promoted to final persistence in this pass — a candidate fix is designed but not applied (see CURRENT RISKS above). Live schema/RLS/RPC/Storage compatibility is not verified because Supabase access was unavailable.
