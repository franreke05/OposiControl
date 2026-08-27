# Active Technical Debt

Database redesign follow-up remains active for entitlement XOR semantics,
session/attempt idempotency, Storage consumer evidence and policy optimization.
See `docs/database/MIGRATION_STATE.md`.

Only concrete independent debt remains here.

| ID | TITLE | STATUS | NEXT_ACTION | REFERENCE |
|---|---|---|---|---|
| DEBT-006 | Runtime observability and platform verification | PARTIALLY_CLOSED | Backend observability foundation integrated (request correlation, structured strategic events, `/health/live`+`/health/ready`, 7 Supabase `HttpClient`s bounded with connect+request timeouts — previously unbounded, confirmed risk, now fixed with tests). Still open: Sentry (`SENTRY_CONFIGURATION_PENDING`, config-only), Prometheus/Grafana (documented, no SDK), physical/emulator runtime verification | ../backend/Observability.kt; ../engineering/DEPLOYMENT.md |
| DEBT-006B | Import atomicity + duplicate detection | DESIGNED / APPLY_PENDING_APPROVAL | See PDF-002/PDF-003 in RISKS.md and `supabase/canonical/content_import_atomicity_candidate_v1/` | ../registry/RISKS.md; ../../supabase/canonical/content_import_atomicity_candidate_v1/ |
| DEBT-010 | Storage object/path inventory | ACTIVE | Inventory objects and consumers before any cleanup | ../database/STORAGE.md |
| DEBT-011 | OposiRed semantic adapter | ACTIVE | Define adapter contract without destructive rename | ../integration/README.md |
