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
