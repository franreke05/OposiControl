# Hashira Routing

| Trabajo | Hashira dueño | Revisión mínima | Puerta humana |
|---|---|---|---|
| Endpoint Ktor | Backend/Ktor | Architecture + Quality | Si cambia contrato o seguridad |
| Migración DB | Database | Architecture + Auth/Security + Quality | Siempre para producción, RLS o destructivo |
| Login administrativo | Auth/Security | Architecture + Quality | Siempre fuera de desarrollo local aprobado |
| Pantalla admin | Admin UI/UX | Quality | Si cambia flujo o accesibilidad |
| Parser PDF | Content/Import | Backend/Ktor + Quality | Si cambia formato o persistencia |
| Importación masiva y persistencia | Content/Import + Database | Architecture + Quality | Siempre antes de publicación |
| Contrato OposiBot | Integration | Backend/Ktor + Architecture + seguridad/dominio | Si cambia compatibilidad |
| Release, CI o infraestructura | Release/Infra | Architecture + Quality | Siempre para entorno compartido |
| Second Brain | Documentation | Architecture + dominio afectado | Si cambia una decisión o precedencia |

Reglas: el Hashira que implementa no certifica en solitario; el trabajo transversal se entrega con evidencia a todos los revisores; si falta evidencia o hay contradicción, el estado es `NOT_VERIFIED` y se abre un gate, no una suposición.

## Overlap ownership

- **Auth/Security ↔ Integration:** Auth/Security owns authentication and authorization semantics; Integration owns API/consumer compatibility. Review is reciprocal when both are affected.
- **Admin UI/UX ↔ Content/Import:** Content/Import owns domain behavior and import rules; Admin UI/UX owns presentation, states and usability. UI may not invent business rules.
- **Release/Infra ↔ Backend/Ktor:** Backend owns application/server behavior; Release/Infra owns deployment/runtime environment. Architecture + Quality review changes affecting both.
- **Architecture ↔ Backend/Database:** Architecture owns cross-domain boundaries; Backend/Database own implementation details. Each reviews the other when its boundary changes.
- **Database ↔ Content/Import:** Database owns persistence contract; Content/Import owns validation/import semantics. Bulk persistence requires both owners plus Architecture + Quality.
- **Quality ↔ every implementer:** Quality is an independent reviewer and never the sole implementer/certifier of its own result.
- **Documentation ↔ Architecture:** Documentation owns structure and traceability; Architecture reviews durable decisions and precedence.
