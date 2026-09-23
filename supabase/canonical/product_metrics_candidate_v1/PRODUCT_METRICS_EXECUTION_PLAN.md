# Product Metrics Candidate V1 Execution Plan

Status: tracked candidate; `002`–`006` and corrective `007`–`008` were applied to the
authorized MAIN project during this finalization run.

## Order

1. `001_preflight.sql` — read-only schema, data, policy and grant assertions.
2. `002_event_contract.sql` — extend the existing `eventos_app`; no second raw-event table.
3. `003_first_response_integrity.sql` — enforce one real first response per user/question.
4. `004_commercial_catalog_contract.sql` — add the internal product/tariff child-table contract.
5. `005_record_answer_rpc.sql` — install the authoritative, service-role-only answer writer.
6. `006_backend_only_boundary.sql` — remove direct client mutations after Ktor/RPC and event-contract tests pass.
7. `007_record_answer_runtime_fix.sql` — corrective migration added after the first MAIN runtime call exposed an ambiguous `RETURNING` target in `record_answer`.
8. `008_record_answer_returning_alias_fix.sql` — final corrective migration qualifying `RETURNING` columns by the target alias after the first corrective runtime retry.
9. `009_record_event_idempotency_rpc.sql` — service-role-only event writer that replays identical keys and rejects conflicting payloads.

`006` is intentionally after `005`: applying the boundary before the Ktor writer is
available would break the current direct REST answer insert. The Ktor source in this
repository is aligned to the RPC; OposiBot is not modified.

`007` is not a planned baseline migration; it is a tracked corrective migration for
the runtime defect found after `005`. It must not be substituted for or renamed over
the already-applied `005` migration.

`008` is a second tracked corrective migration; it must not be substituted for or
renamed over any already-applied migration.

10. `010_admin_grant_tickets.sql` — atomic, idempotent and concurrency-safe wallet grant RPC; service-role-only execution.

`009` and `010` are tracked extensions discovered during runtime hardening. They must not be substituted for or renamed over any already-applied migration.

## Invariants and contracts

- `es_primer_intento_pregunta` means first real response for `usuario_id + pregunta_id`.
- `cuenta_para_ranking` is true only when the attempt is eligible and the response is the first real response.
- `record_answer` locks the attempt, validates the answer server-side, returns an existing same-attempt answer on retry, and handles the cross-attempt unique race.
- `eventos_app.id` remains server row identity. Every event producer must supply a stable, nonblank globally unique `idempotency_key`; the Ktor endpoint rejects unkeyed events and `002` enforces `NOT NULL`.
- `productos_comerciales` is stable internal product identity; `tarifas_producto_comercial` stores period/phase prices. Provider IDs belong only in `productos_store` after a provider contract exists.
- Existing commercial codes remain `free`, `rafagas`, `razonados`, `inmersivo`; no SKU is seeded here.
- `INMERSIVO_PLAN_QUOTA = WAITING_PRODUCT_DECISION` and is not inferred from `acceso_inmersivo`.

## Explicit non-actions

- Do not reapply already-registered migration names or move candidate files into `supabase/migrations` without a separately tracked release decision.
- Do not run `POST_OPOSIBOT_LEGACY_CLEANUP`.
- Do not modify OposiBot, `public.usuario`, `/v1/auth/*`, Opositeca, OposiRed, PDF/import, exam-resources or news-assets.
- Do not seed unresolved product decisions: `DAILY_CHALLENGE_REWARD_POLICY`, `COMMUNITY_INACTIVE_THRESHOLD`, `INMERSIVO_PLAN_QUOTA`, `RESOURCE_SAVE_ANALYTICS`, `PREDICTIVE_COMPARABLE_COHORT`, `DIFFICULTY_TIME_QUINTILES`, `INACTIVITY_AND_SESSION_SEMANTICS`, `POPUP_FREQUENCY`.
- Do not clean up protected requirements objects.

## Required dry-run gates

- Run `001` against a disposable production-shaped clone and inspect notices/errors.
- Verify RPC retry, cross-attempt concurrency, event idempotency, period/phase pricing and provider uniqueness.
- Verify authenticated direct mutations fail while own SELECT remains available.
- Run the project test matrix in the hardening report.

## Rollback

Rollback is separately reviewed: restore previous grants/policies and remove only the
named candidate index/constraints/function after consumers are migrated. Retain added
nullable event/catalog columns until all readers stop using them. No `DROP ... CASCADE`.
