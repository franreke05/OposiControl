# Security Audit

## Secrets/config

- `.env`: `SECRET_PRESENT`, `SECRET_LOCAL_ONLY` y gitignored; no se imprimieron valores.
- `.env.example`: `PLACEHOLDER`; contiene URL Supabase y nombres de keys, no valores reales.
- `OPOSICONTROL_BACKEND_TOKEN`, service-role, publishable/anon, HMAC y OAuth se leen desde entorno.
- `.mcp.json`: `UNTRACKED`, anuncia un servidor Supabase con `read_only=false`; no se usó. Su presencia merece revisión local antes de compartir el workspace.
- No se detectó secreto real committed por la búsqueda segura realizada.

## Auth / authorization

- Backend admin: bearer estático o JWT con admin role.
- App consumer: JWT Supabase ES256; bypass de usuario solo dev.
- OposiBots auth: register/login/refresh/logout/recovery/OAuth/profile, captcha configurable y rate limit.
- Panel UI: login temporalmente desactivado por constante; todas las secciones aparecen sin filtro de rol.
- Roles modelados (`roleIds`, `app_role`, `roles_usuario`), pero granularidad de permisos por sección no está conectada en navegación.

## Riesgos

`P1`: bypass UI y token estático admin son riesgos operativos si se distribuye panel de producción. `P1`: service role centraliza poder destructivo; depende de Ktor y configuración correcta. `P2`: `.mcp.json` no es read-only aunque el encargo requiere read-only. `NOT_VERIFIED`: headers de producción, TLS, secret rotation, logs y policies live.
