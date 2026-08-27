# PDF Import Domain

## CURRENT_IMPLEMENTATION

- Backend parser uses PDFBox.
- UI supports/mentions Aiken and GIFT.
- A staging API exists in the current code/docs, including the authenticated mobile multipart PDF entrypoint.
- Current PDF scope is digital/selectable text; OCR is `NOT_REQUIRED_CURRENT_SCOPE`.
- Existing audit documents parser failures, validation, transactions and duplicate concerns.

## CURRENT RISKS

Non-atomic persistence, partial errors, duplicate handling and publication boundaries remain open. The mobile flow inserts into staging first; final publication uses the existing row-level admin import boundary and reports partial results instead of claiming atomic success.

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

The legacy `/api/v1/admin/rafagas/import` route remains separate and is not reused by Screen 09.

## Backend readiness note

The backend exposes PDF/import staging and PDFBox-based processing behind authenticated admin routes. Source review found a documented non-atomic fallback in admin operations; it remains a P1 boundary and is not promoted to final persistence in this pass. Live schema/RLS/RPC/Storage compatibility is not verified because Supabase access was unavailable.
