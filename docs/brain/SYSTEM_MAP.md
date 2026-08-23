# System Map

Database Redesign V1 is applied through controlled migrations 012, 013 and
014. The service-role boundary, canonical RPC writers and legacy compatibility
boundaries remain unchanged.

## Runtime flow

ARTURO / Compose Multiplatform
  → domain + data repositories
  → backend/ktor
  → Supabase service-role boundary

OposiBot is a separate consumer boundary. It uses /v1/auth/*; its deployed compatibility is not fully verified.

## Components

- Client: composeApp, core/*, domain, data and 17 feature modules.
- Backend: Ktor combined/consumer profiles, legacy routes, V1 routes, app routes, admin routes and OposiBot auth.
- Database: Product Metrics/wallet canonical scope is verified; unrelated legacy/Storage scope remains NOT_VERIFIED.
- Import: PDFBox plus staged/reviewable persistence boundary.

## Canonical boundaries

- Service-role access stays in Ktor; clients do not receive service-role credentials.
- record_answer, record_event and admin_grant_tickets are the canonical Product Metrics/wallet writers.
- public.usuario, /v1/auth/*, Opositeca, OposiRed, exam-resources and news-assets remain compatibility boundaries.
- POST_OPOSIBOT_LEGACY_CLEANUP is deferred until OposiBot adaptation.

## Route families

| FAMILY | ROLE |
|---|---|
| /api/v1/admin/* | current admin surface |
| /api/v1/app/* | authenticated consumer surface |
| /v1/* | public/compatibility surface |
| /content/* and /admin/* | legacy compatibility surfaces |
| /v1/auth/* | protected OposiBot authentication contract |

Detailed ownership is in docs/backend/ROUTE_MAP.md, docs/integration/ENDPOINT_CATALOG.md and docs/architecture/MODULE_MAP.md.
