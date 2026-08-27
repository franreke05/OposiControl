# ADR-008: Bulk import is staged and reviewable

Status: `CONFIRMED_PRODUCT_DECISION` · Date: `2026-08-19`

Decision: bulk import passes through session, staging, validation, review and duplicate decision before final persistence/publication.

Evidence: confirmed product direction; Screen 09 implements the session/staging/review path in `feature/mobile-content-upload` at commit `9eea7da`.
Consequence: no auto-replace or silent success is allowed. The current publish boundary is row-level, reports `parcial` when errors or skips exist, and still requires a product/database decision for duplicate handling and atomic final persistence.
