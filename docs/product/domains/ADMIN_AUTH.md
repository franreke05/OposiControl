# Admin Auth Domain

## CURRENT_IMPLEMENTATION

Login bypass is closed by `TEMPORARILY_DISABLE_LOGIN = false`; the panel now returns to the authenticated flow. Backend admin routes require bearer authentication.

Authentication entry is controlled, but live end-to-end authorization remains `PARTIAL`: `hasAdminRole` requires the canonical `admin`/`superadmin` role codes through `roles_usuario` and `roles_app`; a client-side role label is not trusted. `ADMIN_ROLE_MODEL` remains pending for product confirmation and must not be guessed. Static backend token and dev flags remain temporary/dev-only controls.

## TARGET_REQUIREMENT

Email/password, Google and Sign in with Apple for iOS.

## PRODUCT_DECISION_PENDING

`ADMIN_ROLE_MODEL` is pending. Do not hardcode `Arturo == admin`.
