# Active Risk Registry

Only independent future risks remain here.

| ID | TITLE | SEVERITY | STATUS | NEXT_ACTION | BLOCKER | REFERENCE |
|---|---|---|---|---|---|---|
| RISK-022 | Valid JWT live E2E | P1 external | BLOCKED_EXTERNAL | Obtain a non-personal Auth fixture | External credential | backend/auth |
| PAY-001 | Payment provider contract | P2 external | WAITING_EXTERNAL_PROVIDER | Define checkout, webhook and provider identifiers | Provider decision | ../product/COMMERCIAL_CODE_CONSUMER_MATRIX.md |
| PROD-001 | Product Metrics policy decisions | P1 product | WAITING_PRODUCT_DECISION | Resolve the eight named product policies | Client decision | ../product/CLIENT_DECISIONS_REQUIRED.md |
| PDF-001 | PDF/import publication contract | P1 product | WAITING_PRODUCT_DECISION | Freeze staging, review and atomic publication rules | Client format decision | ../product/domains/PDF_IMPORT.md |
| PDF-002 | Duplicate detection in Screen 09 | P1 implementation | DESIGNED / APPLY_PENDING_APPROVAL | Confirm real schema (`preguntas`, `admin_create_question` signature) and `estado_parseo` constraint, then apply candidate migration | Live Supabase schema access | ../product/domains/PDF_IMPORT.md; ../../supabase/canonical/content_import_atomicity_candidate_v1/ |
| PDF-003 | Atomic final publication | P1 implementation | DESIGNED / APPLY_PENDING_APPROVAL | Same candidate migration as PDF-002 (`admin_import_lote_preguntas`, all-or-nothing) — review, confirm `admin_create_question` signature, apply, then swap the active route to call it | Live Supabase schema access | ../product/domains/PDF_IMPORT.md; ../../supabase/canonical/content_import_atomicity_candidate_v1/ |
| PDF-004 | Screen 09 runtime and JWT E2E | P1 verification | BLOCKED_EXTERNAL | Run the upload/review/publish flow on an available emulator/device with a non-production admin JWT | Runtime device and credential | ../engineering/TESTING.md; integration/mobile-finish (integrated) |
| PDF-005 | Live Supabase/RLS compatibility for Screen 09 | P1 external | BLOCKED_EXTERNAL | Verify deployed schema, RLS and authenticated route behavior with non-production data | Live environment access | ../database/STORAGE.md; ../engineering/TESTING.md; integration/mobile-finish (integrated) |
| PDF-006 | Async import worker | P2 future | DEFERRED | Define worker ownership and retry/observability contract before scaling imports | Internal architecture decision | ../product/domains/PDF_IMPORT.md; integration/mobile-finish (integrated) |
| PDF-007 | Immersive resources association | P2 future | DEFERRED | Define how imported immersive questions attach resources before extending Screen 09 | Internal product/implementation decision | ../product/domains/PDF_IMPORT.md; integration/mobile-finish (integrated) |
| PDF-008 | Original PDF storage policy | P2 product | WAITING_PRODUCT_DECISION | Decide retention, bucket, path and access policy for source PDFs before storing originals | Internal product/storage decision | ../database/STORAGE.md; ../product/domains/PDF_IMPORT.md |
| UI-001 | UI runtime verification | P1 verification | ACTIVE | Execute visual/runtime verification | Runtime environment | ../engineering/TESTING.md |
| LEG-001 | OposiBot, legacy and canonical-vocabulary parity | P1 compatibility | BLOCKED_EXTERNAL | Verify internal/deployed consumers before retiring static token, public.usuario, guiado aliases or legacy routes | Consumer evidence | ../integration/OPOSIBOT_CONSUMER_MATRIX.md and ../product/domains/EXAMS.md |
