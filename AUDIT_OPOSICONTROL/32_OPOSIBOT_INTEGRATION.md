# OposiBot Integration

## Clear contracts

| Capability | Endpoint/family | Auth | Status |
|---|---|---|---|
| register/login/session | `/v1/auth/*` | public + bearer where required | VERIFIED backend |
| profile completion | `/v1/auth/register/profile` | bearer | VERIFIED backend |
| public exam catalog | `/v1/*` | public | VERIFIED code |
| consumer home/exams | `/api/v1/app/*` | Supabase JWT | VERIFIED code |
| answer/finalize attempt | `/api/v1/app/intentos/*` | JWT + ownership checks | VERIFIED code |
| feedback/event telemetry | `/api/v1/app/feedback`, `/eventos` | JWT | VERIFIED code |
| admin OposiBots user CRUD | `/api/v1/admin/oposibots/usuarios` | admin gateway auth | VERIFIED backend/tests |

The App V1 responder explicitly checks attempt ownership, question membership, option membership and duplicate answer in source.

## Unknown

No OposiBot client repository is present; consumers, version compatibility, deployed base URL and live contract compatibility are `UNKNOWN`. The plan document mentions an external OposiBot path, but that is `DOCUMENTED_ONLY`.
