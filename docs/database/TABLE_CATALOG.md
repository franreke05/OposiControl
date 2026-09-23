# Table Catalog

Current scope exception: the Product Metrics tables and wallet objects used by migrations `002`–`010` were inspected in MAIN on 2026-08-21. This exception does not promote the entire legacy/V1 catalog to live-verified status. The blanket baseline below applies to that legacy/V1 catalog.

Catálogo de tablas inferidas desde código y SQL documentado. No es confirmación del esquema live.

| Grupo | Estado | Fuente |
|---|---|---|
| Content/exams | `CODE_EXPECTED` / `DOCUMENTED` | código, SQL y audit |
| News | `CODE_EXPECTED` / `DOCUMENTED` | código y audit |
| Support | `CODE_EXPECTED` / `DOCUMENTED` | código y audit |
| Import/staging | `CODE_EXPECTED` / `DOCUMENTED` | API/parser/docs |

`LIVE_VERIFIED: NO` para todas las tablas salvo las excepciones anotadas; no se listan columnas como live facts si solo provienen de SQL histórico.

## Excepción — 2026-09-02

`public.productos_comerciales` pasa a `LIVE_VERIFIED` (lectura): el endpoint admin
`GET /api/v1/admin/comercial/productos`, contra el proyecto MAIN configurado en
`.env`, devolvió filas reales (`free` y `rafagas`, `tipo_producto = suscripcion`).
Esto confirma la tabla y los campos leídos por ese endpoint; **no** promueve
`tarifas_producto_comercial`, `planes_suscripcion`, `beneficios_plan` ni
`capacidades_especialidad`, que siguen siendo `CODE_EXPECTED` porque no se
ejercitaron con datos en esta sesión.

Verificado además que Supabase responde: `/health/ready` devolvió
`{"ktor":"up","supabase":"up"}`. Ninguna escritura fue ejecutada.

## Inventario en vivo — 2026-09-02

Inspección de sólo lectura del esquema expuesto por PostgREST (OpenAPI): **90
tablas y vistas**, muchas más de las que este catálogo asumía. Esto invalida la
suposición previa de que las métricas de producto no estaban instrumentadas.

Vistas y RPC relevantes ya existentes:

- `v_usuario_debilidades_etiqueta` — debilidades por etiqueta.
- `v_preguntas_dificultad_global` — dificultad de preguntas.
- `recalcular_estadisticas_usuario_globales` / `_etiquetas` / `_preguntas`,
  `recalcular_estadisticas_globales_preguntas` / `_etiquetas` — rollups.
- `admin_grant_tickets`, `record_answer`, `record_event`, `rpc_purchase_articulo`,
  `rpc_grant_exam_access`, `can_access_*`, `es_admin`, `has_role`.

Clasificación observada (conteos exactos, sin leer datos personales):

| Estado | Tablas |
|---|---|
| `LIVE_VERIFIED` (con filas) | `perfiles_usuario` (3), `carteras_usuario` (2), `transacciones_cartera` (16), `suscripciones` (1), `ligas_nivel` (5), `productos_comerciales` |
| `SCHEMA_EXISTS_NOT_POPULATED` | `perfiles_usuario_detalle`, `estadisticas_usuario`, `estado_competitivo_usuario`, `rachas_usuario`, `estado_opositester_usuario`, `servicios`, `paquetes_opositickets`, `reglas_popup_comercial`, `promociones`, `sesiones_estudio`, `atribucion_usuario` |
| `MISSING` (referenciada por rutas legacy) | `articulo_tienda`, `noticia` — sus rutas devuelven 500 en ejecución; ver RISKS.md LEG-002 |

`ligas_nivel` está sembrada con el modelo documentado: `liga_50` (mínimo 50,
ventana 50) y `liga_200`/`500`/`800`/`1000` (ventana 100). Las ligas ya son
configuración, no código.
