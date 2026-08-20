# Import Validation

## UI text import

Validates non-empty file, presence of `ANSWER`, exactly four A-D options, answer in A-D, duplicate statements and max 50. Errors cancel the whole UI import before repository call if any issue exists.

## Backend PDF import

Validates file presence, 20 MB limit, title, A-D fields and answer. It does not visibly validate exam metadata beyond UUID query params, duplicate questions, source/year/opposition/category, MIME as PDF, or content uniqueness.

## Layer

| Layer | Evidence |
|---|---|
| UI | `QuestionImportParser`, ViewModel, domain use case |
| parser | `RafagaParser` field checks |
| service | gateway validation/DTO requirements, mixed |
| database | documented constraints/triggers, current NOT_VERIFIED |

Foreign keys and option/question integrity are documented in SQL and checked in App V1 path, but not proven for the PDF loop.
