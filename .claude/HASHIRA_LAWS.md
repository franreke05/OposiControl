# Hashira Laws

Estas leyes gobiernan cambios en OposiControl. Son reglas de trabajo y revisión, no evidencia de que una capacidad exista.

- **HL-01 Source of Truth First.** Comenzar por `AGENTS.md`, `docs/README.md` y el Second Brain canónico.
- **HL-02 Runtime and Evidence Outrank Docs.** Runtime observado y pruebas ejecutadas prevalecen sobre cualquier documento.
- **HL-03 Evidence Before Claim.** Toda afirmación verificable debe indicar evidencia o quedar como `NOT_VERIFIED`.
- **HL-04 Domain Ownership.** Cada cambio tiene un dominio dueño y un documento canónico responsable.
- **HL-05 No Silent Cross-Domain Change.** Un cambio que cruce dominios declara impacto y revisores.
- **HL-06 No Self Certification.** El implementador no es el único certificador del cambio.
- **HL-07 Build/Test Before Close.** No cerrar una tarea sin ejecutar pruebas proporcionales y registrar fallos preexistentes.
- **HL-08 No Fake Integration.** No inventar consumidores, endpoints, DTOs, respuestas live o conexiones.
- **HL-09 No Silent Debt.** Toda deuda nueva o descubierta entra en el registro correspondiente.
- **HL-10 ADR for Durable Decisions.** Las decisiones duraderas de arquitectura o gobernanza requieren ADR.
- **HL-11 Current-State Synchronization.** Todo cambio transversal actualiza `CURRENT_STATE.md` y el registro afectado; las podas documentales no crean diarios históricos duplicados.
- **HL-12 Current State Synchronization.** `CURRENT_STATE.md` se actualiza al cerrar una intervención transversal.
- **HL-13 Stop on Contradiction.** Una contradicción no se tapa con documentación; se registra y se detiene el supuesto afectado.
- **HL-14 Protect Dirty Work.** Los cambios previos del working tree son protegidos hasta contar con autorización explícita.
- **HL-15 Explicit Git Staging.** No se hace staging ni commit implícito.
- **HL-16 Human Gates.** Producción, migraciones, RLS, borrados, publicación y decisiones de producto tienen puerta humana.
- **HL-17 Archive Before Destruction.** Preservar evidencia antes de borrar, mover o sustituir documentación.
- **HL-18 Never Expose Secrets.** No copiar secretos, tokens, `.env`, dumps ni settings locales al Second Brain.
- **HL-19 Contract Ownership.** El dueño del contrato mantiene versión, consumidores conocidos y compatibilidad verificable.
- **HL-20 Documentation Ownership.** Un documento canónico tiene dueño, estado, fecha y límites explícitos.
- **HL-21 Database Changes Require Canonical Migration.** Todo cambio DB durable futuro debe pasar por migración canónica, ADR si corresponde, ledger y `CURRENT_STATE`; hoy no se declara cuál es la fuente canónica porque existe drift.
- **HL-22 No Unreviewed Bulk Persistence.** Ningún proceso masivo puede escribir silenciosamente en tablas finales sin validación, trazabilidad, revisión y política explícita.
- **HL-23 Admin Auth Required Outside Approved Local Development.** Un build o entorno real no puede depender del bypass de login; el bypass actual es riesgo abierto y queda fuera de esta fase.
- **HL-24 UI Must Preserve Operational Usability.** La UI administrativa debe conservar densidad operativa, medidas explícitas, accesibilidad y adaptación móvil sin trasladar tablas desktop sin diseño.

## Estados obligatorios

Usar `CURRENT_IMPLEMENTATION`, `TARGET_REQUIREMENT`, `CONFIRMED_PRODUCT_DECISION`, `PRODUCT_DECISION_PENDING`, `NOT_VERIFIED`, `DOCUMENTED_ONLY` y `LIVE_VERIFIED: NO` según corresponda. No convertir un target en implementación por repetirlo en un ADR o registry.
