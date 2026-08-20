# Observability

- `LoggerFactory`/Logback was added in dirty preexisting changes for OposiBots auth gateway/routes.
- Ktor logs some Supabase/unexpected errors to stderr and tracks request IDs for auth operations.
- `AnalyticsTracker` exists in client ViewModels, but concrete sink/production delivery is not proven.
- Admin audit domain/API exists (`/api/v1/admin/auditoria`, `audit_log` documented).
- No metrics backend, tracing, request correlation across all routes, alerting or error tracking service found.
- Import audit fields (who/when/file/result/rejected) are not written by PDF route; only returned count is visible.

Status: `PARTIAL`; production observability `NOT_VERIFIED`.
