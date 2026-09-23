# Legacy Identity Removal Plan

**Status:** `REFERENCE / PLANNED`  
**Canonical identity:** `auth.users.id UUID` → `perfiles_usuario.usuario_id` → `roles_usuario.usuario_id`  
**Legacy identity:** `public.usuario.id integer`, with `auth_user_id uuid` nullable  
**Live snapshot:** `public.usuario` has 0 rows; the table remains protected.

## Consumers to remove or adapt

| Surface | Evidence | Required transition |
|---|---|---|
| `OposiBotsUserAdminGateway.kt` | `/rest/v1/usuario`, integer DTOs | replace with UUID profile/admin adapter after consumer sign-off |
| `OposiBotsAuthGateway.kt` | `/rest/v1/usuario?auth_user_id=...` | route profile completion to canonical profile service; keep auth contract |
| `TagSearchGateway.kt` | `/rest/v1/usuario` lookup | remove identity coupling from tag search |
| `AdminGateway.kt` | `usuario`, `user_role`, `app_role`, integer parsing | migrate admin users/roles to `perfiles_usuario`/`roles_usuario` |
| `Application.kt` legacy content | `especialidad`, `rafaga`, `pregunta`, `recurso_pregunta`, `opcion_pregunta` and integer IDs | adapter to UUID content service, then deprecate route |
| `SupabaseContentRepositoryImpl.kt` | legacy REST resources and integer parsing | UUID repository contract |
| `ContentDtos.kt` | `Int` identifier conversions | string/UUID DTOs; preserve external response only during adapter period |
| Supabase functions/RPCs | `current_usuario_id`, `has_role`, `has_any_role`, legacy access/purchase RPCs | replace/restrict after function matrix and consumer check |
| OposiBot | auth DTO uses `String`, no `public.usuario` direct call | preserve `/v1/auth/*`; server adapter only |
| Tests | legacy gateway and route tests | add UUID parity tests before deletion |

## Safe sequence

1. Confirm `public.usuario` remains empty and capture a live snapshot before each future migration.
2. Add UUID service/adapters and route parity tests; do not dual-write by default.
3. Move profile completion to `perfiles_usuario`/canonical profile capability while retaining the consumed auth response contract.
4. Move admin user/role reads to `roles_usuario` with explicit role codes.
5. Remove identity lookups from tags/content services.
6. Deprecate legacy routes after consumer telemetry and OposiBot/server sign-off.
7. Only then consider dropping `public.usuario`, integer tables, legacy functions and aliases in a separate reviewed migration.

## Hard stops

- Any non-zero `public.usuario` row count.
- Unknown external consumer or deployed alias.
- Missing UUID backfill/ownership proof.
- Unreconciled function/RPC callers.
- PDF/import or Storage path depending on integer IDs.
