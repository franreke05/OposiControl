# Canonical V1 migration candidate V2

This is the corrected, pre-OposiBot package. It is candidate-only and intentionally outside `supabase/migrations/`.

## Execution order

1. `001_preflight.sql`
2. `002_canonical_vocabulary.sql`
3. `003_authorization_canonicalization.sql`
4. `004_rls_and_grants.sql`
5. `005_security_definer_grants.sql`
6. `006_storage_and_preservation.sql`
7. `007_plan_entitlements.sql`

V2 does not contain `current_auth_user_id()`, any `is_*` authorization family or legacy cleanup. It reuses `es_admin()` and `es_moderador_o_admin()` as the only canonical authorization source. `can_manage_content()` and `can_moderate()` remain compatibility wrappers delegating to those functions.

All authenticated business-table writes are denied. The five reviewed sensitive tables (`atribucion_usuario`, `usos_promocion_usuario`, `popup_usuario_historial`, `comunidades_objetivo_usuario`, `perfiles_usuario_detalle`) are service-role-only because no direct client consumer was evidenced. Ktor/service-role remains the mutation boundary.

`RAZONADO_ACCESS_POLICY = RAZONADOS_PLAN_CONFIRMED`. `007` reuses `planes_suscripcion` + `beneficios_plan` for entitlements and `productos_comerciales` for the confirmed EUR-cent prices. `tipo_acceso` remains a content/publication classification and is not tied to a fixed plan. No demo, ticket or usage-limit contract is invented.

`capacidades_especialidad.permite_guiados` is renamed to
`permite_razonados` by a guarded block in `007_plan_entitlements.sql`.
The package aborts explicitly if both names or neither name exist, and the
postcondition is exactly one canonical column. The deferred cleanup package
does not contain a second rename path.

`/v1/auth/*`, `public.usuario`, Opositeca, OposiRed, PDF/import staging/parser, `exam-resources` and `news-assets` are preserved.
