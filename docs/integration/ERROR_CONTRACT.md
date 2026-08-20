# Error Contract

Backend errors and auth gateway mappings are documented from current code as `CURRENT_IMPLEMENTATION`. Expected client behavior must be backed by tests; live behavior is `NOT_VERIFIED`.

Import target policy: valid rows may continue, warnings require review, errors remain draft/correction; no final-table partial write without review.

## Current backend mapping

`AppUnauthorized` maps to 401; known not-found conditions map to 404; `IllegalArgumentException` maps to 400; `IllegalStateException` and unexpected `Throwable` map to 500. Auth routes additionally return endpoint-specific validation, rate-limit and upstream statuses. The generic 500 path is sanitized/truncated in current code, but its global observability and correlation are not verified. Live behavior remains `NOT_VERIFIED`.
