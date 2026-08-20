# Content Management

| Dominio | UI | CRUD/API | Estado |
|---|---|---|---|
| Especialidades | Sí | create/read/update/archive en Admin V1; legacy también | FUNCTIONAL/PARTIAL |
| Exámenes/solucionarios | Sí | CRUD, publish/unpublish, cover, order | FUNCTIONAL/PARTIAL |
| Preguntas | Sí | CRUD, options, order, resources | FUNCTIONAL/PARTIAL |
| Ráfagas | Sí | CRUD y preguntas; PDF backend separado | PARTIAL |
| Recursos | Sí | text/url/file, upload/delete/order | PARTIAL; storage runtime unknown |
| Tags/communities | Sí | CRUD y búsqueda | FUNCTIONAL |
| Store | Sí | item/offer/limited/burst models; tolerant optional backend | PARTIAL |
| Settings | Sí | local SQLite/SQLDelight | UI_ONLY/local |
| Publish lifecycle | Sí | draft/published/archived y publishFrom | PARTIAL |

Validación existe en use cases, ViewModels, routes y DB documentada, pero no de forma homogénea. Preview/dry-run editorial de contenido general: `NOT_VERIFIED/MISSING`.
