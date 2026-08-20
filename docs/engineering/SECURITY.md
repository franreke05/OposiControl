# Security

`SECURITY_BASELINE: PARTIAL`.

The client login bypass is closed (`TEMPORARILY_DISABLE_LOGIN = false`) and admin routes require bearer authentication. Authorization is still partial: `AppV1Gateway.hasAdminRole` checks that some assignment exists in `roles_usuario`, but does not enforce a specific administrator role. `ADMIN_ROLE_MODEL` remains a product decision.

Service-role and auth-secret references remain in backend gateways/configuration; no service-role reference was observed in client modules. The backend is the intended trust boundary, so route authorization and ownership filters are critical. `OPOSICONTROL_BACKEND_TOKEN` is temporary/legacy and `OPOSICONTROL_ALLOW_DEV_USER`/`OPOSICONTROL_ALLOW_DEV_TOKEN` are dev/test-only controls, not production authorization.

Content negotiation, authentication and status pages are installed. OposiBots auth has its own rate limiting and request IDs; there is no global Ktor `CallLogging`, metrics/tracing, global rate limit, or verified global CORS/HSTS policy. HTTPS/proxy controls are conditional in OposiBots configuration.

The Supabase live audit was not executed: the connector was not connected and the CLI is not installed. Project, tables, RLS, RPC, Storage, applied migrations and advisors are therefore `NOT_VERIFIED / INFRASTRUCTURE_GAP`. No Supabase mutation occurred.

No `BACKEND_BASELINE_READY` declaration is justified until role authorization, live Supabase verification, schema reconciliation and deployment/proxy validation are closed.
