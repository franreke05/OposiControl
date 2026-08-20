# Design System

Existe un sistema parcial y reutilizable.

- Theme central: `AdminTheme.kt`, `GlassTokens`, `lightColorScheme`, Manrope regular/semibold.
- Tokens: background, glass fills/strokes, accent, success, warning, text, divider y shadows.
- Spacing central: `UiSpacing`; métricas: `AppLayoutMetrics`.
- Componentes: glass surfaces/background/inputs/chips, scaffold/sidebar, header, cards, table, forms, dialog, async/empty/error feedback.
- Estados: `StatusChip` y `UiAsync`.
- Iconografía: componentes locales en feature exams/mobile; no se encontró un catálogo de iconos central único.

## Duplicación / deuda

Hay estilos y componentes específicos dentro de `features/exams` y `MobileAdminPanel` además de `core:ui`. Colores Material adicionales aparecen en screens; se requiere revisión visual posterior para decidir si son divergencia o uso intencional.

Estado: `PARTIAL`, suficiente para consistencia base pero no design system completo documentado.
