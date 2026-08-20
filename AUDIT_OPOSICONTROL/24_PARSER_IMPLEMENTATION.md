# Parser Implementation

## Backend PDF parser

- Library: PDFBox `3.0.2`.
- Strategy: `PDFTextStripper` → plain text; split by block regex; line parser with known keys.
- Multiline continuation: supported for current field.
- OCR/AI/structured tables: absent.
- Exact 4 options A-D and answer A-D: enforced.
- Tags stored as a text resource after question creation.

## UI parser

- Pure Kotlin object `QuestionImportParser`.
- Normalizes CRLF/CR.
- Detects GIFT first, otherwise Aiken detailed parser.
- Deduplicates by normalized statement, limits to 50, reports duplicate/over-limit counts.
- Creates UUIDs and four options in `ExamsViewModel`, then calls repository bulk import.

## Not proven

No fixture PDF was found, only string parser tests. No parser test covers PDFBox extraction, page breaks, encoding, table layout or OCR.
