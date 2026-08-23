# Gateway Map

## Canonical V1 reconciliation — 2026-08-20

Live Supabase is now read-only verified for this audit. Gateway duplication remains: legacy content/admin gateways use integer/legacy REST resources, while app/admin V1 gateways target UUID-era capabilities. `OposiBotsAuthGateway` and `OposiBotsUserAdminGateway` remain protected adapters because no external OposiBot consumer repository or handshake was verified. No gateway was refactored or removed.

Combined mode constructs service-role gateways for content, news, tags, admin V1, app V1, OposiBots users and auth support. Consumer mode is narrower and only constructs the OposiBots auth surface. The source contains no client service-role reference, but the server boundary is concentrated and requires explicit route authorization plus ownership evidence.

El gateway de auth Supabase está bajo `backend/ktor` y su modificación previa en working tree está protegida. El gateway es server-side; sus errores y logs se documentan sin secretos. Live Supabase `READ_ONLY_VERIFIED / RECONCILIATION_PARTIAL` para esta auditoría.
