# Technical Debt Registry

| ID | Deuda | Estado |
|---|---|---|
| `DEBT-001` | Drift entre SQL/migraciones/esquema esperado | abierta |
| `DEBT-002` | Coexistencia legacy/V1 | abierta |
| `DEBT-003` | Importación sin boundary final plenamente atómico | abierta |
| `DEBT-004` | Build global roto en audit module | resuelta en OPC-20260819-004; mantener regression gate |
| `DEBT-005` | Contratos OposiBot sin consumer verification | abierta |
| `DEBT-006` | Plugins de plataforma y observabilidad global incompletos | abierta |
| `DEBT-007` | Static backend token y validación/paginación de rutas requieren retirada o endurecimiento | abierta |
| `DEBT-008` | Canonical migration source y reconciliación live pendientes | bloqueada por infraestructura |

Los cambios funcionales futuros deben registrar deuda nueva antes de cerrar.
