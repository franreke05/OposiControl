# PDF Import Domain

## CURRENT_IMPLEMENTATION

- Backend parser uses PDFBox.
- UI supports/mentions Aiken and GIFT.
- A staging API exists in the current code/docs.
- Current PDF scope is digital/selectable text; OCR is `NOT_REQUIRED_CURRENT_SCOPE`.
- Existing audit documents parser failures, validation, transactions and duplicate concerns.

## CURRENT RISKS

Non-atomic persistence, partial errors, duplicate handling and publication boundaries remain open. Do not insert directly into final tables.

## TARGET_PRODUCT

`IMPORT SESSION → STAGING → VALIDATION → REVIEW → DUPLICATE DECISION → FINAL PERSISTENCE`; valid rows can continue, warnings require review, errors remain draft/correction. Duplicate policy is `WARN_AND_DECIDE`, default `SKIP`, never silent replace. Import must not silently publish.

## WAITING CLIENT INPUT

`PDF_FORMAT_CONTRACT: STAGED / WAITING_CLIENT_INPUT Arturo`. The final format is not available. Do not invent regex, template, delimiters or definitive parser rules.

Owner of missing information: `Arturo / Client`.

## DO NOT IMPLEMENT YET

No parser rewrite, DTO freeze, schema/migration/RPC/RLS/storage change, final persistence change or UI flow change is authorized by this reorganization.

## Backend readiness note

The backend exposes PDF/import staging and PDFBox-based processing behind authenticated admin routes. Source review found a documented non-atomic fallback in admin operations; it remains a P1 boundary and is not promoted to final persistence in this pass. Live schema/RLS/RPC/Storage compatibility is not verified because Supabase access was unavailable.
