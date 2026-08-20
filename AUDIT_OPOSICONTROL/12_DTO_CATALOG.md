# DTO / Contract Catalog

## Familias

- Admin V1: `AdminEspecialidadDto`, `AdminExamenDto`, `AdminPreguntaDto`, `AdminOpcionDto`, `AdminRecursoDto`, lotes/staging, users/roles/tickets/support/reports/dashboard.
- App V1: `AppExamenDto`, `AppPreguntaDto`, `AppOpcionDto`, `AppIntentoDto`, estadísticas, reto, feedback y evento.
- OposiBots auth: register/login/refresh/recover/reset/OAuth/profile/session/user/error enums.
- Data KMP: DTOs paralelos en `data/remote/dto` para auth, users, roles, audit y content.
- Legacy: modelos de `AdminGateway`, `SupabaseV1Gateway`, `SupabaseContentGateway` con nombres y tipos distintos.

## Problemas

- Duplicación de DTOs entre rutas legacy/V1 y entre dominio/remote.
- IDs mezclados: actual V1 espera UUID; gateways legacy documentan enteros.
- Enums duplicados por strings (`draft/published/archived`, `rafaga/inmersivo`, roles) y no un único contrato compartido.
- Errores no uniformes: `ErrorDto`, `AppError`, `OposiBotsErrorResponse` y excepciones con mensajes directos.
- Paginación solo visible en usuarios OposiBots y límites ad hoc; no hay contrato transversal.
- Nullable fields representan estados reales de publicación/recursos, pero no siempre distinguen “no cargado” de “vacío”.

Estado: `PARTIAL`; no se detectó un catálogo generado OpenAPI ni versionado de contratos.
