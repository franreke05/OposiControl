# PDF Bulk Import System

## Implementaciones encontradas

1. `AdminV1Routes.kt` + `RafagaParser.kt`: PDF real para ráfagas, backend JVM, PDFBox.
2. `ExamsModels.kt` + `ExamsViewModel.kt`: importación de texto Aiken/GIFT desde picker UI; no PDF parsing.
3. `/api/v1/admin/importaciones/preguntas`: API de lotes/staging, pero su creación/importación consume DTOs y no está conectada a un pipeline PDF visible.

## Pipeline real

| Paso | Estado |
|---|---|
| seleccionar PDF | VERIFIED backend multipart; UI específica PDF NOT_VERIFIED |
| upload/read | VERIFIED; 20 MB ráfaga |
| validación MIME/extension | PARTIAL; bytes/multipart, no validación PDF robusta visible |
| extracción texto | VERIFIED PDFBox |
| parser | VERIFIED para formato de bloques |
| split question/fields | VERIFIED |
| validation | VERIFIED TITULO, A-D, RESPUESTA |
| preview | MISSING en ruta PDF |
| correction | PARTIAL vía staging API, no conectado al PDF route |
| duplicate detection/hash | MISSING |
| persist | VERIFIED loop create |
| publish | MISSING en mismo flujo |

Conclusión: `PARTIAL`, no es aún un importador masivo de PDFs de tipos múltiples.
