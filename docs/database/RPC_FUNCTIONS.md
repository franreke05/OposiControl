# RPC Functions

## Current canonical writers — 2026-08-21

`public.record_answer(...)`, `public.record_event(...)` and `public.admin_grant_tickets(uuid, integer, text, text, text)` are live and verified for the current Product Metrics/backend scope. Each is `SECURITY DEFINER`, uses an empty `search_path`, denies execution to `public`, `anon` and `authenticated`, and grants execution only to `service_role`. Wallet runtime tests covered retry, payload conflict, negative result protection and concurrent calls.

Other legacy RPCs listed below retain their historical/read-only review status and are not changed by this documentation cleanup.

The dated reconciliation below is historical evidence and does not override this current canonical-writer status.

## Live read-only reconciliation — 2026-08-20

Live inspection found `SECURITY DEFINER` functions owned by `postgres`. Broad `PUBLIC`/`anon`/`authenticated` execute grants were observed on access helpers, role helpers and the mutating RPCs `rpc_grant_exam_access(integer,integer,varchar,boolean)` and `rpc_purchase_articulo(integer,integer)`. `ensure_wallet_for_usuario()`, `fn_audit_row_change()`, `handle_new_auth_user()` and `sincronizar_estado_premium_usuario()` were restricted to `postgres`/`service_role`; `oposibots_upsert_own_profile(...)` allowed `authenticated`, `postgres` and `service_role`.

This is a P1 review item, not a finding that the functions are exploitable. Body, ownership, `search_path`, input validation and product authorization still require a controlled security review. No RPC was invoked or changed.

RPCs/functions outside the current canonical writers are `DOCUMENTED`/`CODE_EXPECTED`. Their existence and behavior in live Supabase remain `LIVE_VERIFIED: NO` unless explicitly covered by the current Product Metrics/backend scope above.

No RPC was created, altered or invoked for this reorganization.

Local inventory found 10 function definitions and backend references to administrative/search/OposiBots RPCs. This is not proof that every function is deployed or that grants/search paths are hardened live. Read-only Supabase verification is the next gate.
