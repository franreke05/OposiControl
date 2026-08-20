# Real Architecture

```text
Compose UI / platform entrypoint
  ↓
Feature Screen + Intent
  ↓
MviViewModel + UiAsync
  ↓
Domain UseCase + Validator
  ↓
Domain Repository interface
  ↓
Data repository / API client
  ↓ HTTP bearer
Ktor route family
  ↓
Gateway
  ↓ PostgREST/Auth/Storage HTTP con service role
Supabase/Postgres
```

## Confirmado

- MVI es real por `Intent/State/ViewModel`, no solo una inferencia.
- Koin registra módulos de datos y features.
- `DataModule` selecciona actualmente `KtorAdminV1ContentRepositoryImpl` para contenido, `KtorNewsRepositoryImpl`, repositorios Ktor para tags/comunidades/moderación/admin, y SQLDelight para estado local.
- Ktor concentra autorización gateway, serialización, errores y acceso service-role.
- `AppV1Routes` usa JWT Supabase verificado por JWKS; el bypass de desarrollo se controla por env.
- No existe worker/queue/background import dedicado; la importación PDF ocurre dentro del request.

## Capas legacy

`Application.kt` aún expone `/content/*` y gateways con tablas antiguas, mientras `AdminV1Routes.kt` usa esquema real documentado en español/plural/UUID. `SupabaseContentRepositoryImpl`, `SupabaseNewsRepositoryImpl` y similares siguen en el árbol, aunque no son el wiring principal actual.

## Clasificación de dominios

Contenido, exámenes, preguntas, noticias, catálogos, usuarios, tickets, soporte, moderación y auditoría: `ACTIVE` o `PARTIAL`. Suscripciones/analytics/notifications: modelos o tablas parciales, sin slice admin completo verificable. OposiBot auth: `ACTIVE` backend, consumidor externo `UNKNOWN` desde este repo.
