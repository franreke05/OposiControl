# Parser Failure Modes

| Caso | Resultado probable/status |
|---|---|
| missing answer / invalid answer | SUPPORTED as error in UI/backend |
| 5 options / 3 options | UNSUPPORTED; exact 4 A-D |
| multiline reasoning | SUPPORTED when continuation field is active |
| multiline question | UI partially supported inside block; PDF parser treats continuation as current field, depends on labels |
| repeated headers/footers | UNSUPPORTED/UNKNOWN |
| page break in question | UNKNOWN; PDFBox emits text, no page-aware logic |
| duplicate question number | no number-based detection; UNKNOWN |
| duplicate statement | UI deduplicates; backend PDF route does not |
| image question | UNSUPPORTED; no OCR/image extraction |
| table question | UNSUPPORTED/UNKNOWN |
| scanned PDF | OCR_MISSING |
| password/corrupt PDF | generic failure path; no friendly validation verified |
| weird whitespace/special chars | some trim/uppercase normalization; full encoding robustness UNKNOWN |

## Limits

UI parser max 50 after deduplication. Backend PDF max 20 MB, but no question count cap or stream-to-disk pipeline is visible.
