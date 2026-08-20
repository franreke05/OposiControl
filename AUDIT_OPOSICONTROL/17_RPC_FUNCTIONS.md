# RPC / Functions

## Funciones documentadas

`admin_sync_roles`, `admin_grant_tickets`, `admin_create_question`, `admin_replace_question_options`, `admin_upsert_search_entry`, `admin_replace_search_index`, `private.admin_upsert_search_entry`, `oposibots_upsert_own_profile`, `oposibots_claim_oauth_credential`, además de triggers/validadores `slugify`, `safe_int`, `safe_uuid`, `handle_updated_at`, `set_slug_from_nombre`, `block_recurso_pregunta_for_rafagas`, validación de solución/opción, tipo de pregunta, recurso inmersivo, respuesta de intento y recalculado de estadísticas.

## Seguridad documentada

- `oposibots_upsert_own_profile`: `SECURITY DEFINER`, `search_path=''`, execute solo authenticated.
- `oposibots_claim_oauth_credential`: `SECURITY INVOKER`, `search_path=''`, execute documentado para service role.
- RPC admin: execute revocado a public/anon/authenticated y concedido a service role.
- Migration 2026-08-03 fija `search_path='public'` en 18 funciones y revoca execute directo de cuatro funciones trigger.

## Estado

Propósito/argumentos/grants según SQL: `DOCUMENTED_ONLY`. Security mode y grants actuales en Postgres: `NOT_VERIFIED`.
