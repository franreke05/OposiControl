# OposiBot Consumer Compatibility Matrix

**Audit date:** 2026-08-20  
**Consumer repository:** `C:\Users\franr\OneDrive\Desktop\OposiBot`  
**Consumer commit:** `e4bf669e3db8f9ecfbd1c07b0a4d459e1a318038`  
**Consumer branch:** `Versión-final-Oposibot`  
**Method:** read-only source/grep/Git inspection. This is the current compatibility source; no OposiBot file was changed.

Classification: `CANONICAL_REFERENCE`. Consumer evidence is maintained here; pending compatibility work is tracked separately in the active registry.

The client runtime is `AuthRuntime.api -> RealAuthApi`. It issues the twelve auth requests below. No active client call was found for `/content/*`, `/v1/*` outside `/v1/auth/*`, `/api/v1/app/*` or `/api/v1/admin/*`; endpoint catalog entries and comments are not treated as consumption.

## Endpoint matrix

| Route | Method | Consumer evidence | Classification | Compatibility action |
|---|---|---|---|---|
| `/v1/auth/login` | POST | `RealAuthApi.login` | `CONSUMED` | MUST_KEEP; Supabase Auth adapter |
| `/v1/auth/register` | POST | `RealAuthApi.register` | `CONSUMED` | MUST_KEEP; Supabase Auth adapter |
| `/v1/auth/register/profile` | POST | `RealAuthApi.completeRegistrationProfile` | `CONSUMED` | MUST_KEEP; legacy profile adapter until UUID profile contract is complete |
| `/v1/auth/recover` | POST | `RealAuthApi.recoverAccess` | `CONSUMED` | MUST_KEEP; preserve anti-enumeration envelope |
| `/v1/auth/reset-password` | POST | `RealAuthApi.resetPassword` | `CONSUMED` | MUST_KEEP; Supabase Auth boundary |
| `/v1/auth/oauth/google` | POST | `RealAuthApi.signInWithGoogle` | `CONSUMED` | MUST_KEEP; provider exchange adapter |
| `/v1/auth/oauth/apple` | POST | `RealAuthApi.signInWithApple` | `CONSUMED` | MUST_KEEP; provider exchange adapter |
| `/v1/auth/email/exchange` | POST | `RealAuthApi.exchangeEmailCode` | `CONSUMED` | MUST_KEEP; session exchange adapter |
| `/v1/auth/email/resend` | POST | `RealAuthApi.resendVerification` | `CONSUMED` | MUST_KEEP; rate-limit contract |
| `/v1/auth/refresh` | POST | `RealAuthApi.refreshSession` | `CONSUMED` | MUST_KEEP; session rotation contract |
| `/v1/auth/logout` | POST | `RealAuthApi.logout` | `CONSUMED` | MUST_KEEP; session invalidation contract |
| `/v1/auth/me` | GET | `RealAuthApi.currentUser` | `CONSUMED` | MUST_KEEP; JWT identity contract |
| `/v1/*` excluding `/v1/auth/*` | GET/other | no active `HttpClient` call found | `NOT_CONSUMED` | candidate for later deprecation only |
| `/content/*` | mixed | no active client call found | `NOT_CONSUMED` | retain for other/unknown consumers |
| `/api/v1/app/*` | mixed | no active client call found | `NOT_CONSUMED` | target API, not an OposiBot contract yet |
| `/api/v1/admin/*` | mixed | no active client call found | `NOT_CONSUMED` | admin-only; never infer client access |
| `/api/v1/admin/oposibots/usuarios*` | mixed | no active client call found | `LEGACY` | server-side admin surface; keep until broader consumer audit |

## DTO and identity evidence

- Auth DTOs are string IDs: `AuthUser.id: String`, no integer user ID is consumed by the client.
- `RealAuthApi` uses bearer JWT/session tokens and `AuthSessionStore`; it does not access Supabase directly.
- Profile request fields are `firstName`, `lastName`, `username`, `specialty`; the backend may temporarily persist through `public.usuario`.
- No client reference was found to `public.usuario` or `oposibots_upsert_own_profile`; those are server/database compatibility details.
- OposiBot has pre-existing protected changes in `shared/.../LoginStateHolder.kt` and `.env.example` files. They were not modified.

## Removal gate

Only the twelve auth routes are proven OposiBot consumers. That is sufficient to preserve `/v1/auth/*`, but not sufficient to delete the route family. Retirement requires server parity tests, a Supabase Auth direct-path decision, error-envelope compatibility, and confirmation that no deployed/other consumer uses the aliases.
