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
