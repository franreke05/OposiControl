# Component Registry

## Core UI

`AdminScaffold`, `SidebarNav`, `ScreenHeader`, `AdminTable`, `FilterBar`, `CrudSplitLayout`, `SectionCard`, `SummaryCard`, `StatusChip`, `GlassSurface`, `GlassBackground`, `GlassInputs`, `GlassChip`, `GlassToggleRow`, `FormSection`, `FormFeedback`, `ConfirmDialog`, `AsyncStatePane`, `EmptyStateCard`.

## Feature-level

Exams exports mobile action pills/icons, content file pickers and editor-specific layers. `MobileAdminPanel` composes users, moderation, access, tickets, support and settings into a second presentation surface.

## Registration

DI feature modules are loaded from `AppModules.kt`; all listed features are wired. No standalone component preview/catalog/test application was found.

## Reuse gaps

Mobile and exams have local action/layout primitives; news/editor and catalog forms contain domain-specific UI. Future component consolidation should wait until runtime UX and product workflows are confirmed.
