# Exams Domain

## CURRENT_IMPLEMENTATION

Verified names include `rafaga` and `inmersivo`.

## TARGET_REQUIREMENT

Product types are `INMERSIVOS`, `RAZONADOS`, `RAFAGAS`; `razonado` is `TARGET_REQUIRED / IMPLEMENTATION_NOT_VERIFIED`.

Immersive resources may be displayed after answering. This documents behavior; it does not freeze a new DTO.

## Competitive read boundary

Competitive read models must require both `es_primer_intento_pregunta = true` and `cuenta_para_ranking = true`. The first-response contract is enforced by the Product Metrics migrations; repeated responses remain learning/history data and must not enter competitive ranking.
