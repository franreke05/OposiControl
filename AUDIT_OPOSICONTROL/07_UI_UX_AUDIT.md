# UI / UX Structural Audit

## Resultado global

`ACCEPTABLE` en shell y consistencia general; `NEEDS_IMPROVEMENT` en escalabilidad, paridad y algunos flujos. No se rediseñó nada.

## Fortalezas verificadas

- Sidebar/header y acción primaria compartidos por `AdminScaffold`.
- Componentes reutilizables: `AdminTable`, `FilterBar`, `CrudSplitLayout`, `ConfirmDialog`, `AsyncStatePane`, `EmptyStateCard`, `StatusChip`, `FormFeedback`.
- Estados `Loading`, `Error`, `Empty`, `Success` representados en gran parte de ViewModels.
- Confirmaciones para borrado en varios flujos y feedback de guardado en cambios recientes.

## Riesgos

- `BoxWithConstraints` se usa en tema y scaffold; el proyecto pide exactitud y este mecanismo calcula escalas por viewport. Es una tensión con la regla local, no necesariamente un bug.
- `AdminTable` calcula anchos por pesos (`172.dp`) y scroll horizontal; no hay paginación común verificable.
- Existen medidas y escalas distintas desktop/mobile; la auditoría visual real no puede confirmar responsive/accessibility.
- Login oculto por constante; acciones visibles no sustituyen autorización.
- Form validation y errores no están unificados entre gateways legacy y V1.
- No se verificaron navegación de teclado, lector de pantalla, focus management ni tamaños táctiles en runtime.

## Clasificación por capacidad

Navegación/shell `GOOD`; tablas/filters `ACCEPTABLE`; feedback/error `ACCEPTABLE`; responsive/accessibility `NOT_VERIFIED/NEEDS_IMPROVEMENT`; acciones destructivas `ACCEPTABLE` en UI inspeccionada; escalabilidad de listas/importación `NEEDS_IMPROVEMENT`.
