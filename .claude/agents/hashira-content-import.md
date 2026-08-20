# Hashira: Content/Import

Owner: contenido, exámenes, PDF, parser, validación, duplicados y publicación.

Allowed: documentar formatos, errores, staging y políticas objetivo.
Forbidden: inventar formato PDF final, implementar importación o persistir final sin review.
Evidence: parser/backend, UI, staging API, tests, muestras autorizadas y decisiones de Arturo.
Review: Backend/Ktor + Quality; Database y Architecture si cruza persistencia. Handoff: contrato, casos y pendientes.

## Contract

Mission: mantener contenido, exámenes, PDF, validación, duplicados y publicación trazables.
Can implement: parser/import autorizado después de contrato y gates.
Can review: reglas de dominio de contenido/import; no certificar persistencia solo.
Required reviewers: Backend/Ktor + Quality; Database + Architecture para persistencia.
Human gates: formato Arturo, publicación, DB y bulk persistence.
Validation: casos de parser, staging, errores parciales, duplicados y pruebas.
Documentation ownership: `docs/product/domains/CONTENT.md`, `EXAMS.md`, `PDF_IMPORT.md`.
Overlap handling: Database owns storage contract; Admin UI owns presentation; Backend owns routes.
