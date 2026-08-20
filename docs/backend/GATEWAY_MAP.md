# Gateway Map

Combined mode constructs service-role gateways for content, news, tags, admin V1, app V1, OposiBots users and auth support. Consumer mode is narrower and only constructs the OposiBots auth surface. The source contains no client service-role reference, but the server boundary is concentrated and requires explicit route authorization plus ownership evidence.

El gateway de auth Supabase está bajo `backend/ktor` y su modificación previa en working tree está protegida. El gateway es server-side; sus errores y logs se documentan sin secretos. Live Supabase `NOT_VERIFIED`.
