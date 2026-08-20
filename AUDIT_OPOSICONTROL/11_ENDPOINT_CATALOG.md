# Endpoint Catalog

Todos los endpoints encontrados en código están agrupados por archivo. Auth: `PUBLIC`, `ADMIN_REQUIRED`, `JWT_REQUIRED`, `BEARER_REQUIRED` o `UNKNOWN`.

## `Application.kt`

| Método | Ruta | Auth | Handler/estado |
|---|---|---|---|
| GET | `/health` | PUBLIC | health/config |
| POST | `/admin/backend/restart` | ADMIN_REQUIRED | dev/ops, condicionado por env |
| POST | `/admin/dev/restart-connection` | ADMIN_REQUIRED | dev/ops |
| GET/POST/PUT/DELETE | `/content/especialidades[/{id}]` | ADMIN_REQUIRED | legacy catalog |
| GET/POST/PUT/DELETE | `/content/etiquetas[/{id}]` | ADMIN_REQUIRED | legacy tags |
| GET/POST/PUT/DELETE | `/content/comunidades[/{id}]` | ADMIN_REQUIRED | legacy communities |
| GET/POST/PUT/DELETE | `/content/solucionarios[/{id}]` | ADMIN_REQUIRED | exams legacy |
| POST/DELETE | `/content/solucionarios/{id}/portada` | ADMIN_REQUIRED | image upload, 10 MB |
| GET/POST/PUT/DELETE | `/content/solucionarios/{id}/preguntas[/{preguntaId}]` | ADMIN_REQUIRED | questions legacy |
| PUT | `/content/solucionarios/{id}/preguntas/order` | ADMIN_REQUIRED | reorder |
| GET/POST/PUT/DELETE | `/content/rafaga[/{id}]` | ADMIN_REQUIRED | burst legacy |
| GET/POST/PUT/DELETE | `/content/rafaga/{id}/preguntas[/{preguntaId}]` | ADMIN_REQUIRED | burst questions |
| PUT | `/content/rafaga/{id}/preguntas/order` | ADMIN_REQUIRED | reorder |
| POST/DELETE | `/content/preguntas/{preguntaId}/recursos/{resourceId}/upload` | ADMIN_REQUIRED | resource upload, 50 MB |
| POST/DELETE | `/content/preguntas/{preguntaId}/recursos[/{resourceId}]` | ADMIN_REQUIRED | resource CRUD |
| PUT | `/content/preguntas/{preguntaId}/recursos/order` | ADMIN_REQUIRED | reorder |
| GET/POST/PUT/DELETE | `/content/noticias[/{noticiaId}]` | ADMIN_REQUIRED | news legacy |
| POST | `/content/noticias/assets` | ADMIN_REQUIRED | image asset, 10 MB |
| GET | `/content/tags` | ADMIN_REQUIRED | tag search list |
| GET | `/content/search` | ADMIN_REQUIRED | global search |
| POST | `/content/search/reindex` | ADMIN_REQUIRED | synchronous reindex |

## `V1Routes.kt` — public read API

| GET | `/v1/especialidades` | PUBLIC | list |
| GET | `/v1/examenes` | PUBLIC | filters UUID/type/access |
| GET | `/v1/examenes/{examenId}` | PUBLIC | detail |
| GET | `/v1/examenes/{examenId}/preguntas` | PUBLIC | only available rafaga/free path |

## `AdminV1Routes.kt`

| Método | Ruta | Auth | Handler |
|---|---|---|---|
| GET/POST/PATCH/DELETE | `/api/v1/admin/especialidades[/{id}]` | ADMIN_REQUIRED | AdminV1Gateway |
| GET/POST/PATCH/DELETE | `/api/v1/admin/examenes[/{id}]` | ADMIN_REQUIRED | exam CRUD/archive |
| GET | `/api/v1/admin/examenes/{id}/preguntas` | ADMIN_REQUIRED | list/relation |
| PATCH | `/api/v1/admin/examenes/{id}/preguntas/orden` | ADMIN_REQUIRED | reorder |
| POST | `/api/v1/admin/examenes/{id}/publicar` | ADMIN_REQUIRED | publish |
| POST | `/api/v1/admin/examenes/{id}/despublicar` | ADMIN_REQUIRED | unpublish |
| POST/DELETE | `/api/v1/admin/examenes/{id}/portada` | ADMIN_REQUIRED | multipart image |
| GET/POST/PATCH/DELETE | `/api/v1/admin/preguntas[/{id}]` | ADMIN_REQUIRED | question CRUD/archive |
| GET/POST | `/api/v1/admin/preguntas/{id}/recursos` | ADMIN_REQUIRED | resource list/add |
| PATCH/DELETE | `/api/v1/admin/preguntas/{id}/recursos/{recursoId}` | ADMIN_REQUIRED | resource update/archive |
| GET/POST | `/api/v1/admin/importaciones/preguntas[/{loteId}]` | ADMIN_REQUIRED | import lotes |
| PATCH | `/api/v1/admin/importaciones/staging/{id}` | ADMIN_REQUIRED | staging correction |
| POST | `/api/v1/admin/importaciones/{loteId}/importar` | ADMIN_REQUIRED | persist staging |
| POST | `/api/v1/admin/rafagas/import` | ADMIN_REQUIRED | PDF import |
| GET | `/api/v1/admin/feedback/preguntas` | ADMIN_REQUIRED | feedback list |
| POST | `/api/v1/admin/feedback/preguntas/{id}/resolver` | ADMIN_REQUIRED | resolve feedback |
| GET | `/api/v1/admin/auditoria` | ADMIN_REQUIRED | audit list |
| GET/PATCH | `/api/v1/admin/usuarios[/{id}]` | ADMIN_REQUIRED | admin user CRUD |
| POST | `/api/v1/admin/usuarios/{id}/roles` | ADMIN_REQUIRED | role sync |
| POST | `/api/v1/admin/usuarios/{id}/tickets` | ADMIN_REQUIRED | grant tickets |
| GET/POST | `/api/v1/admin/reportes[/{id}/resolver]` | ADMIN_REQUIRED | reports |
| GET | `/api/v1/admin/roles` | ADMIN_REQUIRED | catalog read |
| GET | `/api/v1/admin/tickets` | ADMIN_REQUIRED | wallets |
| GET/POST | `/api/v1/admin/tickets/{usuarioId}/transacciones` | ADMIN_REQUIRED | transactions |
| GET | `/api/v1/admin/soporte` | ADMIN_REQUIRED | conversations |
| GET/POST | `/api/v1/admin/soporte/conversaciones/{id}/mensajes` | ADMIN_REQUIRED | messages |
| GET/POST | `/api/v1/admin/accesos` | ADMIN_REQUIRED | exam access |
| GET | `/api/v1/admin/moderacion`, `/reportes`, `/actions`, `/acciones` | ADMIN_REQUIRED | moderation reads |
| POST | `/api/v1/admin/moderacion/actions` | ADMIN_REQUIRED | warning/action |
| GET | `/api/v1/admin/settings`, `/ajustes` | ADMIN_REQUIRED | settings |
| GET/POST/PATCH/DELETE | `/api/v1/admin/etiquetas[/{id}]` | ADMIN_REQUIRED | tags |
| GET/POST/PATCH/DELETE | `/api/v1/admin/comunidades-autonomas[/{id}]` | ADMIN_REQUIRED | communities |
| GET | `/api/v1/admin/dashboard` | ADMIN_REQUIRED | metrics |

## `AppV1Routes.kt`

`GET /api/v1/app/home`, `GET /examenes`, `GET /examenes/{id}`, `POST /examenes/{id}/iniciar`, `POST /intentos/{id}/responder`, `POST /intentos/{id}/finalizar`, `GET /estadisticas`, `GET /reto-diario`, `POST /feedback/pregunta`, `POST /eventos`; todos `JWT_REQUIRED` salvo bypass dev controlado por env.

## `OposiBotsAuthRoutes.kt`

`POST /v1/auth/register`, `/email/resend`, `/email/exchange`, `/login`, `/refresh`, `/recover`, `/reset-password`, `/oauth/google`, `/oauth/apple`; `POST /logout`; `GET /me`; `POST /register/profile`. Primer grupo público con rate limit, últimos requieren bearer según handler.

## `OposiBotsUserAdminRoutes.kt`

`GET/POST /api/v1/admin/oposibots/usuarios`, `GET/PATCH/DELETE /api/v1/admin/oposibots/usuarios/{id}`; `ADMIN_REQUIRED`, paginación `limit/offset` y búsqueda.

## Contratos

DTO/request/response están declarados junto a rutas o en `data/remote/dto`. Tests de endpoints backend: sí. Consumidor OposiBot dentro de este repo: no verificable.
