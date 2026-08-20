# Source of Truth

Current cleanup ledger: `docs/ledger/2026/08/OPC-20260820-005_repository-cleanup-and-consolidation.md`.

## Precedencia

1. Comportamiento runtime observado.
2. Pruebas ejecutadas.
3. Configuración activa.
4. Supabase/storage verificado en lectura.
5. Código fuente ejecutado.
6. Este Second Brain.
7. ADRs aceptadas.
8. Contratos y documentos especializados.
9. Registries.
10. Ledger.
11. Archivo histórico y evidencia.

Una fuente superior puede invalidar una afirmación inferior. La ausencia de observación live no se rellena con una inferencia del SQL o de un documento antiguo.

## Estados obligatorios

- `CURRENT_IMPLEMENTATION`: observado en código/runtime actual.
- `TARGET_REQUIREMENT`: objetivo no necesariamente implementado.
- `CONFIRMED_PRODUCT_DECISION`: decisión confirmada por el encargo/product owner.
- `PRODUCT_DECISION_PENDING`: falta decisión humana.
- `NOT_VERIFIED`: no probado o no observado.
- `DOCUMENTED_ONLY`: existe solo como documentación.
- `LIVE_VERIFIED: NO`: Supabase/storage no ha sido verificado live en esta fase.

## Límites de identidad

El producto de este repositorio es OposiControl/OposiBot con cliente actual confirmado `ARTURO`. `Claudia` y `CaravanTruck` pertenecen a otro contexto y no se mezclan sin evidencia de repositorio/contrato.

## Punteros canónicos

- Estado operativo: [CURRENT_STATE.md](./CURRENT_STATE.md)
- Arranque de producto y decisiones: [PRODUCT_SCOPE.md](./PRODUCT_SCOPE.md).
- Detalle especializado: [docs/README.md](../README.md) y su Operational Knowledge Map.
- Decisiones duraderas: [ADRs](../architecture/adr/README.md).
- Riesgos, deuda, unknowns y contradicciones: [RISKS.md](../registry/RISKS.md), [TECH_DEBT.md](../registry/TECH_DEBT.md), [UNKNOWNS.md](../registry/UNKNOWNS.md), [CONTRADICTIONS.md](../registry/CONTRADICTIONS.md).
- Historial de intervenciones: [ledger](../ledger/).
- Alcance: [PRODUCT_SCOPE.md](./PRODUCT_SCOPE.md)
- Mapa de sistema: [SYSTEM_MAP.md](./SYSTEM_MAP.md)
- Decisiones: [ADRs](../architecture/adr/README.md)
- Riesgos y unknowns: [registry](../registry/RISKS.md), [UNKNOWNS.md](../registry/UNKNOWNS.md)
- Sesión de reorganización: [ledger](../ledger/2026/08/OPC-20260819-001_second-brain-hashira-reorganization.md)
