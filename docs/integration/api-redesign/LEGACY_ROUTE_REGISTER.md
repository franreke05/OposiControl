# Registro de rutas legacy

Fuente de verdad maquina: [`legacy-route-register.tsv`](legacy-route-register.tsv) (una fila por `METHOD path`). **No se borra ninguna
ruta**: hay clientes publicados (OposiBot) o instalados (OposiControl) cuya presencia en el campo no se puede descartar, y la prueba
de "cero trafico" necesita el contador `legacy_route_requests_total{endpoint="ID"}` (`LegacyRoutes.kt`). Aun no hay fecha `Sunset`
aprobada por el propietario en ninguna ruta.

| Clase | Criterio | Rutas |
| --- | --- | --- |
| `RETIRE_AFTER_TELEMETRY` | Sin llamador vivo en los repos; un OposiBot antiguo instalado es DESCONOCIDO. Retirar solo cuando el contador valga 0 durante la ventana acordada (p. ej. 30 dias) tras la release canonica. | 9 `APP-*` (`/api/v1/app/{home,estadisticas,commercial/*,eventos,feedback/pregunta,examenes/{id}/iniciar,intentos/{id}/responder}`) y 5 `BOT-ADMIN-*` (`/api/v1/admin/oposibots/usuarios*`, sucesor `/api/v1/admin/usuarios`); y los 7 escritores legacy `POST/PUT/DELETE /admin/users*` y `POST/PUT /admin/roles*` (EP-152/153/224/001/147/148/222, RC-10), que ya responden 410 `ADMIN_LEGACY_ROUTE_DISABLED` antes de leer el cuerpo o de cualquier I/O upstream (escribian sin la guarda MASTER-AUTH-001 ni auditoria) pero siguen montados y contados |
| `KEEP_FOR_PUBLISHED_CLIENTS` | Un cliente vivo las llama. Mantener hasta que exista la sucesora ruteada y el cliente migre. | `GET /v1/auth/me` y `POST /v1/auth/register/profile` (OposiBot; sucesoras `D1-11`/`D1-12`), `/content/noticias*` y `/admin/news-types` (OposiControl; sucesoras no implementadas), `/admin/moderation/actions` (EP-144) y `/api/v1/admin/dashboard` (EP-046) hasta que OposiControl migre, y las `/v1/*` publicas con llamador OC |
| `UNKNOWN` | Sin llamador encontrado y sin contador. Exige `owner:`; primero se anade `.legacyRoute(...)` y despues se clasifica. | `GET /v1/examenes/{id}` y `GET /v1/examenes/{id}/preguntas` |

`RETIRE_NOW_SAFE` no existe: ninguna ruta lo justifica. Las rutas admin/`content` huerfanas sin marca legacy no estan aqui: son
las filas `ADMIN_CONTRACT_GAP` con `consumer=NONE` de `ops/inventory/undocumented-routes.tsv` (clase UNKNOWN de hecho; no se desmontan
antes de que un contador demuestre cero).

## Garantias automaticas (`LegacyRouteRegisterTest`)

- Toda hoja con `legacyRoute(...)` esta en el registro con su contador `legacy_route_requests_total{endpoint="<id>"}` y un
  `endpointId` real de `LegacyEndpoints`; una ruta marcada y no registrada falla.
- Una fila de una ruta que ya no se sirve falla (borrar exige telemetria a cero y editar el registro a proposito).
- `UNKNOWN` sin `owner:` falla; una clase fuera de las tres falla.
- El registro coincide con las filas `LEGACY_PUBLISHED_CLIENT_TELEMETRY_PENDING` de `ops/inventory/undocumented-routes.tsv`.
- A nivel de cable, `X-Usuario-Id` sin JWT da 401 con el flag resuelto por `allowsDevBypass("production"|"", true)` y se acepta
  solo en desarrollo (`allowDevUser` esta en las 9 rutas `APP-*`).
