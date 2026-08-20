# Technical Debt

1. Consolidate `/content`, `/v1`, `/api/v1/admin` and `/api/v1/app` contracts with explicit ownership/versioning.
2. Make migrations the canonical schema history; archive or label SQL docs clearly.
3. Separate Ktor route validation, application services and gateways.
4. Remove or quarantine dead legacy repositories after runtime contract confirmation.
5. Replace admin UI bypass with real session/role gating before distribution.
6. Define atomic import/staging/job model before supporting bulk PDFs.
7. Add PDF fixtures and parser/property/failure tests.
8. Centralize DTOs/errors/enums or generate a contract artifact.
9. Add production observability and import audit trail.
10. Verify iOS/JVM implementations against the actual product scope.

This is inventory only; no refactor was performed.
