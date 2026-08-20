# Table Catalog

Catálogo de tablas inferidas desde código y SQL documentado. No es confirmación del esquema live.

| Grupo | Estado | Fuente |
|---|---|---|
| Content/exams | `CODE_EXPECTED` / `DOCUMENTED` | código, SQL y audit |
| News | `CODE_EXPECTED` / `DOCUMENTED` | código y audit |
| Support | `CODE_EXPECTED` / `DOCUMENTED` | código y audit |
| Import/staging | `CODE_EXPECTED` / `DOCUMENTED` | API/parser/docs |

`LIVE_VERIFIED: NO` para todas las tablas; no se listan columnas como live facts si solo provienen de SQL histórico.
