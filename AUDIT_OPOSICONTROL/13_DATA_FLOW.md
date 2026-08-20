# Data Flow

## Admin contenido actual

`ExamsScreen` → `ExamsViewModel` → `Import/Upsert/Get use case` → `ContentRepository` → `KtorAdminV1ContentRepositoryImpl` → `/api/v1/admin/*` → `AdminV1Gateway` → PostgREST service-role → tablas pluralizadas documentadas.

## News/catálogos

`NewsScreen`/catalog screens → ViewModel → repository Ktor → `Application.kt` legacy o `AdminV1Routes` según repositorio → gateway → Supabase. La familia concreta debe comprobarse por repository; no hay un gateway único.

## Consumer

OposiBot externo → `/v1/auth/*` o `/api/v1/app/*` → validación de bearer/JWT → `AppV1Gateway` → PostgREST.

## Importación PDF ráfaga

```text
multipart file
 → límite 20 MB
 → PDFBox text extraction
 → split --- PREGUNTA ---
 → fields TITULO/A/B/C/D/RESPUESTA/...
 → validation
 → loop createPregunta + optional etiqueta resource
 → 201 count
```

No hay preview ni transacción visible. Importación UI de preguntas sigue otro camino: picker texto → Aiken/GIFT parser → DTOs generados en cliente → bulk request del repository.

## Integridad

Se observan operaciones compuestas (pregunta + opciones + recursos + índice) repartidas entre request/gateway. RPC atómicas existen documentadas, pero no se confirmó que sean el camino activo de todas las rutas.
