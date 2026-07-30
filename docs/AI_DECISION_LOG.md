# AI Decision Log

Registro cronológico resumido. El detalle normativo vive en
[`DECISIONS.md`](DECISIONS.md).

## 2026-07-29 — D-001: congelación de Noticias

- **Fuente:** alcance explícito del plan aprobado.
- **Impacto:** ninguna edición en features, gateway, editor, SQL, endpoints o
  métricas de Noticias; solo regresión compartida.
- **Responsable:** coordinador y Enciclopedia.
- **Estado:** `CONGELADA`.
- **Compatibilidad:** comportamiento previo obligatorio; plan específico
  posterior.

## 2026-07-29 — D-002/D-005: modelo canónico

- **Fuente:** plan; migraciones `001` y `007`.
- **Impacto:** esquema español singular, IDs enteros y ráfaga como
  `examen.tipo_examen`.
- **Responsable:** Backend.
- **Estado:** `ACEPTADA`, integración `PARCIAL`.
- **Compatibilidad:** legacy solo lectura temporal si hay consumidores.

## 2026-07-29 — D-003/D-004: frontera de seguridad

- **Fuente:** plan; migración `002`; `BackendSecurity.kt`.
- **Impacto:** Auth directo con publishable key; negocio por Ktor; actor desde
  `sub`; JWT/JWKS/sesión/RBAC.
- **Responsable:** Backend y coordinador.
- **Estado:** `PARCIAL`.
- **Compatibilidad:** puentes heredados deben estar autenticados y no recibir
  nuevas capacidades.

## 2026-07-29 — D-006/D-007: atomicidad e idempotencia

- **Fuente:** plan; migraciones `003`, `005`, `006`; gateways Ktor.
- **Impacto:** cuatro opciones/una correcta, recursos privados, importación
  atómica, Idempotency-Key durable y PATCH optimista.
- **Responsable:** Backend.
- **Estado:** `PARCIAL`; bloqueado hasta pruebas de RPC, concurrencia y Storage.
- **Compatibilidad:** payload heredado solo para backfill; reintento idéntico
  conserva respuesta y cuerpo distinto da `409`.

## 2026-07-29 — D-008: `core/api`

- **Fuente:** plan; módulo `core/api`.
- **Impacto:** DTO compartidos fuera de dominio y base `/api/admin/v1`.
- **Responsable:** coordinador y Backend.
- **Estado:** `PARCIAL`.
- **Compatibilidad:** se adoptan los nombres reales del módulo y se exige
  migración para renombrarlos.

## 2026-07-29 — D-009/D-010: estado y presentación

- **Fuente:** plan; reglas del proyecto; skills de MVI y UI.
- **Impacto:** Route/Content, State/Action/Effect, IDs estables, glass y medidas
  exactas.
- **Responsable:** Lógica y UI/UX.
- **Estado:** `PARCIAL`.
- **Compatibilidad:** la UI puede reorganizarse sin cambiar paleta ni tono.

## 2026-07-29 — D-011/D-012: operación

- **Fuente:** plan; implementación de logging; `deploy/` y runbooks.
- **Impacto:** correlación segura, Caddy HTTPS, Ktor interno, secretos externos
  y rollback por imagen.
- **Responsable:** Logs, Backend y coordinador.
- **Estado:** `PARCIAL`.
- **Compatibilidad:** alias service-role únicamente para Noticias congelada;
  migraciones aditivas durante rollback.
