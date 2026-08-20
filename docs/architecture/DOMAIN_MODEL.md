# Domain Model

Dominios canónicos: contenido, exámenes, importación PDF, news, support, admin auth e integración OposiBot.

| Dominio | Current | Target/decision |
|---|---|---|
| Exámenes | `rafaga`, `inmersivo` observados | `INMERSIVOS`, `RAZONADOS`, `RAFAGAS`; razonado no verificado |
| PDF | PDFBox backend, Aiken/GIFT UI, staging API | contrato final pendiente de Arturo |
| News | implementación existente documentada en audit | segmentación `SPECIALTY` |
| Support | enums actuales | workflow objetivo y bidireccional |
| Auth | bypass temporal activo | email/password + Google + Apple iOS |
| Integración | boundary OposiBot documentado | consumer compatibility `NOT_VERIFIED` |
