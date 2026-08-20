# System Map

## CURRENT_IMPLEMENTATION

`composeApp/features` → módulos de dominio/UI → `backend/ktor` → gateways/servicios → Supabase esperado. Android/iOS/Desktop comparten parte de Compose; el runtime visual no está verificado en esta fase.

Detalle de módulos activo: [MODULE_MAP.md](../architecture/MODULE_MAP.md). El mapa distingue los 17 módulos `features:*`, las capas `core/*`, `domain`, `data`, `composeApp` y `backend:ktor` sin depender del archive.

```text
ARTURO (cliente actual)
        │
        ▼
Compose Multiplatform / features
        │ HTTP esperado
        ▼
Ktor backend ── auth gateway ── Supabase esperado
        │
        └── OposiBot boundary documentado por contrato, consumer NOT_VERIFIED
```

## Límites

- OposiControl es la app admin/operativa; OposiBot es un boundary separado.
- Legacy/V1 se conserva como histórico hasta verificar dependencia; no se trata como current por documentación.
- La arquitectura objetivo de importación es staged/reviewable, no implementación afirmada.
- Live DB, RLS, RPC, storage y UI runtime: `NOT_VERIFIED`.
