# POST_OPOSIBOT_LEGACY_CLEANUP

This is a separate deferred package. It is not part of `migrations_candidate_v2` and must not run before OposiBot has been adapted and compatibility-tested.

Required gates:

- OposiBot `/v1/auth/*` replacement/compatibility tests pass.
- `public.usuario` remains empty.
- The profile adapter `oposibots_upsert_own_profile(...)` is no longer required and is absent or replaced.
- No function definition still references `public.usuario`.
- The session variable `oposicontrol.post_oposibot_legacy_cleanup_approved = 'true'` is explicitly set.

The package contains only the deferred legacy function/table cleanup. The
`permite_guiados -> permite_razonados` rename belongs to the pre-OposiBot V2
candidate and is not part of this package. It does not use `CASCADE`.
