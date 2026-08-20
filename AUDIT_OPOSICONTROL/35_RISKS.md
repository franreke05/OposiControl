# Risks

## P0

No P0 confirmed from repository-only evidence. A real committed secret was not found. Live RLS/secrets remain unverified, so absence of P0 is not a production security certification.

## P1

- `P1-001 AUTH`: admin UI bypass enabled by constant; panel can open without login.
- `P1-002 SCHEMA`: severe migration/schema drift and multiple gateway families can route writes to incompatible contracts.
- `P1-003 IMPORT`: PDF loop has no visible transaction, preview or staged correction; failure can leave partial data.
- `P1-004 BUILD`: global test/compile fails in audit feature.
- `P1-005 PRIVILEGE`: backend service-role and static bearer concentrate broad administrative power.

## P2

- `P2-001`: PDF no OCR, no duplicate hash/idempotency, no progress/retry/cancel.
- `P2-002`: legacy routes/gateways remain active and duplicate contracts.
- `P2-003`: news layer/publish persistence and optional commerce schema are partial.
- `P2-004`: settings are local-only despite admin UI wording.
- `P2-005`: missing consistent pagination/error/contract versioning.
- `P2-006`: platform implementations suggest iOS/JVM parity gaps.

## P3

Accessibility verification, central icon registry, UI visual consistency, documentation consolidation and reusable import fixtures.
