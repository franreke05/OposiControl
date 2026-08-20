# Migration Drift

## Resultado

`SEVERE_DRIFT` como riesgo de gobernanza; estado runtime exacto `NOT_VERIFIED`.

## Evidencia

- `supabase/migrations` solo contiene cuatro SQL con fecha 2026-07/08: RLS/perfil OposiBots, índices FK, initplan RLS y hardening de funciones/grants.
- DDL de tablas, storage y muchas policies vive en `docs/supabase/*.sql`, no en una cadena completa de migrations.
- `docs/superpowers/plans/...` registra que la instancia live tenía migration history vacía y que se aplicaron cambios directamente en agosto; eso es documentación histórica, no consulta actual.
- Código usa simultáneamente familias singular/inglés y plural/español/UUID.

## Drift observado

| Elemento | Estado |
|---|---|
| tablas esperadas por código vs DDL documental | PARTIAL_DRIFT |
| columnas/PK | PARTIAL_DRIFT: int/UUID y nombres diferentes |
| policies/RLS | UNKNOWN actual; definiciones repartidas |
| RPC/admin atomic functions | DOCUMENTED_ONLY/UNKNOWN aplicado |
| triggers/functions | DOCUMENTED_ONLY; migrations solo endurecen subset |
| migration history productiva | DOCUMENTED_ONLY: plan dice vacía al inicio de agosto |

No se ejecutó ninguna SQL ni se modificó Supabase.
