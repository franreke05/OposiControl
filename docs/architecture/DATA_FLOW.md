# Data Flow

## CURRENT_IMPLEMENTATION

El flujo observado/documentado es cliente → Ktor → gateway/servicio → persistencia esperada. Los errores del backend se traducen a respuestas HTTP y modelos del cliente según el código actual; no se declara equivalencia live.

## TARGET_REQUIREMENT: importación

`IMPORT SESSION → STAGING → VALIDATION → REVIEW → DUPLICATE DECISION → FINAL PERSISTENCE`. La publicación debe ser explícita y auditable. Este mapa no implementa ni congela DTOs.

## NOT_VERIFIED

No se ha verificado runtime, persistencia atómica, RLS efectiva, retries ni observabilidad end-to-end.
