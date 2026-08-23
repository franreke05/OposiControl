# Service Map

## Canonical V1 reconciliation — 2026-08-20

The service layer is `ADAPTER_REQUIRED` where UUID live tables coexist with integer DTO/repository parsing and legacy routes. Admin/import, PDF, commerce, news and OposiBot services must retain compatibility until consumer and live-policy evidence is complete. This audit made no service behavior change.

Readiness classification: route/service contracts are source-verified; live persistence, RLS, RPC behavior and deployed error semantics are not. Admin V1 has staging/import operations and a documented non-atomic fallback. App V1 applies user-id filters in source, but role authorization and live policy enforcement remain partial/not verified.

Los servicios se catalogan desde el código de `backend/ktor` y la auditoría `10_BACKEND_MAP.md`. Cada servicio debe declarar input/output, auth, persistencia, errores y tests. No se afirma disponibilidad live ni atomicidad no probada.
