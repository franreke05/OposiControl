# Product decisions pending

Single list of what the backend is NOT implementing because a product (or owner-level security) decision is open.
A blocked capability does not block its slice: everything else in the slice is implemented or scheduled.
Source of the contract ids and DEC ids: `OPENAPI_EXTENSION_PLAN.md` (this folder) and the decision register
`PHASE3_DECISION_GATE.md` (OposiBot repo). Updated 2026-10-09.

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
| `nivel` | own OposiTester counters D5-16 | DEC-028 |

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
| D7-18 product upsert | RESOLVED in FA15: the request uses the names the product list emits (`tipoProducto`, `planId`, `etiquetaPromocional`). | implemented (section 12.3) |

## 11. Defaults taken in the catalog reads and upserts (FA13), to confirm

- Catalog lists return up to 100 rows by default (the design says 50): the current panel asks for the whole catalog in one call.
- Plan and tariff upserts write the row in one statement and then the audit row; if the audit row fails the call fails, but the
  row is already saved. Making both a single transaction needs a database function.
- A plan code or product code in the URL is limited to 64 characters (letters, digits, underscore, dot, hyphen).
- A tariff saved without `posicion` keeps the position it had (1 for a new one).

## 12. Added by FA15 (2026-10-09)

### 12.1 Four app paths are both "legacy, to retire" and canonical in the design (decision for the lead)

| Contract | Path | Today |
|---|---|---|
| D3-01 | `GET /api/v1/app/home` | legacy-marked: `Deprecation` header, retirement counter, register class "retire after telemetry" |
| D3-03 | `GET /api/v1/app/estadisticas` | same |
| D7-01 | `GET /api/v1/app/commercial/catalog` | same |
| D5-15 | `POST /api/v1/app/feedback/pregunta` | same |

The design keeps these paths and extends them. The repository gates treat a path as either legacy (to retire) or declared in the
contract, never both. Options, none taken: promote the path (remove the deprecation and the retirement row, then extend the
response); or keep retiring it and give the canonical contract a new path. Nothing was changed for these four.

Open points that remain even after that decision:

- D3-01: the daily challenge block must have the shape of D2-05, which is specified but not served; the legacy response already
  has `examenes` and `retoDiario` with another element shape and another selection rule (first five published exams, not filtered
  by the user's specialty).
- D3-03: failed and unanswered totals, the "last 100" block and the per-exam scope have no stored source and no formula.
- D7-01: product type values of the design differ from the stored ones; ticket price, service type and effects live in the
  services table (DEC-033 not executed); the store identifier depends on DEC-031; custom top-up is DEC-032.
- D5-15: `target` and `recursoId` have no column.

### 12.2 Not implemented in FA15 for a missing schema object or an undefined rule

| Contract | Missing | Effect today |
|---|---|---|
| D6-15 ticket economy summary | an aggregate function (sums by type and by period over the ledger, total of balances) | not implemented; the existing summary keeps its counters and lists the sums as unavailable |
| D5-14 user report | a UNIQUE index for "one pending report per reporter and target", a function for "insert + sent counter" in one transaction, the rate limit rule; and a target a user can actually see (forum reads are blocked; question and resource reports are DEC-026) | not implemented; users cannot file a report through the backend |
| D4-19 question resources for the app | the saved-resource store (`guardado`), the access rule when no exam is given, and DEC-025 (the attempt review already withholds resources) | not implemented |
| D5-27 report resolution (canonical) | same path as the route the admin panel calls, with another body and a required `Idempotency-Key`; needs the panel to move first (section 8) | the existing route is unchanged; it grants the reward only through the atomic, idempotent grant function |

### 12.3 Defaults taken in FA15, to confirm

- Billing history (D7-05): a purchase is dated by the creation of its row (a pending purchase has no purchase date yet); a
  subscription by its start. A subscription shows no price (the table stores none) and its type is always `suscripcion`.
- Forum categories (D5-01): at most 50, cached by the client for five minutes; no ETag.
- OposiTester (D5-16): the stored level is not shown until DEC-028 defines the levels.
- Product upsert (D7-18): field names of the product list, not of the design; the price cannot be set here (only through
  tariffs); a missing plan is 422; text limits 200 / 2000 / 100 characters (name, description, promotional label); the row and
  its audit row are two steps, as in the plan and tariff upserts.

## 13. Noted by the last review (2026-10-09), not blocking

- Fixed: a subscription product can no longer be saved without its plan (D7-18), nor a ticket pack without its amount.
- Admin catalog lists (products, plans, services, promotions, popups) are now bounded to 100 rows per page and the current
  admin client reads only the first page: harmless at today's catalog size; the client must page on `total` before a list
  grows past 100.
- D7-22 (plan), D7-19 (tariff), D7-18 (product): upsert then mandatory audit row are two steps (no DB function): if the
  audit insert fails the change is stored and the caller gets 500; a retry is idempotent and writes the audit row. The
  contract does not declare that 500 yet. The plan audit row does not record `nivel` before/after.
- Forum "reported" mode reads up to 500 pending reports without an order and sends long id lists upstream: bound and
  order it before the moderation queue can grow.
- Decisions needed: promote or retire the four legacy-marked app paths (home D3-01, stats D3-03, store D7-01, report
  D5-15); owner go-ahead for the question-resource contracts D4-13..D4-17 (inside the protected authoring flow).

## 14. Legacy store screens (2026-10-09): PRODUCT_BLOCKED

The admin routes `/api/v1/admin/tienda/{articulos,ofertas,recursos-temporales,rafagas}` read four tables that do not exist
in production, have no OpenAPI contract and no migration; the canonical commercial catalogue (products, plans, tickets) is a
different model. Nothing was invented: reads answer an empty list and writes answer 409 `STORE_LEGACY_NOT_PROVISIONED`
(before: 502). Decision for the owner: retire these routes and the screen that calls them, or define the store model so a
schema can be created (DEBT-068, DEBT-084).

## 15. OposiControl UI phase, Users + Monetization (2026-10-11): PRODUCT_BLOCKED

Found while building the Catalogue and OposiMarket screens. Nothing below was invented in the UI; each item is a
decision the owner has to take before the capability can exist.

1. **OposiMarket tables are not consumed by OposiBot.** The app-facing API reads only `productos_comerciales` (with
   plans, benefits and tariffs). `paquetes_opositickets`, `servicios`, `promociones` and `reglas_popup_comercial` are
   administrable from OposiControl (create, edit, activate) and are empty in production, but no `/api/v1/app` route,
   and no database function tracked in this repository, reads them. Ticket packs in particular exist twice: as
   products of type `pack_tickets` (what the app sells) and as rows of `paquetes_opositickets` (what Market edits).
   Decision: which of the two is canonical, and what the app should do with services, promotions and pop-up rules.
   Until then OposiMarket says so in its information rail.
2. **Unit of a `cantidad` promotion.** `promociones.valor` is a bare `numeric` and the backend only requires it to be
   non-negative; whether a fixed-amount discount is euros or cents is not defined anywhere. The form shows the number
   as stored and says the unit is set by whoever applies the promotion.
3. **Effect of each service type.** The twelve `tipo_servicio` values are fixed by a CHECK, but duration, multiplier
   and stacking rules of boosters and protectors are not modelled (no columns, no contract). The form edits name,
   type, prices and availability only.
4. **Pop-up rule vocabulary.** `evento_disparador`, `pantalla` and `requiere_plan_minimo` are free text with no
   catalogue of valid values; only `momento` and `tipo_examen_objetivo` are constrained. The form offers lists for the
   two constrained fields and free text for the rest.
5. **No delete or archive** for products, tariffs, plans, benefits, packs, services, promotions or pop-up rules: the
   backend exposes upsert only. The screens offer activate/deactivate (and hide), which is what exists.
6. **Subscriptions list.** `GET /api/v1/admin/suscripciones` (read-only, cursor) has no screen in this block; the
   Users record already shows each user's subscription. A cross-user subscriptions view was not in the defined scope.
7. **Legacy store on narrow windows.** Below 1040 dp the app falls back to the mobile adapter, where "Catálogo" still
   opens the legacy store screen of section 14. Desktop (1080 dp and wider) uses the canonical Catalogue.

## 16. News editor (2026-10-11): what the editor assumes and what stays PRODUCT_BLOCKED

The desktop news editor was rebuilt on the canonical contract (D4-01..D4-09). Found while aligning it with the app:

1. **The consumer does not exist.** D4-18 (`GET /api/v1/app/noticias`) is still blocked by DEC-030 and OposiBot shows a
   static image in its Home banner. A published news item is not shown anywhere today; the editor says so.
2. **Canvas format (taken as input for DEC-030).** The editor now composes on the Home banner itself: 358 x 148 dp on
   phones, 704 x 291 dp on tablets, edited at 4 px per dp (1432 x 592). Before it was 800 x 500, an aspect no screen of
   the app has. If DEC-030 decides that news live somewhere else, only the constants in `NewsDocument.kt` change.
   Production has no news rows, so nothing had to be migrated. The painting rules are in
   `docs/integration/NEWS_RENDERING_CONTRACT.md`.
3. **Scheduling** has no automatic activation (section 6). The editor keeps the date and offers "Publicar ahora".
4. **Banner action.** The row has no link or call-to-action columns: what the banner's "Ir" button opens is undefined.
5. **Editing a published item** is not allowed by the contract (`NEWS_NOT_EDITABLE`): archive and create another.
6. **Body text and card image** (`texto`, `imagenPath`) are stored but no app surface shows them yet.
