# AI Token Index

Fecha: 2026-07-29
Objetivo: localizar la evidencia mínima antes de cargar archivos extensos.

## Lectura inicial obligatoria

| Prioridad | Ruta | Contiene | Leer cuando | Coste |
|---|---|---|---|---|
| 1 | `AGENTS.md` | reglas locales de edición, UI y pruebas | siempre | bajo |
| 2 | `docs/AI_SESSION_STATE.md` | estado de la sesión y bloqueos | siempre | bajo |
| 3 | `docs/IMPLEMENTATION_STATUS_2026-07-29.md` | matriz vertical y No-Go | siempre | medio |
| 4 | `docs/DECISIONS.md` | decisiones vinculantes | antes de diseñar | medio |
| 5 | `docs/AI_CONTEXT_MAP.md` | mapa completo de arquitectura | al cambiar de capa | medio |

## Índice por tema

| Tema | Ruta primaria | Ruta de contraste | Nota |
|---|---|---|---|
| Módulos/targets | `settings.gradle.kts` | `README.md` | Gradle manda sobre README |
| Versiones | `gradle/libs.versions.toml` | `build.gradle.kts` de cada módulo | comprobar target real |
| DTO/rutas | `core/api/src/commonMain/.../api/` | rutas de `backend/ktor` | declarar no equivale a servir |
| Registro Ktor | `backend/ktor/.../Application.kt` | `CanonicalAdminRoutes.kt`, `OperationalAdminRoutes.kt` | revisar legacy y canónico |
| JWT/RBAC | `BackendSecurity.kt` | migración `002`, `RouteAuthorization.kt` | actor siempre desde `sub` |
| HTTP Supabase | `SupabaseHttp.kt` | gateways | revisar token, retry, timeout |
| Esquema | migración `001` | `docs/supabase/oposicontrol_real_schema_alignment.md` | singular + ID entero |
| RLS/RBAC | migración `002` | pgTAP | probar conducta, no solo estructura |
| RPC operativas | migración `003` | `OperationalAdminGateway.kt` | firmas deben coincidir |
| Backfill | migración `004` | `007` | aditivo; no borrar legado |
| Storage | migración `005` | `CanonicalAdminGateway.kt` | premium privado |
| Import/reordenación | migración `006` | editor de exámenes | 1–50 y rollback total |
| Ráfagas | migración `007` | `examen.tipo_examen` | campaña ≠ tipo de examen |
| Wiring cliente | `data/.../DataModule.kt` | repositorios Ktor | muestra implementación activa |
| Auth cliente | `AuthRepositoryImpl.kt` | `SecureSessionStore` por target | no SQL/token claro |
| Cliente HTTP | `core/network/.../ApiClientFactory.kt` | tests MockEngine | refresh, retry y IDs |
| MVI | `core/common/.../MviViewModel.kt` | feature migrada | intents FIFO + Effect |
| UI | `core/ui/.../theme`, `layout`, `components` | screens | medidas exactas |
| Logs | `docs/observability/logging-runbook.md` | `core/common/.../logging` | allowlist y correlación |
| VPS | `docs/deployment/backend-production-runbook.md` | `deploy/` | Docker/Caddy/Supabase CLI |
| Noticias | `features/news`, `features/news-types`, `NewsGateway.kt` | estado Git | solo regresión, no edición |

## Archivos grandes: acceso dirigido

- `Application.kt`: empezar por `module()`, registro de rutas y helpers de
  errores; abrir gateways heredados solo si el corte aún los consume.
- Migraciones: leerlas en orden. No interpretar una RPC sin leer también las
  migraciones posteriores que puedan reemplazar su firma.
- Screens grandes: localizar primero `Route`, `ScreenContent`, `UiState`,
  `Action/Intent` y `Effect`; después leer el bloque concreto.
- `CanonicalAdminGateway.kt`: buscar la interfaz y la operación concreta;
  contrastar siempre con su RPC/migración y contrato DTO.

## Comandos de orientación

```powershell
rg --files -g '*.md'
rg --files core/api supabase/migrations backend/ktor/src
rg -n 'route\\(\"|get\\(\"|post\\(\"|patch\\(\"|put\\(\"' backend/ktor/src
rg -n 'Ktor.*Repository|bind<|single<|factory<' data features
git status --short
git diff --name-only -- features/news features/news-types backend/ktor/src/main/kotlin/com/oposicontrol/backend/NewsGateway.kt
```

No repetir lecturas si el archivo no cambió. Si otro agente lo está editando,
esperar su estado final y leer solo el diff nuevo más el contexto afectado.
