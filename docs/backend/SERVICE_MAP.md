# Service Map

Readiness classification: route/service contracts are source-verified; live persistence, RLS, RPC behavior and deployed error semantics are not. Admin V1 has staging/import operations and a documented non-atomic fallback. App V1 applies user-id filters in source, but role authorization and live policy enforcement remain partial/not verified.

Los servicios se catalogan desde el código de `backend/ktor` y la auditoría `10_BACKEND_MAP.md`. Cada servicio debe declarar input/output, auth, persistencia, errores y tests. No se afirma disponibilidad live ni atomicidad no probada.
