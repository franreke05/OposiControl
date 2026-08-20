# Repository Map

## Topología

| Área | Estado | Evidencia |
|---|---|---|
| `composeApp` | ACTIVE | entry point KMP, roots desktop/mobile y recursos |
| `core/common`, `core/network`, `core/database`, `core/ui`, `core/testing` | ACTIVE/PARTIAL | módulos incluidos en `settings.gradle.kts`; SQLDelight local |
| `domain` | ACTIVE | modelos, contratos, use cases y validación |
| `data` | ACTIVE + LEGACY | repositorios Ktor actuales y `Supabase*RepositoryImpl` antiguos |
| `features/*` | ACTIVE/PARTIAL | 17 features registradas en DI; algunas son read-only o local-only |
| `backend/ktor` | ACTIVE + LEGACY | Ktor, gateways, rutas, parser PDF y auth |
| `supabase/migrations` | PARTIAL | cuatro archivos, no contienen todo el esquema de negocio |
| `docs/supabase` | DOCUMENTED_ONLY/HISTORICAL | DDL, políticas y RPC no equivalentes a migrations aplicadas |
| `scripts` | ACTIVE/OPS | arranque backend y reconexión ngrok |
| `iosApp` | ACTIVE ENTRYPOINT | wrapper Swift/Xcode |
| `.ai`, `.claude`, `.superpowers` | INSTRUCTION/HISTORICAL | material para agentes y planes |
| `.logs`, `.mcp.json` | DIRTY UNTRACKED | preexistentes al inicio; no tocados |

## Módulos Gradle

`composeApp`; `core:common`; `core:network`; `core:database`; `core:ui`; `core:testing`; `domain`; `data`; `features:auth`, `dashboard`, `users`, `roles`, `settings`, `audit`, `specialties`, `communities`, `tags`, `news-types`, `news`, `exams`, `exam-access`, `store`, `tickets`, `support`, `moderation`; `backend:ktor`.

## Source sets

KMP común + Android + iOS (arm64/simulator arm64) + JVM desktop. No target JS/Wasm configurado en `composeApp/build.gradle.kts`; hay recursos `webMain`, pero no una aplicación web compilable verificada.

## Clasificación general

- `ACTIVE`: código registrado en DI y alcanzable desde un root.
- `PARTIAL`: existe el slice, pero falta paridad, persistencia o backend completo.
- `LEGACY`: `/content/*`, gateways singulares/en inglés y repositorios no seleccionados por `DataModule` actual.
- `ORPHAN/UNKNOWN`: no se puede confirmar runtime o consumidor externo desde este repo.
