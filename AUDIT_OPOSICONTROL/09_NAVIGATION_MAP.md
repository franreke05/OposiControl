# Navigation Map

```text
DesktopModerationRoot
  → AppRoot
      ├─ Auth (implementado, desactivado por TEMPORARILY_DISABLE_LOGIN)
      └─ AdminShell
          └─ cualquiera de AdminSection.entries (17)
```

`AppNavigator` solo mantiene un `StateFlow`; no hay deep links, URL routes, historial/back stack ni permisos por sección.

## Entradas y destinos

`DashboardQuickAction.REVIEW_PENDING`, `IMPORT_QUESTIONS` y `AUDIT_RESOURCES` → `QUESTIONS_RESOURCES`; `CREATE_NEWS` → `NEWS`; `CREATE_EXAM` → `EXAMS`; `ACTIVATE_OFFER` → `STORE`; `REVIEW_TICKETS` → `TICKETS`; `OPEN_AUDIT` → `AUDIT`.

## Hallazgos

- Todas las `AdminSection` son visibles por defecto (`AdminSection.entries`); no hay filtro por rol.
- `SUPPORT` no tiene acción primaria.
- No se detectaron rutas placeholder en `AppDestination`; sí existe una pantalla Auth que queda inalcanzable mientras el flag sea true.
- `MobileContentRoot` usa una navegación/contenedor distinto, no un mapa equivalente a las 17 secciones.
- Reachability real y botones muertos: `NOT_VERIFIED` sin ejecución visual.
