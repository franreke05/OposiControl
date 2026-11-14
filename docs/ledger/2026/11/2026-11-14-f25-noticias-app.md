# F25 — Noticias de la app (2026-11-14)

Rama local `f25/news-app` desde develop `08ea7f0f`. Sin push. MAIN/VPS sin tocar.

## Decisión del propietario (2026-11-11, regla ampliada 2026-11-14)

Las noticias que publica Control se ven en el carrusel de Inicio de la app y en su detalle (DEC-030 decidido). Regla:

- Se muestran las noticias de la especialidad principal del usuario; un usuario SIN especialidad ve las de todas.
- Visible = tipo de noticia (`tipo_noticia.activo`) activo y
  - `published` con `publicar_desde` nula o pasada (misma regla que la RLS `noticia_select_publico`), fecha = `published_at`; o
  - `scheduled` con `publicar_desde` pasada: no hay trabajo que la publique, así que se sirve exactamente como publicada, con fecha = `publicar_desde`.
  - Programadas futuras, borradores y archivadas nunca salen.
- Imágenes: el bucket `news-assets` es PÚBLICO; Ktor construye la URL pública del objeto (`storagePublicUrl`) desde la ruta guardada, sin firmar. Una capa `imagen` que no apunta a un objeto de `news-assets` sale vacía.

## Cambio

- `NewsFeed.kt`: `GET /api/v1/app/noticias` (D4-18, cursor `fecha desc, id desc`, límite app) y `GET /api/v1/app/noticias/{noticiaId}` (D4-27, con `texto`). Solo GET a PostgREST con columnas explícitas; `tipo_noticia!inner(activo)` + `tipo_noticia.activo=is.true`. `Cache-Control: private, no-store`.
- Contrato: enmienda **FA49** (TARGET, aditiva): `openapi/news/news-app.yaml` nuevo, rutas en `openapi/root.yaml`, `OpenApiContractTest` (D4-18 cursor 50/20, D4-27), manifiesto de congelación. Inventarios `ops/inventory/routes.bot.txt`, `routes.combined.txt`, `route-profiles.tsv`. Registro en CONTRACT_FREEZE.md (repo OposiBot): PENDING_OWNER.
- Cliente OposiBot (worktree F25, `feature/news/data`): los nombres del cable coinciden (`items`/`nextCursor`; `id, titulo, resumen, fecha, tipoFondo, backgroundUrl, imagenUrl, capas[], texto`; capa `tipo, contenido, x, y, width, height, opacity, rotationDegrees, fontFamily, fontSizeSp, fontWeight, textColor, orden`). Su KDoc dice "signed URLs": ahora son públicas (no caducan); no cambia el código.
- Sin migración: las tablas de noticias ya están en MAIN. Hecho de MAIN anotado (no se corrige aquí, limpieza del propietario): los objetos de `20260923103000_news_canvas_schema` existen pero su versión no está registrada en `supabase_migrations.schema_migrations`.

## Anexo al runbook

1. Imagen Ktor NUEVA (las rutas viven en Ktor). Sin migración previa.
2. Comprobación en producción: `GET /api/v1/app/noticias` con un usuario de prueba devuelve las publicadas de su especialidad (o todas si no tiene).
3. Rollback: imagen anterior (la app trata el 404 como "sin noticias").

## Verificación

`:backend:ktor:test` completo (AppNewsRoutesTest, RouteInventory, OpenApiContract, OpenApiFreezeManifest incluidos).
