# Consolidación de ramas — 2026-10-14/15

Modelo resultante: `main` (referencia, `cfa2e14`) y `develop` (Alpha). Ninguna historia reescrita, sin force.
Cada head anterior queda en el tag `archive/2026-10-14/<rama>` (61 tags en origin, incluidos el stash
`stash-kmp-integration` y cuatro `*-wip` con trabajo que estaba sin commitear).

## Destino de cada rama con commits fuera de `origin/main`

| Rama | Únicos | Destino | Motivo |
|---|---:|---|---|
| feature/oposibot-consumer-backend | 8 | MERGED en develop | Login: solicitudes de acceso con verificación de email (FA17, migración 20261020100000) |
| feature/canonical-api-openapi-ext-fa11 | 1 | ARCHIVE_ONLY | Ya en develop como `84e3934` (cherry-pick) y corregido después |
| qa/mobile-runtime-audit | 1 | ARCHIVE_ONLY | Equivalente ya en develop (`1adef0e`) |
| agent/kmp-ktor-supabase-{integration,snapshot} | 1 c/u | ARCHIVE_ONLY | Arquitectura alternativa de julio (módulo `core/api`), abandonada; integrarla rompería develop |
| feature/mobile-opositester-list (WIP) | — | ARCHIVE_ONLY | `minimumInteractiveComponentSize` ya presente en develop |
| feature/mobile-settings (WIP) | — | ARCHIVE_ONLY | Pantalla Preferencias, retirada por `1ed98fd` |
| codex/backend-admin-audit-20260904 (WIP) | — | ARCHIVE_ONLY | Auditoría histórica de 2026-09-04, superada por los registros vigentes |
| OposiControl-MarketPkg (detached, WIP) | — | ARCHIVE_ONLY | `includeAllModules`; develop ya declara `modules(... java.sql ...)` |
| origin/dependabot/* (12 + 1 sin PR) | 1 c/u | ARCHIVE_ONLY | Subidas de versión fuera del alcance Alpha; se reevalúan en un gate propio |
| feature/december-*, feature/december-phase-b-* | 9–12 | EXCLUIDAS (owner) | Trabajo de otra sesión en curso (motor de examen, stack E2E local) |

El resto de ramas (31) no tenían commits fuera de `origin/main`: borradas tras el tag.

## Bloques Alpha integrados en develop (ramas `tmp/*` ya borradas)

| Bloque | Contenido |
|---|---|
| E1 | Tipos de noticia por `/api/v1/admin/tipos-noticia`; Moderación solo «Advertencia» por la ruta canónica; `AuditApi` comprueba el estado HTTP; 37 escritores legacy sin cliente → 410 |
| E3 | Diálogo/botones de conceder tickets muertos quitados (el saldo se ajusta en OposiTickets); Soporte sin «Asignado a» ni filtro de estado inexistente; Informes paginados con notas y recompensa; errores visibles en OposiRed/OposiTester; selector de Accesos sobre el censo |
| E5 | Comunidades y Etiquetas navegables; pantallas muertas, búsqueda global stub, rutas Assets/Eventos y ceros silenciosos eliminados |
| E4 | Noticias avisan de relectura fallida y capas ilegibles; ruta de packs inalcanzable y controles muertos de Market/Importación quitados |
| E2 | `auditoria_log` append-only (20261021100000); vaciar opcionales en guardados comerciales (DEBT-089); borrar cuentas solo superadmin; un superadmin no puede quitarse su rol; Idempotency-Key opcional en soporte y advertencias |
| Analytics | Métricas de rachas y activos de Retención → «Sin instrumentar» (`rachas_usuario` no tiene escritor) |
