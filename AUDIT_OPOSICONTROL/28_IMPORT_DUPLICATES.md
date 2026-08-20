# Import Duplicates / Idempotency

- UI parser: deduplicates within one text file by lowercase trimmed statement; reports count.
- Backend PDF parser: no hash/checksum, source ID, import ID lookup or fingerprint visible.
- DB models include import lot/staging tables and `lote_importacion_id` in documented schema, but PDF route directly creates questions and does not show idempotency key.
- Re-uploading identical PDF: `UNKNOWN`, likely duplicates unless DB constraints/gateway reject them.
- Duplicate exams: no generic PDF-level detection visible.

Status: `MISSING` for explicit idempotency in PDF pipeline; `UNKNOWN` for effective DB behavior.
