# OposiControl Second Brain

Este árbol es la memoria operativa canónica del proyecto. Describe la realidad observada, separa objetivos de implementación y conserva incertidumbres.

## Arranque

Leer: `AGENTS.md` → `docs/README.md` → `docs/brain/SOURCE_OF_TRUTH.md` → `docs/brain/CURRENT_STATE.md` → documento del dominio. Para cada cambio aplicar `.claude/HASHIRA_LAWS.md` y `.claude/HASHIRA_ROUTING.md`.

## Mapa

- `brain/`: verdad operativa, alcance, sistema y roadmap.
- `architecture/`: módulos, flujos, dominio y ADRs.
- `backend/`, `database/`, `integration/`, `product/`, `engineering/`: catálogos especializados.
- `registry/`: componentes, riesgos, deuda, unknowns y contradicciones.
- `governance/`: cómo se actualiza esta memoria.

## Operational Knowledge Map

| Area | Ubicación canónica |
|---|---|
| Arquitectura | `docs/architecture/` |
| Module map | `docs/architecture/MODULE_MAP.md` |
| Product scope | `docs/brain/PRODUCT_SCOPE.md` |
| Endpoint catalog | `docs/integration/ENDPOINT_CATALOG.md` |
| Backend route map | `docs/backend/ROUTE_MAP.md` |
| OposiBot contract | `docs/integration/OPOSIBOT_CONTRACT.md` |
| Database | `docs/database/DATABASE_MAP.md` |
| Migration state | `docs/database/MIGRATION_STATE.md` |
| PDF import | `docs/product/domains/PDF_IMPORT.md` |
| Risks | `docs/registry/RISKS.md` |
| Technical debt | `docs/registry/TECH_DEBT.md` |
| Unknowns | `docs/registry/UNKNOWNS.md` |
| Contradictions | `docs/registry/CONTRADICTIONS.md` |
| Hashira laws/routing | `.claude/HASHIRA_LAWS.md`, `.claude/HASHIRA_ROUTING.md` |

`archive = historical evidence`; `archive != current truth`.

## Active Project Topology

La topología activa observada en filesystem y Gradle es:

```text
composeApp
core/common
core/network
core/database
core/ui
core/testing
domain
data
features/auth
features/dashboard
features/users
features/roles
features/settings
features/audit
features/specialties
features/communities
features/tags
features/news-types
features/news
features/exams
features/exam-access
features/store
features/tickets
features/support
features/moderation
backend/ktor
```

Son 17 módulos de feature activos. El detalle de dependencias y límites está en [`MODULE_MAP.md`](./architecture/MODULE_MAP.md).

## Estados y autoridad

La evidencia live actualizada del alcance Product Metrics V1 está en `docs/brain/CURRENT_STATE.md`: MAIN `ndiooyyqtaeysnedywer` tiene las migraciones `002`–`010` aplicadas y verificadas. La marca global `LIVE_VERIFIED: NO` solo permanece para áreas fuera de ese alcance, como cobertura completa de Storage, legacy y proveedores externos.

Cada documento debe distinguir `CURRENT_IMPLEMENTATION`, `TARGET_REQUIREMENT`, `CONFIRMED_PRODUCT_DECISION`, `PRODUCT_DECISION_PENDING`, `NOT_VERIFIED`, `DOCUMENTED_ONLY` y `LIVE_VERIFIED`. `LIVE_VERIFIED: NO` sigue aplicando a Supabase/Storage fuera del alcance Product Metrics V1; dentro del alcance autorizado sí hubo migraciones y fixtures controlados, todos verificados y limpiados.

Las auditorías y actas cerradas no forman parte del working tree operativo; la información vigente está consolidada en Second Brain, registries y documentos técnicos. Git conserva el historial versionado.
