# Product Metrics Candidate V1

Tracked candidate and corrective migration source. The authorized MAIN run has
applied `002` through `006` and the runtime corrective `007`; production execution
was explicitly authorized for this run.

## Files

- `001_preflight.sql`: read-only assertions; it must end in rollback.
- `002_event_contract.sql`: versioned `eventos_app`, the sole raw-event table, with mandatory global idempotency keys.
- `003_first_response_integrity.sql`: first response identity independent of ranking eligibility.
- `004_commercial_catalog_contract.sql`: stable product identity plus normalized tariff child table; no provider IDs.
- `005_record_answer_rpc.sql`: atomic, idempotent, authoritative Ktor answer writer.
- `006_backend_only_boundary.sql`: coherent client-role grants and RLS policy boundary.
- `007_record_answer_runtime_fix.sql`: corrective replacement for the runtime `RETURNING` ambiguity found after `005`.
- `008_record_answer_returning_alias_fix.sql`: final corrective replacement qualifying `RETURNING` columns by target alias.
- `009_record_event_idempotency_rpc.sql`: service-role-only event writer with same-key replay and conflict detection.
- `010_admin_grant_tickets.sql`: atomic, idempotent, concurrency-safe wallet grant RPC; service-role execution only.
- `PRODUCT_METRICS_EXECUTION_PLAN.md`: order, gates and non-actions.
- `tests/verify_candidate.ps1`: local static contract and filename verifier; it does not connect to Supabase.

## Safety boundary

Supabase production writes: authorized for the current MAIN finalization run; no
production write is performed outside the tracked migrations and named test fixtures.

OposiBot writes: `NONE`.

`public.usuario`, legacy identity functions, `/v1/auth/*`, Opositeca, OposiRed,
PDF/import, exam-resources, news-assets and `POST_OPOSIBOT_LEGACY_CLEANUP` remain
outside this candidate.

The current source includes the corrective migration required by the MAIN runtime
loop. `POST_OPOSIBOT_LEGACY_CLEANUP` remains outside scope.

The current source also includes tracked wallet migration `010_admin_grant_tickets.sql`,
which is the only active Ktor wallet writer contract.
