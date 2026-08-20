# ADR-008: Bulk import is staged and reviewable

Status: `CONFIRMED_PRODUCT_DECISION` · Date: `2026-08-19`

Decision: bulk import passes through session, staging, validation, review and duplicate decision before final persistence/publication.

Evidence: confirmed product direction; current non-atomic risks are documented in the audit.
Consequence: no silent partial final writes or auto-replace; implementation remains pending and requires Database + Content/Import + Quality review.
