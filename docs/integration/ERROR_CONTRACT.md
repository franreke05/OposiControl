# Error Contract

## Current canonical mapping — 2026-08-21

Known `record_answer`, `record_event` and wallet domain failures are mapped by Ktor to semantic `404`, `409` or `422` responses without exposing database details. Unknown Supabase failures remain internal `5xx`. The mapping is covered by `BackendErrorsTest` and the Ktor test task.

Backend errors and auth gateway mappings are documented from current code as `CURRENT_IMPLEMENTATION`. Expected client behavior must be backed by tests; live behavior is `NOT_VERIFIED`.

Import target policy: valid rows may continue, warnings require review, errors remain draft/correction; no final-table partial write without review.

## Current backend mapping

`AppUnauthorized` maps to 401; known domain failures map to their semantic status; `IllegalArgumentException` maps to 400; `IllegalStateException` and unexpected `Throwable` map to 500. Auth routes additionally return endpoint-specific validation, rate-limit and upstream statuses. The generic 500 path is sanitized/truncated in current code. Full deployed HTTP behavior remains `NOT_VERIFIED` where no valid JWT fixture was available.
