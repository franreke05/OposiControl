# Security

`SEC-001: CLOSED / VERIFIED_FOR_CANONICAL_BACKEND_SCOPE`.

The canonical scope has service-role-only writers for `record_answer`, `record_event` and `admin_grant_tickets`, empty `search_path`, no client execution grants and Ktor error mapping for known domain failures. Wallet retry, payload-conflict and concurrency behavior passed controlled MAIN tests. Canonical admin routes require a verified admin JWT; the static token is isolated to local development legacy `/admin/*` and `/content/*` surfaces and is no longer embedded in client builds.

The client login bypass is closed (`TEMPORARILY_DISABLE_LOGIN = false`). `AppV1Gateway.hasAdminRole` uses the canonical `superadmin`/`admin` role set from `roles_usuario` + `roles_app`; moderator remains a separate role and is not promoted to admin.

Service-role and auth-secret references remain in backend gateways/configuration; no service-role reference was observed in client modules. The backend is the intended trust boundary, so route authorization and ownership filters are critical. `OPOSICONTROL_BACKEND_TOKEN` is temporary/legacy and `OPOSICONTROL_ALLOW_DEV_USER`/`OPOSICONTROL_ALLOW_DEV_TOKEN` are dev/test-only controls, not production authorization.

Content negotiation, authentication and status pages are installed. OposiBots auth has its own rate limiting and request IDs; there is no global Ktor `CallLogging`, metrics/tracing, global rate limit, or verified global CORS/HSTS policy. HTTPS/proxy controls are conditional in OposiBots configuration.

The current MAIN scope was verified through Supabase read checks and controlled fixtures; the fixture rows were removed. Full Storage, deployment/proxy and legacy-schema coverage remains `NOT_VERIFIED`.

Remaining external blockers are valid non-personal Auth E2E evidence, complete live Storage policy/object verification and unrelated legacy RPC/consumer reconciliation. They do not alter the closed canonical backend boundary and were not changed in this gate.
