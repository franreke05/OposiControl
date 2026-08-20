# Admin Auth Domain

## CURRENT_IMPLEMENTATION

Login bypass is closed by `TEMPORARILY_DISABLE_LOGIN = false`; the panel now returns to the authenticated flow. Backend admin routes require bearer authentication.

Authentication entry is controlled, but authorization remains `PARTIAL`: `hasAdminRole` checks for any role assignment rather than an explicit administrator role. `ADMIN_ROLE_MODEL` remains pending and must not be guessed. Static backend token and dev flags remain temporary/dev-only controls.

## TARGET_REQUIREMENT

Email/password, Google and Sign in with Apple for iOS.

## PRODUCT_DECISION_PENDING

`ADMIN_ROLE_MODEL` is pending. Do not hardcode `Arturo == admin`.
