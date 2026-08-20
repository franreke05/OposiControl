# PDF Formats Found

## Formato PDF de ráfaga

Evidence: `RafagaParser.kt` and route `POST /api/v1/admin/rafagas/import`.

```text
--- PREGUNTA ---
TITULO: ...
A: ...
B: ...
C: ...
D: ...
RESPUESTA: A|B|C|D
RAZONAMIENTO: ...
FUENTE: ...
ETIQUETA: tag1,tag2
```

Fields required: `TITULO`, `A`, `B`, `C`, `D`, `RESPUESTA`. Optional: `RAZONAMIENTO`, `FUENTE`, `ETIQUETA`. Continuation lines append to current field; separator regex is `---\s*PREGUNTA\s*---`.

Images/tables/headers/footers/page numbers/metadata: not explicitly supported by parser. Scanned PDF/OCR: not implemented. Password/corrupt PDF: generic exception path, exact UX unknown.

## Text formats in UI

- Aiken-like: question block, `A.`/`B)` etc, `ANSWER: X`; exactly four options.
- GIFT-like: `::Title::statement{~wrong =correct ~wrong ~wrong}`; exactly four tokens after parse.

These are text contracts, not evidence that arbitrary PDFs in either format are accepted.

Exam types found in models: `inmersivo`, `rafaga`; access `premium`, `gratuito`; statuses `DRAFT`, `PUBLISHED`, `ARCHIVED`. No additional parser-specific exam type was verified.
