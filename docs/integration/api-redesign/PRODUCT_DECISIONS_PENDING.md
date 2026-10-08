# Product decisions pending

Single list of what the backend is NOT implementing because a product (or owner-level security) decision is open.
A blocked capability does not block its slice: everything else in the slice is implemented or scheduled.
Source of the contract ids and DEC ids: `OPENAPI_EXTENSION_PLAN.md` (this folder) and the decision register
`PHASE3_DECISION_GATE.md` (OposiBot repo). Updated 2026-10-08.

## 1. Contracts blocked by an open product decision (37)

| Area | Contracts | Decision |
|---|---|---|
| Exams: quick quiz | D2-04 | DEC-021 |
| Leagues / rankings / streaks | D3-02, D3-09, D3-10, D3-11, D3-12 | DEC-003, DEC-004, DEC-006, DEC-012 |
| Streaks | D3-04 | DEC-006 |
| Home banner | D4-18 | DEC-030 |
| OposiTeca visibility of explanations/resources | D4-22, D4-23, D4-24, D4-25 | DEC-025 |
| OposiTeca (unregistered proposal) | D4-26 | PROP-006 (no DEC yet) |
| OposiRed (feed, posts, comments, reactions, moderation of posts) | D5-02, D5-03, D5-04, D5-05, D5-07, D5-11, D5-13, D5-31 | DEC-022, DEC-023, DEC-024, DEC-029 |
| OposiTester | D5-17 | DEC-027, DEC-040 |
| Rewards | D6-04 | DEC-036 |
| Payments / purchases / provider | D7-04, D7-10, D7-13, D7-14, D7-15, D7-16, D7-20 | DEC-031 |
| Subscriptions | D7-06 | DEC-034 |
| In-app support channel | D8-10, D8-11, D8-12, D8-13 | not registered as a DEC yet |
| User settings | D8-14, D8-15 | DEC-041 (TECH default proposed) |

External: D7-03 (payment provider verification) needs the provider chosen in DEC-031.

## 2. Values left null by contract until a decision (implemented, shape fixed)

| Field | Where | Decision |
|---|---|---|
| `precio` | exam detail D2-02 | DEC-035 |
| score formula (technical default `correct / evaluable * 10` in use) | result D2-12 | DEC-013 |
| reveal of the correct answer before finish, answer change | D2-07, D2-09 (nothing revealed, answer locked once given) | DEC-015 |
| pause / resume, deadline | attempts (not offered) | DEC-014 |
| `comparativa`, `probabilidadPlaza`, `pctAciertoGlobal` | result D2-12 | DEC-004, DEC-016, DEC-019 |
| explanation / resources in review | D2-65 | DEC-025, DEC-040 |
| `recompensas` | wallet D6-01 | DEC-036 |

## 3. Defaults taken by engineering that the owner may want to change

- "My collection" (D2-13): ordered by latest activity (grant or attempt); an attempted exam without current access is listed as locked.
- An open attempt created by the retired legacy route is closed as abandoned when the user starts the exam again.
- Legacy routes `POST /api/v1/app/examenes/{id}/iniciar` and `/intentos/{id}/responder` answer 410.

## 4. Owner-level security decisions (not product, but not purely technical)

- Data API surface: clients never call it, but RLS still lets an authenticated user read some of its own rows directly
  (for example correctness of its own answers before finishing an attempt) and write a few own-row tables
  (`registros_reto_diario`, `sesiones_estudio`, `configuraciones_usuario`, `preguntas_favoritas_usuario`, ...). Closing it
  fully means revoking client writes table by table; content-authoring tables are out of scope (protected flows).
- `/health/ready` of the bot profile does not include Valkey (contract D8-08).
- Identity cache (performance): caching the profile lookup for a few seconds delays the effect of a block or deletion by the
  same time.
- `private.oposibots_oauth_credential` has RLS disabled (0 rows, schema `private` is not exposed by the API).

## 5. Wallet reads (D6-01, D6-02): defaults to confirm

- Ledger type -> origin (not defined by the design; proposal in use, isolated in `WalletOrigins`): `recarga`/`pack_tickets` -> compras,
  `bonus_racha` -> rachas, `reto_diario` -> retos, `referido` -> invitaciones, `reporte_validado` -> opositester, `regalo` -> recompensas,
  anything else -> otros.
- `porOrigen` covers the current month; periods use Europe/Madrid, week starts on Monday.
- Balance, recent movements and earnings are separate reads (no single snapshot); a read RPC would give one.

## 6. Noted by the final review (2026-10-08), not blocking

- Scheduled news: with the current read policy a `scheduled` article never becomes visible on its date by itself (no
  publishing job; the public policy requires `published`). Decide the mechanism before the consumer feed (D4-18): either
  `programar` stores `published` with a future `publicar_desde`, or the feed includes `scheduled` rows whose date has passed.
- Legacy news list decodes `capas` strictly while the canonical route stores them as opaque objects: a canonical article with
  a mistyped known layer key would break the legacy list page until fixed (admin only).
- Wallet reads answer 502 `UPSTREAM_CONTRACT_ERROR` on corrupt upstream rows; `openapi/wallet/wallet.yaml` declares 503 only.

## 7. NEEDS_SCHEMA (not a product decision: a schema object is missing in production)

Checked against the data-free production DDL capture. Nothing below was implemented around the gap.

| Contract | Missing object | Effect today |
|---|---|---|
| D5-23 moderation action log | `acciones_moderacion` has no target columns (only `reporte_id`) | not implemented; the legacy list stays as it is |
| D5-24 moderation action command | target columns on `acciones_moderacion`; `fijar` and `desfijar` missing from `acciones_moderacion_tipo_accion_check`; one function for "target state + action + audit" | not implemented; the existing warn-user route is unchanged |
| D8-02 support conversations | `conversaciones_soporte.asignado_a` (field and filter `asignadoA`) | not implemented as canonical (see section 8) |
| D8-03, D8-04 support messages | an internal-note column on `mensajes_soporte`; for D8-04 also one function for "message + touch conversation + audit" | internal notes stay rejected with 422 (owner decision 2026-10-01) |
| D5-28 feedback list (implemented) | `feedback_pregunta` has no resolution note column | `resolucionNota` is always null |
| D7-27 subscription list (implemented) | `suscripciones` has no cancellation instant column | `canceladaEn` is always null |
| D6-19 market service upsert | a function that replaces a service and its `efectos_servicio` rows in one transaction | not implemented; the existing save of the service row is unchanged |
| D7-23 plan benefits replace | a function that replaces the `beneficios_plan` set of a plan in one transaction | not implemented; the existing one-benefit save is unchanged |
| D7-29 promotion use log (implemented) | an aggregate function for the total discount (row-by-row sums are unbounded) | `descuentoTotalCentimos` is always null |

Already known from the plan and unchanged: D5-30 (`feedback_pregunta.revisado_por`), D8-05 (`conversaciones_soporte.asignado_a`), D6-03 (redeem function), D3-14 (aggregate functions), D4-20/D4-21, D5-12, D7-07, D7-30, D1-20.

## 8. Same path, different wire: needs the OposiControl client to move first (decision for the lead)

For these contracts the canonical METHOD + path is the one the current admin client already calls, and the two wires cannot be
served together by adding fields (the client decodes a bare JSON array, or the canonical command requires an `Idempotency-Key`
header and a body the client does not send). The backend was left untouched for them.

| Contract | Path | Conflict |
|---|---|---|
| D8-02, D8-03 | `/api/v1/admin/soporte/conversaciones`, `.../{id}/mensajes` | bare array with English field names vs `{items, nextCursor}` |
| D8-04 | `POST /api/v1/admin/soporte/conversaciones/{id}/mensajes` | `{assignee, text, internal}` without key vs `{cuerpo, interna}` + required key |
| D5-23 | `GET /api/v1/admin/moderacion/actions` | bare array vs `{items, nextCursor}` |
| D5-24 | `POST /api/v1/admin/moderacion/actions` | body without key vs required key and a different body |
| D6-12, D6-13 | `GET /api/v1/admin/tickets`, `.../{usuarioId}/transacciones` | bare array vs `{items, nextCursor}` |
| D6-14 | `POST /api/v1/admin/tickets/{usuarioId}/transacciones` | `{amount, reason}` without key vs `{delta, tipo, motivo}` + required key |

Options, none taken: migrate the client call first and switch the route in the same release; or agree a rule for a transition
period (the design defines none).

## 9. Defaults taken in the moderation reads and the subscription list (FA12), to confirm

- Role: the eight reads require an active admin account, like the rest of the admin plane. The design also lists a moderator role.
- Reports list (D5-25): page size 1..200, default 200 (what the current panel reads in one call); the design says 1..50.
- Thread list (D5-20): `pendingReports` counts reports on the thread only in the "recent" mode and also reports on its replies in the "reported" mode.
- Forum reports are matched by the entity types the database actually allows (`publicacion_oposired`, `comentario_oposired`); the previous values could never match, so "reported threads" was always empty.

## 10. Design not defined (product or design owner must say; nothing was guessed)

| Where | Open point | Effect today |
|---|---|---|
| D6-06 app popups, D6-22 / D6-23 admin popups | The contracts expose `ctas: [{etiqueta, accion, destino?}]`; the table stores two texts (`cta_principal`, `cta_secundario`). No mapping is defined. | D6-22 returns the two stored texts; D6-23 and D6-06 are not implemented |
| D6-06 app popups | "Filter by user plan": how the user's plan is compared with `requiere_plan_minimo` (and what a user without a plan sees). | not implemented |
| D6-22 admin popups | `metricas {mostrados, clicks, conversiones}`: which `popup_usuario_historial.evento` values count for each. | `conMetricas` not offered |
| D6-21 promotion upsert | Discount types: the design says {porcentaje, importe}; the database allows {porcentaje, cantidad, tickets_extra}. | not implemented; the existing save accepts what the database accepts |
| D6-20 promotions list | `vigente`: boundary rule of "currently valid" (inclusive or exclusive dates, null dates). | filter not offered |
| D6-18 services list, D6-19 | Each effect has one `valor` in the design and three typed value columns in the table. | `efectos` not offered |
| D7-17 products list | `q`: which columns the text search matches. | filter not offered |
| D7-18 product upsert | Request field names: the design names (`tipo`, `planCodigo`, `etiqueta`) or the names the admin wire already uses (`tipoProducto`, `planId`, `etiquetaPromocional`). | not implemented |

## 11. Defaults taken in the catalog reads and upserts (FA13), to confirm

- Catalog lists return up to 100 rows by default (the design says 50): the current panel asks for the whole catalog in one call.
- Plan and tariff upserts write the row in one statement and then the audit row; if the audit row fails the call fails, but the
  row is already saved. Making both a single transaction needs a database function.
- A plan code or product code in the URL is limited to 64 characters (letters, digits, underscore, dot, hyphen).
- A tariff saved without `posicion` keeps the position it had (1 for a new one).
