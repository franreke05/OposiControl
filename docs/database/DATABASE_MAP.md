# Database Map

## Estado

La base de datos está descrita por código/SQL/documentos y por lectura live autorizada. Estado: `LIVE_VERIFIED: READ_ONLY_ONLY`. No hubo writes, migraciones, RPC funcionales, RLS ni Storage changes en esta fase.

| Fuente | Clasificación |
|---|---|
| Código que espera tablas/queries | `CODE_EXPECTED` |
| SQL y docs bajo `docs/supabase` | `DOCUMENTED` |
| Lectura live Supabase | `LIVE_VERIFIED: READ_ONLY_ONLY` |

Las dos migraciones live ausentes se recuperaron exactamente desde `supabase_migrations.schema_migrations.statements` y se repusieron en `supabase/migrations/`. La migración local `20260730120000_oposibots_auth_profile_rls.sql` coincide semánticamente con `20260803092306`; se clasifica como `DUPLICATE_HISTORY`, no se elimina en esta fase.

El drift documentado impide declarar una única migración canónica. Ver [MIGRATION_STATE](./MIGRATION_STATE.md).
