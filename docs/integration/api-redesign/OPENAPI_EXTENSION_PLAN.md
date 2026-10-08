# OpenAPI extension plan (Slices 4-7 and unscheduled contracts)

Status: FA11 implemented (news D4-01..D4-11, wallet reads D6-01/D6-02). FA12 implemented (section 9): moderation reads D5-20, D5-21, D5-22, D5-25, D5-26, D5-28, D5-29 and the admin subscription list D7-27. FA13 implemented (section 10): admin catalog reads D7-17, D7-21, D6-18, D6-20, D6-22, dashboard overview D3-15 and the upserts D7-22, D7-19. FA14 implemented (section 11): promotion use log D7-29. No migration, no database touched.
Date: 2026-10-08
Base: branch `feature/canonical-api-slice-1` at `f3c53d3`
Scope: every canonical contract of `canonical/contracts.jsonl` (218) that `openapi/` did not declare at the base (123), plus the three public catalog contracts of the first batch that it already declared.

## 1. Evidence and method

- Primary evidence: `canonical/contracts.jsonl` (`openapiReady`, `blockedBy`, `status`, `currentEndpoints`, `sourceOfTruth`, `transactionBoundary`) and `canonical/decision_triage.jsonl`, both in the OposiBot repo (`docs/integration/api-redesign/`), read-only.
- Cross-check: `docs/integration/canonical-implementation/PHASE3_DECISION_GATE.md` (DEC-001..DEC-042) and `17_IMPLEMENTATION_PLAN.md` (slice assignment; a contract the plan does not list is `unscheduled`).
- Backend code: grep of `backend/ktor/src/main` for the tables each contract names. Legacy routes: `ops/inventory/undocumented-routes.tsv` (routes Ktor serves today, with their successor contract).
- Production schema: the data-free DDL dump `baseline_main.sql` (90 tables, 53 functions). `MISSING_SCHEMA` means the table, column, index or function is not in that dump. Storage buckets are not part of the dump.

Classes (one per contract):

| Class | Meaning |
|---|---|
| SPEC_READY | The wire is fully determined by the design and no open decision changes behaviour. |
| VALUE_ONLY | The shape is determined; some values stay null (or an enum stays shorter) until the DEC listed. |
| PRODUCT_BLOCKED | An open product decision changes the shape or the behaviour. The operation stays out of `openapi/`. |
| EXTERNAL_BLOCKED | Needs an external system that does not exist yet (payment provider verifier). |
| INTERNAL | In-process, no HTTP route; never part of `openapi/`. |

Two flags are orthogonal to the class and are counted separately: `ALREADY_SERVED_LEGACY` (column "Legacy route served today" is not empty: Ktor already serves the capability at another wire) and `MISSING_SCHEMA` (column "Production schema"). The legacy flag is set only from rows of the reviewed register `ops/inventory/undocumented-routes.tsv` that name the contract as successor, so it is a lower bound: a contract whose `currentEndpoints` are not mapped there shows `-`. "Backend code to reuse" is a grep of table names, a starting point and not a verified reuse plan.

Three rows are an architect OVERRIDE of the dataset (`openapiReady: YES` turned into PRODUCT_BLOCKED after the cross-check): D3-09, D3-11 and D5-05. Each says why in its note; the lead can reverse them. D8-14 and D8-15 are blocked on DEC-041, whose owner is TECH (a default is proposed but not recorded); they are listed as PRODUCT_BLOCKED because the dataset blocks them, not because the owner must decide.

## 2. Counts

| Slice | Contracts | SPEC_READY | VALUE_ONLY | PRODUCT_BLOCKED | EXTERNAL_BLOCKED | INTERNAL | flag ALREADY_SERVED_LEGACY | flag MISSING_SCHEMA |
|---|---|---|---|---|---|---|---|---|
| Slice 2 | 1 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| Slice 3 | 1 | 0 | 0 | 1 | 0 | 0 | 0 | 0 |
| Slice 4 | 7 | 1 | 2 | 3 | 0 | 1 | 1 | 0 |
| Slice 5 | 20 | 15 | 0 | 5 | 0 | 0 | 8 | 6 |
| Slice 6 | 14 | 7 | 2 | 5 | 0 | 0 | 3 | 2 |
| Slice 7 | 16 | 5 | 3 | 3 | 1 | 4 | 4 | 6 |
| unscheduled | 64 | 36 | 4 | 20 | 0 | 4 | 15 | 12 |
| TOTAL | 123 | 64 | 11 | 37 | 1 | 10 | 31 | 26 |

The 123 are the contracts absent from `openapi/` at the base. FA11 (section 5) moves 13 of them into the spec: 12 SPEC_READY and 1 VALUE_ONLY (D6-01).

## 3. Classification table

| ID | Method | Path | Slice | Class | DEC | Legacy route served today | Backend code to reuse | Tables / RPCs | Production schema | Note |
|---|---|---|---|---|---|---|---|---|---|---|
| D7-25 | INTERNAL | `(in-process) EntitlementPolicy.check` | 2 | INTERNAL | - | - | AdminV1Routes.kt, CatalogGateway.kt, EntitlementPolicy.kt | estado_premium_usuario | OK | implemented in Slice 2 (EntitlementPolicy.kt) |
| D2-04 | POST | `/api/v1/app/tests-rapidos` | 3 | PRODUCT_BLOCKED | DEC-021 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, AttemptGateway.kt, CatalogGateway.kt | intentos_examen, preguntas_intento | OK | - |
| D3-01 | GET | `/api/v1/app/home` | 4 | VALUE_ONLY | DEC-007 | - | AdminAnalytics.kt, AdminRankings.kt, AdminUserDetail.kt, AdminUserProfileMapper.kt, AdminV1Routes.kt, AppV1Routes.kt | perfiles_usuario, estado_competitivo_usuario, rachas_usuario, retos_diarios, registros_reto_diario, intentos_examen | OK | otHoy nullable; watch DEC-003/DEC-012 (ligaNivel nullable) and DEC-006 (racha values) |
| D3-02 | GET | `/api/v1/app/liga` | 4 | PRODUCT_BLOCKED | DEC-003, DEC-004, DEC-012 | - | AdminAnalytics.kt, AdminRankings.kt, AdminV1Routes.kt | estado_competitivo_usuario, ligas_nivel | OK | - |
| D3-03 | GET | `/api/v1/app/estadisticas` | 4 | VALUE_ONLY | DEC-004, DEC-009, DEC-010 | - | AdminV1Routes.kt, AppV1Routes.kt, AdminAnalytics.kt, AdminRankings.kt | estadisticas_usuario, sesiones_estudio, estado_competitivo_usuario | OK | colocacion, mediaComunidad, ultimoRepasoEn nullable |
| D3-04 | GET | `/api/v1/app/racha` | 4 | PRODUCT_BLOCKED | DEC-006 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, AttemptGateway.kt | rachas_usuario, respuestas_intento, eventos_app | OK | - |
| D3-05 | POST | `/api/v1/app/eventos` | 4 | SPEC_READY | - | - | AdminAnalytics.kt | eventos_app | OK | TECH default DEC-038 (eventos_app sole sink) must be recorded in an ADR first; not a product decision. NOT IN FA12: precondition not met (no ADR records the TECH default DEC-038 in this repo) |
| D3-06 | INTERNAL | `in-process: ApplyRankedAnswers (no HTTP route)` | 4 | INTERNAL | DEC-003, DEC-005, DEC-006 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, AttemptGateway.kt, AdminRankings.kt | respuestas_intento, estado_competitivo_usuario, estadisticas_usuario, rachas_usuario, sesiones_estudio | OK | in-process; behaviour blocked by the decision |
| D3-10 | GET | `/api/v1/admin/rankings` | 4 | PRODUCT_BLOCKED | DEC-004, DEC-012 | `GET /api/v1/admin/rankings/global`, `GET /api/v1/admin/rankings/liga/{}`, `GET /api/v1/admin/rankings/usuarios` | AdminAnalytics.kt, AdminRankings.kt, AdminV1Routes.kt, AdminUserDetail.kt, AdminUserProfileMapper.kt, AppV1Routes.kt | estado_competitivo_usuario, perfiles_usuario | OK | - |
| D4-01 | GET | `/api/v1/admin/noticias` | 5 | SPEC_READY | - | `GET /content/noticias` | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK (no keyset index on (actualizado_en desc, id); small table) | - |
| D4-03 | POST | `/api/v1/admin/noticias` | 5 | SPEC_READY | - | `POST /content/noticias` | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-04 | PATCH | `/api/v1/admin/noticias/{noticiaId}` | 5 | SPEC_READY | - | `PUT /content/noticias` | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-05 | POST | `/api/v1/admin/noticias/{noticiaId}/publicar` | 5 | SPEC_READY | - | - | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-06 | POST | `/api/v1/admin/noticias/{noticiaId}/programar` | 5 | SPEC_READY | - | - | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-07 | POST | `/api/v1/admin/noticias/{noticiaId}/archivar` | 5 | SPEC_READY | - | - | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-09 | POST | `/api/v1/admin/noticias/assets` | 5 | SPEC_READY | - | `POST /content/noticias/assets` | - | - | n/a | - |
| D4-10 | GET | `/api/v1/admin/tipos-noticia` | 5 | SPEC_READY | - | `GET /admin/news-types` | AdminGateway.kt, NewsGateway.kt, TagSearchGateway.kt | tipo_noticia | OK | - |
| D4-11 | PUT | `/api/v1/admin/tipos-noticia/{codigo}` | 5 | SPEC_READY | - | `POST /admin/news-types`, `POST /admin/news-types/{}`, `PUT /admin/news-types/{}` | AdminGateway.kt, NewsGateway.kt, TagSearchGateway.kt | tipo_noticia | OK | - |
| D4-13 | GET | `/api/v1/admin/preguntas/{preguntaId}/recursos` | 5 | SPEC_READY | - | - | AdminDashboardOverview.kt, AdminV1Routes.kt | recursos_pregunta | OK | - |
| D4-14 | POST | `/api/v1/admin/preguntas/{preguntaId}/recursos` | 5 | SPEC_READY | - | `POST /content/preguntas/{}/recursos` | AdminDashboardOverview.kt, AdminV1Routes.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | recursos_pregunta, auditoria_log | OK | - |
| D4-15 | POST | `/api/v1/admin/preguntas/{preguntaId}/recursos/archivo` | 5 | SPEC_READY | - | `POST /content/preguntas/{}/recursos/{}/upload` | AdminDashboardOverview.kt, AdminV1Routes.kt | recursos_pregunta | OK | - |
| D4-18 | GET | `/api/v1/app/noticias` | 5 | PRODUCT_BLOCKED | DEC-030 | - | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-19 | GET | `/api/v1/app/preguntas/{preguntaId}/recursos` | 5 | SPEC_READY | - | - | AdminDashboardOverview.kt, AdminV1Routes.kt | recursos_pregunta | OK | watch DEC-025 (which resources an entitlement exposes is a value of the access rule, not of this shape) |
| D4-20 | PUT | `/api/v1/app/opositeca/guardados/{tipo}/{targetId}` | 5 | SPEC_READY | - | - | - | preguntas_favoritas_usuario | MISSING_SCHEMA: recursos_guardados_usuario | watch DEC-025 |
| D4-21 | DELETE | `/api/v1/app/opositeca/guardados/{tipo}/{targetId}` | 5 | SPEC_READY | - | - | - | - | MISSING_SCHEMA: recursos_guardados_usuario | watch DEC-025 |
| D4-22 | PUT | `/api/v1/app/opositeca/examenes/{examenId}` | 5 | PRODUCT_BLOCKED | DEC-025 | - | - | - | MISSING_SCHEMA: saved-exam table (shape on DEC-025) | - |
| D4-23 | DELETE | `/api/v1/app/opositeca/examenes/{examenId}` | 5 | PRODUCT_BLOCKED | DEC-025 | - | - | - | MISSING_SCHEMA: saved-exam table (shape on DEC-025) | - |
| D4-24 | GET | `/api/v1/app/opositeca` | 5 | PRODUCT_BLOCKED | DEC-025 | - | AdminV1Routes.kt, Application.kt, CatalogGateway.kt, AdminDashboardOverview.kt, AdminGateway.kt, AdminUserDetail.kt | comunidades_autonomas, examenes | MISSING_SCHEMA: saved tables (DEC-025) | - |
| D4-25 | GET | `/api/v1/app/opositeca/examenes/{examenId}` | 5 | PRODUCT_BLOCKED | DEC-025 | - | - | - | MISSING_SCHEMA: saved tables (DEC-025) | - |
| D5-01 | GET | `/api/v1/app/oposired/categorias` | 6 | SPEC_READY | - | - | AdminV1Routes.kt | categorias_oposired | OK | - |
| D5-02 | GET | `/api/v1/app/oposired/publicaciones` | 6 | PRODUCT_BLOCKED | DEC-024, DEC-023, DEC-022 | - | AdminAnalytics.kt, AdminV1Routes.kt | publicaciones_oposired, reacciones_oposired | MISSING_SCHEMA: saved-post table | - |
| D5-03 | GET | `/api/v1/app/oposired/publicaciones/{id}` | 6 | PRODUCT_BLOCKED | DEC-024 | - | AdminAnalytics.kt, AdminV1Routes.kt | publicaciones_oposired | OK | - |
| D5-04 | POST | `/api/v1/app/oposired/publicaciones` | 6 | PRODUCT_BLOCKED | DEC-029, DEC-023 | - | AdminAnalytics.kt, AdminV1Routes.kt | publicaciones_oposired | OK | - |
| D5-07 | GET | `/api/v1/app/oposired/publicaciones/{id}/comentarios` | 6 | PRODUCT_BLOCKED | DEC-024 | - | AdminAnalytics.kt, AdminV1Routes.kt | comentarios_oposired | OK | - |
| D5-08 | POST | `/api/v1/app/oposired/publicaciones/{id}/comentarios` | 6 | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt | comentarios_oposired | OK | - |
| D5-11 | PUT | `/api/v1/app/oposired/votos` | 6 | PRODUCT_BLOCKED | DEC-024 | - | - | reacciones_oposired | OK | - |
| D5-14 | POST | `/api/v1/app/reportes` | 6 | VALUE_ONLY | DEC-026, DEC-025 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt | reportes_contenido, estado_opositester_usuario | OK | tipoEntidad enum is extended (pregunta, recurso, opositeca_item) only by those decisions |
| D5-15 | POST | `/api/v1/app/feedback/pregunta` | 6 | SPEC_READY | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt | feedback_pregunta | OK | - |
| D5-23 | GET | `/api/v1/admin/moderacion/actions` | 6 | SPEC_READY | - | `GET /admin/moderation/actions`, `GET /api/v1/admin/moderacion/acciones` | AdminV1Routes.kt | acciones_moderacion | OK | NOT IN FA12. NEEDS_SCHEMA: acciones_moderacion has no target columns (only reporte_id), so targetTipo/targetId/targetUser have no source. Also WIRE_CONFLICT: same path as EP-065, the OC client decodes a bare array |
| D5-24 | POST | `/api/v1/admin/moderacion/actions` | 6 | SPEC_READY | - | `POST /admin/moderation/actions` | AdminV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | acciones_moderacion, auditoria_log | OK | NOT IN FA12. NEEDS_SCHEMA: acciones_moderacion target columns; CHECK acciones_moderacion_tipo_accion_check lacks fijar/desfijar; no single-transaction function (target estado + action + audit). Also WIRE_CONFLICT: same path as EP-173, the OC client sends no Idempotency-Key |
| D5-25 | GET | `/api/v1/admin/reportes` | 6 | SPEC_READY | - | `GET /api/v1/admin/moderacion`, `GET /api/v1/admin/moderacion/reportes` | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt | reportes_contenido | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-083: `items` + legacy `data` duplicate, `nextCursor`, `pendientesTotal`, `tipoEntidad` |
| D5-27 | POST | `/api/v1/admin/reportes/{id}/resolver` | 6 | VALUE_ONLY | DEC-026, DEC-040 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AdminGateway.kt, AdminOposiTickets.kt | reportes_contenido, acciones_moderacion, transacciones_cartera, admin_grant_tickets() | OK | ticketsReporter > 0 answers 422 reward_not_enabled until decided |
| D5-30 | POST | `/api/v1/admin/feedback/preguntas/{id}/resolver` | 6 | SPEC_READY | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt | feedback_pregunta | MISSING_SCHEMA: feedback_pregunta.revisado_por | - |
| D6-01 | GET | `/api/v1/app/wallet` | 7 | VALUE_ONLY | DEC-036 | - | AdminAnalytics.kt, AdminGateway.kt, AdminOposiTickets.kt, AdminV1Routes.kt, AdminDashboardOverview.kt | transacciones_cartera, carteras_usuario, activaciones_servicio_usuario | OK (transacciones_cartera.saldo_anterior is nullable: derive saldo_resultante - cantidad for old rows) | IN SPEC (FA11). recompensas null |
| D6-02 | GET | `/api/v1/app/wallet/movimientos` | 7 | SPEC_READY | - | - | AdminAnalytics.kt, AdminGateway.kt, AdminOposiTickets.kt, AdminV1Routes.kt | transacciones_cartera | MISSING_SCHEMA: index transacciones_cartera (usuario_id, creado_en desc, id); only (usuario_id) exists | IN SPEC (FA11). DEC-040 may only ADD ledger types (additive) |
| D6-03 | POST | `/api/v1/app/wallet/canjes` | 7 | VALUE_ONLY | DEC-035 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, CatalogGateway.kt, EntitlementPolicy.kt, AdminGateway.kt | carteras_usuario, contenido_desbloqueado_usuario, examenes, servicios | MISSING_SCHEMA: redeem function (lock wallet + ledger + unlock/activation); only legacy rpc_purchase_articulo exists | price and duration values; shape independent |
| D6-08 | IN_PROCESS | `internal:WalletService.creditVerifiedPurchase (no HTTP; single Ktor)` | 7 | INTERNAL | DEC-031 | - | - | - | MISSING_SCHEMA: wallet credit function keyed by provider transaction | in-process; values on the decision |
| D6-09 | IN_PROCESS | `internal:WalletService.reverseRefund (invoked from /api/v1/webhooks/** of PAYMENTS)` | 7 | INTERNAL | DEC-031 | - | - | - | MISSING_SCHEMA: refund reversal function | in-process, called from the payments webhook; refund-after-spend rule is an unregistered product decision |
| D6-10 | IN_PROCESS | `internal:WalletService.grantSystemReward` | 7 | INTERNAL | DEC-036, DEC-037, DEC-026, DEC-040 | - | - | - | MISSING_SCHEMA: system reward function; CHECK transacciones_cartera_tipo_check lacks opositester/solucionario types (DEC-040) | in-process; values on the decision |
| D6-14 | POST | `/api/v1/admin/tickets/{usuarioId}/transacciones` | 7 | SPEC_READY | - | `POST /admin/ticket-wallets/{}/transactions`, `POST /api/v1/admin/tickets/conceder`, `POST /api/v1/admin/usuarios/{}/tickets` | AdminGateway.kt, AdminOposiTickets.kt, AdminV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | admin_grant_tickets(), auditoria_log | OK | NOT IN FA12. WIRE_CONFLICT: same path as EP-183, the OC client sends `{amount,reason}` and no Idempotency-Key header (canonical: `{delta,tipo,motivo}` + required header). The atomic path exists (admin_grant_tickets) |
| D7-01 | GET | `/api/v1/app/commercial/catalog` | 7 | VALUE_ONLY | DEC-032 | - | AdminSubscriptions.kt, AdminV1Routes.kt, AppV1Routes.kt, CatalogGateway.kt, AdminAnalytics.kt, AdminDashboardOverview.kt | planes_suscripcion, beneficios_plan, productos_comerciales, productos_store, servicios | OK | recargaPersonalizada nullable; pack source is TECH default DEC-033 |
| D7-03 | POST | `/api/v1/app/purchases/verify` | 7 | EXTERNAL_BLOCKED | DEC-031 | - | AdminAnalytics.kt, AppV1Routes.kt, AdminDashboardOverview.kt, AdminUserDetail.kt, AdminV1Routes.kt | compras_usuario, suscripciones | OK | needs a real store/provider verifier and credentials; proveedor values come from DEC-031 |
| D7-04 | POST | `/api/v1/webhooks/payments/{proveedor}` | 7 | PRODUCT_BLOCKED | DEC-031 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminUserDetail.kt, AdminV1Routes.kt, AppV1Routes.kt | suscripciones | MISSING_SCHEMA: provider notification inbox table | - |
| D7-06 | POST | `/api/v1/app/subscription/trial` | 7 | PRODUCT_BLOCKED | DEC-034 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminUserDetail.kt, AdminV1Routes.kt, AppV1Routes.kt | suscripciones | OK | - |
| D7-17 | GET | `/api/v1/admin/comercial/productos` | 7 | SPEC_READY | - | `GET /api/v1/admin/comercial/productos/{}/tarifas`, `GET /api/v1/admin/market/packs` | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, CatalogGateway.kt | productos_comerciales, productos_store | OK | IN SPEC + IMPLEMENTED (FA13). Same path as EP-043 (OC client): OFFSET page, `items` + legacy `data`; item keeps the client names (`tipoProducto`, `planId`, `etiquetaPromocional`, `tarifas`); no `storeMappings` (D7-20), no `q` |
| D7-18 | PUT | `/api/v1/admin/comercial/productos/{codigo}` | 7 | SPEC_READY | - | `POST /api/v1/admin/comercial/productos`, `POST /api/v1/admin/market/packs` | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, CatalogGateway.kt, Observability.kt | productos_comerciales, auditoria_log | OK | NOT IN FA13, implementable: needs the lead to choose the request field names (design `tipo`/`planCodigo`/`etiqueta` vs the names D7-17 emits) |
| D7-19 | PUT | `/api/v1/admin/comercial/productos/{codigo}/tarifas/{periodo}/{fase}` | 7 | SPEC_READY | - | `POST /api/v1/admin/comercial/productos/{}/tarifas` | AdminSubscriptions.kt, AdminV1Routes.kt, AppV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | tarifas_producto_comercial, auditoria_log | OK | IN SPEC + IMPLEMENTED (FA13). New path; one `ON CONFLICT (producto_id, periodo_facturacion, fase_precio)` statement + audit row |
| D7-20 | PUT | `/api/v1/admin/comercial/productos/{codigo}/store-mappings/{proveedor}` | 7 | PRODUCT_BLOCKED | DEC-031 | - | AdminDashboardOverview.kt, AdminV1Routes.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | productos_store, auditoria_log | OK | - |
| D7-26 | INTERNAL | `(in-process) SubscriptionService.grantPlanContentCredits` | 7 | INTERNAL | - | - | CatalogGateway.kt | creditos_contenido_usuario | OK | in-process, no HTTP |
| D1-20 | GET | `/api/v1/app/me/referrals` | unscheduled | VALUE_ONLY | DEC-037 | - | - | invitaciones_referido | MISSING_SCHEMA: perfiles_usuario.codigo_referido (stable per-user code) | earnedOt is 0 until the referral reward trigger is decided |
| D2-15 | GET | `/api/v1/public/examenes` | unscheduled | SPEC_READY | - | `GET /v1/examenes` | - | - | n/a | ALREADY IN SPEC before FA11 (catalog.yaml, TARGET, x-slice null); nothing to add |
| D2-16 | GET | `/api/v1/public/examenes/{examId}` | unscheduled | SPEC_READY | - | `GET /v1/examenes/{}` | - | - | n/a | ALREADY IN SPEC before FA11 (catalog.yaml, TARGET, x-slice null); nothing to add |
| D2-17 | GET | `/api/v1/public/especialidades` | unscheduled | SPEC_READY | - | `GET /v1/especialidades` | - | - | n/a | ALREADY IN SPEC before FA11 (catalog.yaml, TARGET, x-slice null); nothing to add |
| D3-07 | INTERNAL | `in-process: RecordStudySession + WriteAttemptTagSummary (no HTTP route)` | unscheduled | INTERNAL | - | - | AdminAnalytics.kt, AttemptGateway.kt | sesiones_estudio, resumen_intento_etiqueta | OK | in-process, no HTTP |
| D3-09 | GET | `/api/v1/admin/rankings/ligas` | unscheduled | PRODUCT_BLOCKED | DEC-003, DEC-012 | - | AdminAnalytics.kt, AdminRankings.kt, AdminV1Routes.kt | ligas_nivel | OK | OVERRIDE of dataset YES: the response (preguntasMinimas, ventanaCalculo) hard-codes one option of DEC-003 and one season model of DEC-012 |
| D3-11 | GET | `/api/v1/admin/rankings/usuario/{usuarioId}` | unscheduled | PRODUCT_BLOCKED | DEC-003, DEC-004, DEC-006 | - | AdminAnalytics.kt, AdminRankings.kt, AdminV1Routes.kt, AppV1Routes.kt, AdminDashboardOverview.kt, AdminUserDetail.kt | estado_competitivo_usuario, estadisticas_usuario, rachas_usuario, perfiles_usuario | OK | OVERRIDE of dataset YES: liga, posicionLiga, posicionGlobal, percentil and racha are non-nullable and depend on the open league, scope and streak rules |
| D3-12 | POST | `/api/v1/admin/rendimiento/usuarios/{usuarioId}/reconstruir` | unscheduled | PRODUCT_BLOCKED | DEC-003, DEC-006 | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, AttemptGateway.kt | respuestas_intento | OK | - |
| D3-13 | INTERNAL | `in-process: RefreshGlobalQuestionStats (+ exposed as dificultad field in admin question read)` | unscheduled | INTERNAL | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt, AttemptGateway.kt | respuestas_intento | OK | in-process, no HTTP |
| D3-14 | GET | `/api/v1/admin/analytics/{seccion}` | unscheduled | VALUE_ONLY | DEC-009 | `GET /api/v1/admin/analytics/comunidad`, `GET /api/v1/admin/analytics/economia`, `GET /api/v1/admin/analytics/estudio`, `GET /api/v1/admin/analytics/marketing`, `GET /api/v1/admin/analytics/producto`, `GET /api/v1/admin/analytics/retencion` | - | - | MISSING_SCHEMA: one SQL aggregate function per section (only admin_dashboard_active_users and admin_dashboard_economy_totals exist) | notaMediaComunidad nullable |
| D3-15 | GET | `/api/v1/admin/dashboard/overview` | unscheduled | SPEC_READY | - | `GET /api/v1/admin/dashboard`, `GET /dashboard/metrics` | - | - | n/a | IN SPEC + IMPLEMENTED (FA13). Same path as EP-047: the served shape, documented; `Cache-Control: no-store` added |
| D4-02 | GET | `/api/v1/admin/noticias/{noticiaId}` | unscheduled | SPEC_READY | - | `GET /content/noticias/{}` | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-08 | DELETE | `/api/v1/admin/noticias/{noticiaId}` | unscheduled | SPEC_READY | - | `DELETE /content/noticias/{}` | AdminGateway.kt, Application.kt, LegacyRoutes.kt, NewsGateway.kt, TagSearchGateway.kt | noticia | OK | - |
| D4-16 | PATCH | `/api/v1/admin/preguntas/{preguntaId}/recursos/{recursoId}` | unscheduled | SPEC_READY | - | `PUT /content/preguntas/{}/recursos/order` | AdminDashboardOverview.kt, AdminV1Routes.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | recursos_pregunta, auditoria_log | OK | - |
| D4-17 | DELETE | `/api/v1/admin/preguntas/{preguntaId}/recursos/{recursoId}` | unscheduled | SPEC_READY | - | `DELETE /content/preguntas/{}/recursos/{}` | AdminDashboardOverview.kt, AdminV1Routes.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | recursos_pregunta, auditoria_log | OK | - |
| D4-26 | POST | `/api/v1/app/recursos/{recursoId}/uso` | unscheduled | PRODUCT_BLOCKED | UNREGISTERED (PROP-006 (product decision, no DEC id)) | - | - | uso_recursos_pregunta | OK | - |
| D5-05 | PATCH | `/api/v1/app/oposired/publicaciones/{id}` | unscheduled | PRODUCT_BLOCKED | DEC-023 | - | AdminAnalytics.kt, AdminV1Routes.kt | publicaciones_oposired | OK | OVERRIDE of dataset YES: categoriaId scalar encodes one option of the post taxonomy (same reason D5-04 is blocked) |
| D5-06 | DELETE | `/api/v1/app/oposired/publicaciones/{id}` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt | publicaciones_oposired | OK | - |
| D5-09 | PATCH | `/api/v1/app/oposired/comentarios/{id}` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt | comentarios_oposired | OK | - |
| D5-10 | DELETE | `/api/v1/app/oposired/comentarios/{id}` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt | comentarios_oposired | OK | - |
| D5-12 | PUT | `/api/v1/app/oposired/publicaciones/{id}/guardado` | unscheduled | SPEC_READY | - | - | - | - | MISSING_SCHEMA: saved-post table | - |
| D5-13 | POST | `/api/v1/app/oposired/imagenes/upload-url` | unscheduled | PRODUCT_BLOCKED | DEC-029 | - | - | - | MISSING_SCHEMA: private forum bucket (storage is not in the dump) | - |
| D5-16 | GET | `/api/v1/app/opositester/me` | unscheduled | VALUE_ONLY | DEC-028 | - | AdminV1Routes.kt | estado_opositester_usuario | OK | nivel nullable |
| D5-17 | GET | `/api/v1/app/opositester/semanal` | unscheduled | PRODUCT_BLOCKED | DEC-027, DEC-040 | - | - | - | n/a | - |
| D5-20 | GET | `/api/v1/admin/moderacion/hilos` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt, AdminDashboardOverview.kt | publicaciones_oposired, reportes_contenido | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-066 (OC client): wire only grows (`modo`, `reportado`, `pendingReports`) |
| D5-21 | GET | `/api/v1/admin/moderacion/hilos/{id}` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt | publicaciones_oposired | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-067: existing wire; the "first page of replies" is not embedded (the design names no field), replies come from D5-22 |
| D5-22 | GET | `/api/v1/admin/moderacion/hilos/{id}/respuestas` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminV1Routes.kt | comentarios_oposired | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-068: existing wire |
| D5-26 | GET | `/api/v1/admin/reportes/{id}` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt | reportes_contenido | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-084: `data` (with `contentPreview`, `acciones`) + legacy top-level duplicate |
| D5-28 | GET | `/api/v1/admin/feedback/preguntas` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt | feedback_pregunta | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-055: `items` + legacy `data` duplicate; `resolucionNota` null (no column); filter `target` not specified by the design, left out |
| D5-29 | GET | `/api/v1/admin/feedback/preguntas/{id}` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AppV1Routes.kt | feedback_pregunta | OK | IN SPEC + IMPLEMENTED (FA12). Same path as EP-056: existing wire |
| D5-31 | PUT | `/api/v1/admin/oposired/categorias/{id}` | unscheduled | PRODUCT_BLOCKED | DEC-023 | - | AdminV1Routes.kt | categorias_oposired | OK | - |
| D6-04 | POST | `/api/v1/app/wallet/recompensas/{reglaCodigo}/reclamar` | unscheduled | PRODUCT_BLOCKED | DEC-036 | - | - | - | MISSING_SCHEMA: reward rule and claim tables | - |
| D6-06 | GET | `/api/v1/app/popups` | unscheduled | SPEC_READY | - | - | AdminV1Routes.kt | reglas_popup_comercial, popup_usuario_historial | OK | NOT IN FA13. DESIGN pending: `ctas` mapping and the plan filter rule against `requiere_plan_minimo` are not defined |
| D6-11 | IN_PROCESS | `internal:ConsumableService.consume` | unscheduled | INTERNAL | DEC-006 | - | - | activaciones_servicio_usuario | OK | in-process; values on the decision |
| D6-12 | GET | `/api/v1/admin/tickets` | unscheduled | SPEC_READY | - | `GET /admin/ticket-wallets` | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminV1Routes.kt, AdminRankings.kt, AdminUserDetail.kt, AdminUserProfileMapper.kt | carteras_usuario, perfiles_usuario | OK | NOT IN FA12. WIRE_CONFLICT: same path as EP-090, the OC client decodes a bare array |
| D6-13 | GET | `/api/v1/admin/tickets/{usuarioId}/transacciones` | unscheduled | SPEC_READY | - | `GET /admin/ticket-wallets/{}/transactions` | AdminAnalytics.kt, AdminGateway.kt, AdminOposiTickets.kt, AdminV1Routes.kt, AdminDashboardOverview.kt, Observability.kt | transacciones_cartera, auditoria_log | OK | NOT IN FA12. WIRE_CONFLICT: same path as EP-091, the OC client decodes a bare array |
| D6-15 | GET | `/api/v1/admin/tickets/resumen` | unscheduled | VALUE_ONLY | DEC-042 | - | AdminAnalytics.kt, AdminGateway.kt, AdminOposiTickets.kt, AdminV1Routes.kt | transacciones_cartera | OK | gastadosEquivalenteCentimos nullable |
| D6-18 | GET | `/api/v1/admin/market/servicios` | unscheduled | SPEC_READY | - | - | AdminDashboardOverview.kt, AdminMarket.kt, AdminV1Routes.kt, Application.kt | efectos_servicio, servicios | OK | IN SPEC + IMPLEMENTED (FA13). Same path as EP-062: OFFSET page, `items` + legacy `data`; `efectos` not offered (value mapping undefined) |
| D6-19 | POST | `/api/v1/admin/market/servicios` | unscheduled | SPEC_READY | - | - | AdminDashboardOverview.kt, AdminV1Routes.kt, Observability.kt, OposiBotsUserAdminRoutes.kt, AdminMarket.kt, Application.kt | efectos_servicio, auditoria_log, servicios | OK | NOT IN FA13. NEEDS_SCHEMA: no database function replaces a service and its effects in one transaction; the effect `valor` mapping is also undefined |
| D6-20 | GET | `/api/v1/admin/market/promociones` | unscheduled | SPEC_READY | - | - | AdminMarket.kt, AdminV1Routes.kt | usos_promocion_usuario, promociones | OK | IN SPEC + IMPLEMENTED (FA13). Same path as EP-061: OFFSET page, `items` + legacy `data`, `usos`; filter `vigente` not offered (boundary rule undefined) |
| D6-21 | POST | `/api/v1/admin/market/promociones` | unscheduled | SPEC_READY | - | - | AdminDashboardOverview.kt, AdminV1Routes.kt, Observability.kt, OposiBotsUserAdminRoutes.kt, AdminMarket.kt | auditoria_log, promociones | OK | NOT IN FA13. DESIGN/SCHEMA conflict: the design enum is {porcentaje, importe}, production CHECK `promociones_tipo_descuento_check` is {porcentaje, cantidad, tickets_extra} |
| D6-22 | GET | `/api/v1/admin/market/popups` | unscheduled | SPEC_READY | - | - | AdminV1Routes.kt | reglas_popup_comercial, popup_usuario_historial | OK | IN SPEC + IMPLEMENTED (FA13). Same path as EP-060: OFFSET page, `items` + legacy `data`, filter `pantalla`; `ctas` and `metricas` not offered (mapping undefined) |
| D6-23 | POST | `/api/v1/admin/market/popups` | unscheduled | SPEC_READY | - | - | AdminV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | reglas_popup_comercial, auditoria_log | OK | NOT IN FA13. DESIGN pending: `ctas` is required by the command and has no defined mapping to `cta_principal` / `cta_secundario` |
| D7-05 | GET | `/api/v1/app/me/billing-history` | unscheduled | SPEC_READY | - | `GET /api/v1/app/commercial/purchases`, `GET /api/v1/app/commercial/subscriptions` | AdminAnalytics.kt, AppV1Routes.kt, AdminDashboardOverview.kt, AdminUserDetail.kt, AdminV1Routes.kt | compras_usuario, suscripciones | OK | NOT IN FA13, implementable (consumer plane; one keyset over two tables) |
| D7-07 | POST | `/api/v1/app/subscription/cancellation-intent` | unscheduled | SPEC_READY | - | - | - | - | MISSING_SCHEMA: cancellation feedback table | - |
| D7-10 | POST | `/api/v1/app/promotions/quote` | unscheduled | PRODUCT_BLOCKED | DEC-031 | - | AdminMarket.kt, AdminV1Routes.kt | promociones | OK | - |
| D7-13 | GET | `/api/v1/app/me/payment-methods` | unscheduled | PRODUCT_BLOCKED | DEC-031 | - | - | - | MISSING_SCHEMA: payment method reference table | - |
| D7-14 | POST | `/api/v1/app/me/payment-methods` | unscheduled | PRODUCT_BLOCKED | DEC-031 | - | - | - | MISSING_SCHEMA: payment method reference table | - |
| D7-15 | DELETE | `/api/v1/app/me/payment-methods/{id}` | unscheduled | PRODUCT_BLOCKED | DEC-031 | - | - | - | MISSING_SCHEMA: payment method reference table | - |
| D7-16 | PUT | `/api/v1/app/me/payment-methods/default` | unscheduled | PRODUCT_BLOCKED | DEC-031 | - | - | - | MISSING_SCHEMA: payment method reference table | - |
| D7-21 | GET | `/api/v1/admin/planes` | unscheduled | SPEC_READY | - | `GET /api/v1/admin/planes/{}/beneficios` | AdminSubscriptions.kt, AdminV1Routes.kt, AppV1Routes.kt, CatalogGateway.kt | planes_suscripcion, beneficios_plan | OK | IN SPEC + IMPLEMENTED (FA13). Same path as EP-072: OFFSET page, `items` + legacy `data` |
| D7-22 | PUT | `/api/v1/admin/planes/{codigo}` | unscheduled | SPEC_READY | - | `POST /api/v1/admin/planes` | AdminSubscriptions.kt, AdminV1Routes.kt, AppV1Routes.kt, CatalogGateway.kt, AdminDashboardOverview.kt, Observability.kt | planes_suscripcion, auditoria_log | OK | IN SPEC + IMPLEMENTED (FA13). New path; one `ON CONFLICT (codigo)` statement + audit row |
| D7-23 | PUT | `/api/v1/admin/planes/{codigo}/beneficios` | unscheduled | SPEC_READY | - | `POST /api/v1/admin/planes/{}/beneficios` | AdminSubscriptions.kt, AdminV1Routes.kt, AppV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | beneficios_plan, auditoria_log | OK | NOT IN FA13. NEEDS_SCHEMA: no database function replaces the benefit set of a plan in one transaction |
| D7-24 | INTERNAL | `(in-process) ReferralService.grantReward` | unscheduled | INTERNAL | DEC-037 | - | AdminAnalytics.kt, AdminGateway.kt, AdminOposiTickets.kt, AdminV1Routes.kt | invitaciones_referido, transacciones_cartera | OK | in-process; behaviour blocked by the decision |
| D7-27 | GET | `/api/v1/admin/suscripciones` | unscheduled | SPEC_READY | - | - | AdminAnalytics.kt, AdminDashboardOverview.kt, AdminUserDetail.kt, AdminV1Routes.kt, AppV1Routes.kt | suscripciones | OK | IN SPEC + IMPLEMENTED (FA12). New path; `canceladaEn` null (suscripciones has no cancellation instant column); no keyset index on (inicio_en desc, id), admin-only table |
| D7-29 | GET | `/api/v1/admin/market/promociones/{id}/usos` | unscheduled | SPEC_READY | - | - | - | usos_promocion_usuario | OK | IN SPEC + IMPLEMENTED (FA14). New path; cursor envelope with `stats` beside `items`; `descuentoTotalCentimos` null (no aggregate function) |
| D7-30 | GET | `/api/v1/admin/pagos/notificaciones` | unscheduled | SPEC_READY | - | - | - | - | MISSING_SCHEMA: provider notification inbox table | reads the provider notification inbox, which only D7-04 (DEC-031) fills |
| D8-02 | GET | `/api/v1/admin/soporte/conversaciones` | unscheduled | SPEC_READY | - | `GET /admin/support/conversations`, `GET /api/v1/admin/soporte` | AdminV1Routes.kt | conversaciones_soporte, mensajes_soporte | OK | NOT IN FA12. WIRE_CONFLICT: same path as EP-088, the OC client decodes a bare array with other field names. NEEDS_SCHEMA for `asignadoA` (conversaciones_soporte.asignado_a) |
| D8-03 | GET | `/api/v1/admin/soporte/conversaciones/{id}/mensajes` | unscheduled | SPEC_READY | - | `GET /admin/support/conversations/{}/messages` | AdminV1Routes.kt | mensajes_soporte | OK | NOT IN FA12. WIRE_CONFLICT: same path as EP-089, the OC client decodes a bare array. NEEDS_SCHEMA for `esNotaInterna` (mensajes_soporte has no internal-note column) |
| D8-04 | POST | `/api/v1/admin/soporte/conversaciones/{id}/mensajes` | unscheduled | SPEC_READY | - | `POST /admin/support/conversations/{}/messages` | AdminV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | mensajes_soporte, auditoria_log | OK | NOT IN FA12. WIRE_CONFLICT: same path as EP-182, the OC client sends `{assignee,text,internal}` and no Idempotency-Key. NEEDS_SCHEMA: internal-note column (owner decision 2026-10-01 keeps `internal=true` rejected with 422) and a single-transaction function (message + touch + audit) |
| D8-05 | PATCH | `/api/v1/admin/soporte/conversaciones/{id}` | unscheduled | SPEC_READY | - | - | AdminV1Routes.kt, AdminDashboardOverview.kt, Observability.kt, OposiBotsUserAdminRoutes.kt | conversaciones_soporte, auditoria_log | MISSING_SCHEMA: conversaciones_soporte.asignado_a | - |
| D8-10 | GET | `/api/v1/app/soporte/conversaciones` | unscheduled | PRODUCT_BLOCKED | UNREGISTERED (UNREGISTERED product decision: in-app support channel vs email (OB-ACT) | - | AdminV1Routes.kt | conversaciones_soporte | OK | - |
| D8-11 | POST | `/api/v1/app/soporte/conversaciones` | unscheduled | PRODUCT_BLOCKED | UNREGISTERED (UNREGISTERED product decision: in-app support channel (OB-ACT-010/112)) | - | AdminV1Routes.kt | conversaciones_soporte, mensajes_soporte | OK | - |
| D8-12 | GET | `/api/v1/app/soporte/conversaciones/{id}/mensajes` | unscheduled | PRODUCT_BLOCKED | UNREGISTERED (UNREGISTERED product decision: in-app support channel (OB-ACT-010/112)) | - | AdminV1Routes.kt | mensajes_soporte | OK | - |
| D8-13 | POST | `/api/v1/app/soporte/conversaciones/{id}/mensajes` | unscheduled | PRODUCT_BLOCKED | UNREGISTERED (UNREGISTERED product decision: in-app support channel (OB-ACT-010/112)) | - | AdminV1Routes.kt | mensajes_soporte | OK | - |
| D8-14 | GET | `/api/v1/app/configuracion` | unscheduled | PRODUCT_BLOCKED | DEC-041 | - | - | configuraciones_usuario | OK | - |
| D8-15 | PATCH | `/api/v1/app/configuracion` | unscheduled | PRODUCT_BLOCKED | DEC-041 | - | - | configuraciones_usuario | OK | - |

## 4. PRODUCT_BLOCKED list

- D2-04 `POST /api/v1/app/tests-rapidos` -> DEC-021
- D3-02 `GET /api/v1/app/liga` -> DEC-003, DEC-004, DEC-012
- D3-04 `GET /api/v1/app/racha` -> DEC-006
- D3-10 `GET /api/v1/admin/rankings` -> DEC-004, DEC-012
- D4-18 `GET /api/v1/app/noticias` -> DEC-030
- D4-22 `PUT /api/v1/app/opositeca/examenes/{examenId}` -> DEC-025
- D4-23 `DELETE /api/v1/app/opositeca/examenes/{examenId}` -> DEC-025
- D4-24 `GET /api/v1/app/opositeca` -> DEC-025
- D4-25 `GET /api/v1/app/opositeca/examenes/{examenId}` -> DEC-025
- D5-02 `GET /api/v1/app/oposired/publicaciones` -> DEC-024, DEC-023, DEC-022
- D5-03 `GET /api/v1/app/oposired/publicaciones/{id}` -> DEC-024
- D5-04 `POST /api/v1/app/oposired/publicaciones` -> DEC-029, DEC-023
- D5-07 `GET /api/v1/app/oposired/publicaciones/{id}/comentarios` -> DEC-024
- D5-11 `PUT /api/v1/app/oposired/votos` -> DEC-024
- D7-04 `POST /api/v1/webhooks/payments/{proveedor}` -> DEC-031
- D7-06 `POST /api/v1/app/subscription/trial` -> DEC-034
- D7-20 `PUT /api/v1/admin/comercial/productos/{codigo}/store-mappings/{proveedor}` -> DEC-031
- D3-09 `GET /api/v1/admin/rankings/ligas` -> DEC-003, DEC-012
- D3-11 `GET /api/v1/admin/rankings/usuario/{usuarioId}` -> DEC-003, DEC-004, DEC-006
- D3-12 `POST /api/v1/admin/rendimiento/usuarios/{usuarioId}/reconstruir` -> DEC-003, DEC-006
- D4-26 `POST /api/v1/app/recursos/{recursoId}/uso` -> UNREGISTERED (PROP-006 (product decision, no DEC id))
- D5-05 `PATCH /api/v1/app/oposired/publicaciones/{id}` -> DEC-023
- D5-13 `POST /api/v1/app/oposired/imagenes/upload-url` -> DEC-029
- D5-17 `GET /api/v1/app/opositester/semanal` -> DEC-027, DEC-040
- D5-31 `PUT /api/v1/admin/oposired/categorias/{id}` -> DEC-023
- D6-04 `POST /api/v1/app/wallet/recompensas/{reglaCodigo}/reclamar` -> DEC-036
- D7-10 `POST /api/v1/app/promotions/quote` -> DEC-031
- D7-13 `GET /api/v1/app/me/payment-methods` -> DEC-031
- D7-14 `POST /api/v1/app/me/payment-methods` -> DEC-031
- D7-15 `DELETE /api/v1/app/me/payment-methods/{id}` -> DEC-031
- D7-16 `PUT /api/v1/app/me/payment-methods/default` -> DEC-031
- D8-10 `GET /api/v1/app/soporte/conversaciones` -> UNREGISTERED (UNREGISTERED product decision: in-app support channel vs email (OB-ACT)
- D8-11 `POST /api/v1/app/soporte/conversaciones` -> UNREGISTERED (UNREGISTERED product decision: in-app support channel (OB-ACT-010/112))
- D8-12 `GET /api/v1/app/soporte/conversaciones/{id}/mensajes` -> UNREGISTERED (UNREGISTERED product decision: in-app support channel (OB-ACT-010/112))
- D8-13 `POST /api/v1/app/soporte/conversaciones/{id}/mensajes` -> UNREGISTERED (UNREGISTERED product decision: in-app support channel (OB-ACT-010/112))
- D8-14 `GET /api/v1/app/configuracion` -> DEC-041
- D8-15 `PATCH /api/v1/app/configuracion` -> DEC-041

EXTERNAL_BLOCKED: D7-03 `POST /api/v1/app/purchases/verify` (payment provider verifier; DEC-031 also fixes the `proveedor` values).

## 5. First batch (authored in this change)

| Contract | In spec | File | x-slice | Notes |
|---|---|---|---|---|
| D4-01 `GET /api/v1/admin/noticias` | YES | `openapi/news/news.yaml` | 5 | CURSOR, limit 1..100 default 50 |
| D4-02 `GET /api/v1/admin/noticias/{noticiaId}` | YES | news.yaml | null | P2, not listed in the Slice 5 plan; `ETag` = version |
| D4-03 `POST /api/v1/admin/noticias` | YES | news.yaml | 5 | Idempotency-Key REQUIRED; always draft |
| D4-04 `PATCH /api/v1/admin/noticias/{noticiaId}` | YES | news.yaml | 5 | `If-Match` required; 412 PRECONDITION_FAILED |
| D4-05 `POST .../publicar` | YES | news.yaml | 5 | state-idempotent |
| D4-06 `POST .../programar` | YES | news.yaml | 5 | `{publicarDesde}` |
| D4-07 `POST .../archivar` | YES | news.yaml | 5 | state-idempotent |
| D4-08 `DELETE /api/v1/admin/noticias/{noticiaId}` | YES | news.yaml | null | P2, not listed in the Slice 5 plan |
| D4-09 `POST /api/v1/admin/noticias/assets` | YES | news.yaml | 5 | multipart, Idempotency-Key REQUIRED |
| D4-10 `GET /api/v1/admin/tipos-noticia` | YES | news.yaml | 5 | OFFSET, limit 1..200 default 100 |
| D4-11 `PUT /api/v1/admin/tipos-noticia/{codigo}` | YES | news.yaml | 5 | upsert by `codigo`, 201 or 200 |
| D6-01 `GET /api/v1/app/wallet` | YES | `openapi/wallet/wallet.yaml` | 7 | VALUE_ONLY: `recompensas` null until DEC-036 |
| D6-02 `GET /api/v1/app/wallet/movimientos` | YES | wallet.yaml | 7 | CURSOR, limit 1..50 default 20 |
| D2-15 `GET /api/v1/public/examenes` | ALREADY | `openapi/catalog/catalog.yaml` | null | present since the Slice 0 freeze (TARGET); nothing added |
| D2-16 `GET /api/v1/public/examenes/{examId}` | ALREADY | catalog.yaml | null | same |
| D2-17 `GET /api/v1/public/especialidades` | ALREADY | catalog.yaml | null | same |

No contract of the batch turned out PRODUCT_BLOCKED. All 13 new operations are `x-implementation: TARGET` (the marker the spec uses for "designed, not served yet"); none is LIVE.

How the design text was mapped onto the frozen conventions (same rules Slices 0-3 applied):

- Bare statuses and generic names use the frozen generic catalog: design `403 NOT_ADMIN` is `FORBIDDEN`; `409 IDEMPOTENCY_CONFLICT` is the frozen pair `409 IDEMPOTENCY_KEY_IN_PROGRESS` + `422 IDEMPOTENCY_KEY_REUSED`; `412 VERSION_MISMATCH` is `PRECONDITION_FAILED`; `413 FILE_TOO_LARGE` is `PAYLOAD_TOO_LARGE`; `415 UNSUPPORTED_MIME` is `UNSUPPORTED_MEDIA_TYPE`; `403 ACCOUNT_DISABLED` is `ACCOUNT_BLOCKED | ACCOUNT_DELETION_PENDING | PROFILE_MISSING`.
- Contract-specific codes are kept verbatim: `NEWS_NOT_FOUND`, `NEWS_NOT_EDITABLE`, `INVALID_TRANSITION`, `NEWS_INCOMPLETE`, `NOT_FUTURE_INSTANT`, `INACTIVE_TIPO_OR_ESPECIALIDAD`, `ASSET_PATH_UNKNOWN`, `SLUG_CONFLICT`, `INVALID_FILTER`.
- Page envelopes are the shared `CursorPageEnvelope` / `OffsetPageEnvelope`; D4-10 therefore returns `{items, total?, limit, offset}` (design: `items, total`).
- A `400` (path id that is not a uuid) and, for D6-02, a `403` (deny-by-default account state) were added where the design row omits them; omitting the 403 is exactly the known contract gap A10 of D1-17.

Points the design does NOT fix and this change did not invent (left open in the spec, to be closed before the builder starts):

1. `origen` of a movement is "derived from the ledger tipo" but the design has no tipo -> origen table. The spec only fixes the enum. Proposed mapping for the lead to confirm (NOT frozen): `recarga`, `pack_tickets` -> compras; `bonus_racha` -> rachas; `reto_diario` -> retos; `referido` -> invitaciones; `reporte_validado` -> opositester; `regalo` -> recompensas; `gasto`, `compra_examen`, `compra_servicio`, `ajuste`, `devolucion`, `potenciador`, `protector` -> otros.
2. `RewardProgress` (items of `recompensas`) has no shape anywhere in the design although the row says "shape fixed". The spec declares `recompensas: array | null` with untyped object items, the same pattern as `comparativa` in attempts.yaml.
3. `activaciones_servicio_usuario.estado` also allows `cancelada` in production; the design enum is `activa | usada | caducada`. The spec keeps the design enum and states that cancelled activations are not returned.
4. Arrays without a `!` in the design (`recientes`, `consumibles`, `ganancias.porOrigen`) are declared required (always present, possibly empty), because the row marks only `recompensas` as optional.
5. `noticia.texto` is NOT NULL DEFAULT '' in production while the design marks `texto?`; the spec keeps it optional and nullable.
6. `NoticiaCapa` is an opaque JSON object (design: `capas: json[]`); the layer structure the OposiControl editor writes (`NoticiaLayerDto` in NewsGateway.kt) is not frozen.

## 6. Proposed amendment row FA11 (text for CONTRACT_FREEZE.md, OposiBot repo)

| ID | Fecha | Contrato / fichero | Cambio | Motivo | DEC |
|---|---|---|---|---|---|
| FA11 | 2026-10-08 | NUEVOS `openapi/news/news.yaml` (D4-01..D4-11) y `openapi/wallet/wallet.yaml` (D6-01, D6-02); `root.yaml` (10 paths, tags `news-admin` y `wallet`, una frase en `info.description`); `common/identifiers.yaml` (parametro `NoticiaIdPath`); `common/pagination.yaml` (SOLO comentario: D4-10 entra en la lista de grids OFFSET) | AMPLIACION ADITIVA, sin tocar ninguna operacion ni schema congelado: 13 operaciones nuevas, todas `x-implementation: TARGET`. Noticias admin: listado CURSOR (D4-01), detalle con `ETag` = version (D4-02), alta de borrador con `Idempotency-Key` obligatoria (D4-03), edicion de contenido con `If-Match` obligatorio y 412 `PRECONDITION_FAILED` (D4-04), comandos de estado idempotentes `publicar`/`programar`/`archivar` (D4-05..D4-07), borrado (D4-08), subida de asset multipart con `Idempotency-Key` (D4-09), tipos de noticia OFFSET (D4-10) y upsert por `codigo` (D4-11). Wallet: resumen (D6-01) y movimientos CURSOR (D6-02), usuario = JWT sub. `x-slice`: 5 para D4-01, D4-03..D4-07, D4-09..D4-11; null para D4-02 y D4-08 (P2, fuera del listado de SLICE 5 del plan); 7 para D6-01 y D6-02. Los codigos genericos del diseno se expresan con el catalogo congelado (`NOT_ADMIN` = `FORBIDDEN`, `IDEMPOTENCY_CONFLICT` = 409 `IDEMPOTENCY_KEY_IN_PROGRESS` + 422 `IDEMPOTENCY_KEY_REUSED`, `VERSION_MISMATCH` = `PRECONDITION_FAILED`, `FILE_TOO_LARGE` = `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MIME` = `UNSUPPORTED_MEDIA_TYPE`, `ACCOUNT_DISABLED` = `ACCOUNT_BLOCKED`/`ACCOUNT_DELETION_PENDING`/`PROFILE_MISSING`); los especificos se conservan (`NEWS_NOT_FOUND`, `NEWS_NOT_EDITABLE`, `INVALID_TRANSITION`, `NEWS_INCOMPLETE`, `NOT_FUTURE_INSTANT`, `INACTIVE_TIPO_OR_ESPECIALIDAD`, `ASSET_PATH_UNKNOWN`, `SLUG_CONFLICT`, `INVALID_FILTER`). Se anade 400 (id de ruta no uuid) donde el diseno lo omite y 403 en D6-02 (deny-by-default del estado de cuenta, leccion del gap A10 de D1-17). Manifiesto `freeze-manifest.sha256` regenerado (5 ficheros: 2 nuevos, 3 cambiados) y `OpenApiContractTest` ampliado (13 rutas, D4-03/D4-09 en `idempotencyRequired`, D4-01/D6-02 en `cursorLists`, D4-10 en `offsetLists`). D2-15/D2-16/D2-17 (catalogo publico) ya estaban en el contrato: sin cambios | El contrato OpenAPI debe representar el backend final. Los 13 contratos tienen el wire completamente determinado por el diseno (`openapiReady: YES`), sus tablas existen en produccion (`noticia`, `tipo_noticia`, `carteras_usuario`, `transacciones_cartera`, `activaciones_servicio_usuario`) y sustituyen rutas legacy ya servidas (`/content/noticias*`, `/admin/news-types*`). NO interviene ninguna decision de producto en su comportamiento: el feed de noticias de la app (D4-18, DEC-030), el canje (D6-03, valores DEC-035), la reclamacion de recompensas (D6-04, DEC-036), la verificacion de compras (D7-03) y el webhook de pagos (D7-04, DEC-031) quedan FUERA. Regla del propietario: los endpoints tecnicos con semantica ya determinada por el diseno no requieren aprobacion por endpoint | VALUE_ONLY: `WalletSummary.recompensas` = null hasta DEC-036 (forma de sus items sin congelar). ADITIVO: el enum del filtro `tipo` de D6-02 (14 tipos del CHECK actual) solo puede crecer con DEC-040; `Movimiento.tipo` y `Movimiento.origen` son string abiertos. PENDIENTE DE CONFIRMAR (no es decision de producto registrada, el diseno no lo fija): tabla tipo -> origen de los movimientos. Sin aprobacion del propietario por endpoint (ampliacion tecnica); ratificacion del lead pendiente |

## 7. Gates

Run on 2026-10-08 in the working tree (changes NOT committed):

| Gate | Command | Result |
|---|---|---|
| Lint | `npm ci --prefix tools/redocly --ignore-scripts` then `npx --no-install --prefix tools/redocly redocly lint openapi/root.yaml --config openapi/redocly.yaml` | PASS ("Your API description is valid", the same 3 explicitly ignored problems as before; no new lint-ignore entry) |
| Backend tests | `./gradlew :backend:ktor:test --console=plain` | 1594 tests, 1593 passed, 1 failed |

The single failure is `Slice1ContractConformanceTest.openApiIsFrozenNothingUnderOpenapiDiffersFromHead`: it runs `git status --porcelain -- openapi` and fails while `openapi/` differs from HEAD. It is the expected tripwire of an uncommitted tree and it was not weakened; it passes once the change is committed. `OpenApiFreezeManifestTest` (both tests), `OpenApiContractTest` (all tests, with the 13 new routes) and `RouteInventoryTest` pass.

Freeze manifest: the repo has no generator (the manifest is only read by `OpenApiFreezeManifestTest`). It was regenerated by a script that applies the exact rule of that test (sha256 of the UTF-8 text with CRLF normalised to LF, every file under `openapi/`); the 11 untouched files reproduced their pinned hashes byte for byte, which validates the rule. Changed: `common/identifiers.yaml`, `common/pagination.yaml`, `root.yaml`. Added: `news/news.yaml`, `wallet/wallet.yaml`.

## 8. Next

1. Lead: confirm the tipo -> origen mapping and the three overrides (D3-09, D3-11, D5-05); copy the FA11 row into CONTRACT_FREEZE.md.
2. Builder (Slice 5 news): canonical routes over `NewsGateway`/`SupabaseNewsGateway` with keyset listing, `If-Match` on `actualizado_en`, state commands and the idempotency store; keep `/content/noticias*` and `/admin/news-types*` until zero telemetry. No migration is needed for news.
3. Builder (wallet reads): one forward-only migration for the keyset index `transacciones_cartera (usuario_id, creado_en desc, id)`; no other schema change.
4. FA12 took D5-20, D5-21, D5-22, D5-25, D5-26, D5-28, D5-29 and D7-27 from that batch (section 9). The rest of it is classified in section 9.3 (same-path wire conflicts and missing schema) and 9.4 (implementable, not reached).

## 9. Second batch: FA12 (implemented in this change)

### 9.1 What is in the spec and served

| Contract | File | x-slice | Pagination | Notes |
|---|---|---|---|---|
| D5-20 `GET /api/v1/admin/moderacion/hilos` | `openapi/moderation/moderation.yaml` | null | CURSOR, limit 1..50 default 20 | `modo` (the client's `reportados=true` still works); rows gain `reportado` and `pendingReports` |
| D5-21 `GET /api/v1/admin/moderacion/hilos/{id}` | moderation.yaml | null | NONE | 404 `NOT_FOUND` with the canonical envelope |
| D5-22 `GET /api/v1/admin/moderacion/hilos/{id}/respuestas` | moderation.yaml | null | CURSOR, 1..50 default 20 | oldest first |
| D5-25 `GET /api/v1/admin/reportes` | moderation.yaml | 6 | CURSOR, 1..200 default 200 | `items` + legacy `data`; `nextCursor`, `pendientesTotal`, `tipoEntidad` |
| D5-26 `GET /api/v1/admin/reportes/{id}` | moderation.yaml | null | NONE | `data` + legacy top-level copy; `acciones` (newest first, max 50) |
| D5-28 `GET /api/v1/admin/feedback/preguntas` | moderation.yaml | null | CURSOR, 1..50 default 20 | `items` + legacy `data`; filter `tipoFeedback` (legacy `tipo` still accepted) |
| D5-29 `GET /api/v1/admin/feedback/preguntas/{id}` | moderation.yaml | null | NONE | `{data}` |
| D7-27 `GET /api/v1/admin/suscripciones` | `openapi/commerce/commerce.yaml` | null | CURSOR, 1..100 default 50 | new route; read-only; provider payload columns are never selected |

All eight are `x-implementation: TARGET` (the marker every non-Slice-0 operation carries) and read-only: no audit row, no idempotency key, no RPC, no migration.

Code: the seven moderation reads keep their handlers in `AdminV1Routes.kt` and their queries in `AdminV1Gateway` (the legacy aliases `GET /api/v1/admin/moderacion` and `GET /api/v1/admin/moderacion/reportes` call the same `listReportes` and keep their `{data}` wrapper); request parsing is in `ModerationQueries.kt`. D7-27 is `AdminSubscriptionReads.kt` plus `AdminV1Gateway.listSuscripciones`. Tests: `ModerationReadRoutesTest` (30) and `AdminSubscriptionRoutesTest` (8).

One defect fixed on the way, backed by the production DDL: the forum code looked for reports with `tipo_entidad` in (`hilo`, `publicacion`, `comentario`, `respuesta`), values the CHECK `reportes_contenido_tipo_entidad_check` does not allow. The real values `publicacion_oposired` and `comentario_oposired` are now matched first, so "reported threads", the pending-report counters and the content preview of a report can return rows.

### 9.2 How the design was mapped (decisions taken, reversible)

- **Same path as the current client: the wire only grows.** Seven of the eight contracts live at the METHOD + path the OposiControl client already calls. Their canonical wire is the existing one plus additive fields and parameters. Where the design names the page `data` (D5-25, D5-28) the spec uses the shared `CursorPageEnvelope` (`items`) and `data` duplicates it, deprecated, until the client migrates (the rule D1-22 and D1-23 already use). D5-26 is the reverse case: canonical `data`, legacy top-level copy.
- Fields that used to be omitted when equal to their default (`""`, `0`, `false`) are now always emitted (`@EncodeDefault`), so the contract can declare them required. Additive for the client.
- Cursors of D5-20, D5-22 and D5-28 keep the opaque format the routes already emitted (pair `creado_en` + id, pinned by existing tests); they are validated before any read and an unreadable one is `400 INVALID_CURSOR`. They carry no filter fingerprint, unlike the D5-25 and D7-27 cursors (shared codec).
- D5-25 keeps `limit` 1..200 default 200 because the current client reads the whole pending queue in one call and does not follow `nextCursor`; the design says 1..50. `nextCursor` is emitted when the page is full (the first-page query is unchanged), so the last page can be empty. The legacy `offset` parameter is still accepted but is not in the spec (the contract test forbids `offset` on a CURSOR list).
- D5-28: the design filter `tipo` is declared as `tipoFeedback` (the contract test binds a `tipo` parameter on a `/preguntas` path to the question types). `estado` and `tipoFeedback` are not rejected when unknown: an existing test requires the read to go out with its `limit` even for a hostile `estado`, so the value is encoded and matches no row.
- `x-roles` is `[ADMIN]`: the design lists MODERATOR too, but the admin plane guard admits active admin accounts only. Opening these reads to a moderator role is an authorization change, not part of this batch.
- `pendingReports` follows the query the page already runs: in `modo=reportados` it counts pending reports on the thread and on its replies (at most 500 pending reports are considered); in `recientes` only reports on the thread itself.

### 9.3 Contracts of the batch that could NOT be implemented as designed

Finding that the classification of section 3 missed: for most admin contracts of this batch the canonical METHOD + path is ALREADY served and called by the OposiControl client (`data/`, which the backend work must not edit) with a different wire. A response that is a JSON object can grow (duplicate rule above); these cannot:

| Contract | Why | Needs |
|---|---|---|
| D8-02, D8-03 | The client decodes a bare JSON array (`List<SupportConversationDto>`, `List<SupportMessageDto>`, English field names); the canonical wire is `{items, nextCursor}`. | Client migration first, or a decision on how two wires share one path. Schema: `conversaciones_soporte.asignado_a` (`asignadoA`), an internal-note column on `mensajes_soporte` (`esNotaInterna`). |
| D8-04 | The client posts `{assignee, text, internal}` without `Idempotency-Key`; the canonical command requires the header and `{cuerpo, interna}`. | Same as above, plus a database function for "message + touch conversation + audit" in one transaction. `interna` is a separate capability by owner decision (2026-10-01). |
| D5-23 | Bare array to the client. | Schema: `acciones_moderacion` has only `reporte_id`; `targetTipo`, `targetId`, `targetUser` have no source. |
| D5-24 | The client posts without `Idempotency-Key`. | Schema: target columns on `acciones_moderacion`; `fijar`/`desfijar` are not in `acciones_moderacion_tipo_accion_check`; a single-transaction function. |
| D6-12, D6-13 | Bare arrays to the client. | Client migration first. |
| D6-14 | The client posts `{amount, reason}` and no `Idempotency-Key` header; canonical is `{delta, tipo, motivo}` with the header required. The atomic path exists (`admin_grant_tickets`). | Client migration first. |
| D3-05 | Precondition of the plan not met: no ADR records the TECH default DEC-038. | The ADR. |

### 9.4 Implementable, not reached in this change

- Same path as the client but object responses, so the duplicate rule applies: D7-17, D7-21 (reads), D6-18, D6-20, D6-22 (reads), D6-19, D6-21, D6-23 (upserts by `codigo` at the path the client already posts to), D3-15 (existing overview shape).
- New paths: D7-18, D7-19, D7-22, D7-23 (PUT upserts), D7-29, D6-06, D7-05.
- To check before building them: D6-19 and D7-23 replace a set of child rows ("in one DB fn" in the design); if no such function exists in production they are NEEDS_SCHEMA. `reglas_popup_comercial` stores `cta_principal` and `cta_secundario` as text, while D6-06, D6-22 and D6-23 expose `ctas: [{etiqueta, accion, destino?}]`: the mapping is not defined by the design. D6-06 filters by "user plan" against `requiere_plan_minimo`; the comparison rule must come from the design, not from the builder. The names of the admin catalog wire differ from the design (`tipoProducto`/`tipo`, `tarifas`/`tariffs`, `etiquetaPromocional`/`etiqueta`, `planId`/`planCodigo`), so D7-17 needs both sets of names until the client migrates.

### 9.5 Proposed amendment row FA12 (text for CONTRACT_FREEZE.md, OposiBot repo)

| ID | Fecha | Contrato / fichero | Cambio | Motivo | DEC |
|---|---|---|---|---|---|
| FA12 | 2026-10-08 | NUEVOS `openapi/moderation/moderation.yaml` (D5-20, D5-21, D5-22, D5-25, D5-26, D5-28, D5-29) y `openapi/commerce/commerce.yaml` (D7-27); `root.yaml` (8 paths, tags `moderation-admin` y `commerce-admin`, una frase en `info.description`) | AMPLIACION ADITIVA, sin tocar ninguna operacion ni schema congelado: 8 operaciones nuevas de SOLO LECTURA, todas `x-implementation: TARGET`. Moderacion: hilos de OposiRed por cursor (D5-20, `modo`), detalle (D5-21), respuestas por cursor (D5-22), reportes por cursor con `pendientesTotal` (D5-25, `x-slice` 6), detalle de reporte con `contentPreview` y `acciones` (D5-26), feedback de preguntas por cursor (D5-28) y detalle (D5-29). Comercio: lista de suscripciones por cursor (D7-27). Siete de las ocho viven en la ruta que ya llama OposiControl: el cable solo crece y los duplicados legacy (`data` en D5-25 y D5-28, copia de primer nivel en D5-26, `tieneReportePendiente` en D5-20) se marcan `deprecated`. Manifiesto `freeze-manifest.sha256` regenerado (2 ficheros nuevos, 1 cambiado) y `OpenApiContractTest` ampliado (8 rutas; D5-20, D5-22, D5-25, D5-28 y D7-27 en `cursorLists`). `undocumented-routes.tsv` baja de 169 a 162 | Los ocho contratos son lecturas con el cable determinado por el diseno y por lo que ya se sirve, sobre tablas que existen en produccion; ninguna decision de producto cambia su comportamiento | VALUE_ONLY por esquema, no por DEC: `resolucionNota` (D5-28) y `canceladaEn` (D7-27) son null hasta que exista la columna. FUERA: D5-23, D5-24, D8-02..D8-04, D6-12..D6-14 (conflicto de cable en la misma ruta y/o esquema ausente, seccion 9.3). Ratificacion del lead pendiente |

### 9.6 Gates (2026-10-08, working tree, NOT committed)

| Gate | Command | Result |
|---|---|---|
| Lint | `npx --no-install --prefix tools/redocly redocly lint openapi/root.yaml --config openapi/redocly.yaml` | PASS (the same 3 explicitly ignored problems) |
| Backend tests | `./gradlew :backend:ktor:test --console=plain` | 1691 tests, 1690 passed, 1 failed (the expected tripwire `openApiIsFrozenNothingUnderOpenapiDiffersFromHead`, which fails while `openapi/` differs from HEAD) |
| Direct Supabase access | `bash scripts/verify-no-direct-supabase.sh` | PASS |
| Ops config | `bash scripts/verify-ops-config.sh` | PASS |

No migration was added, so the database gates were not run.

## 10. Third batch: FA13 (implemented in this change)

### 10.1 What is in the spec and served

| Contract | File | x-slice | Pagination / idempotency | Notes |
|---|---|---|---|---|
| D7-17 `GET /api/v1/admin/comercial/productos` | `openapi/commerce/commerce.yaml` | null | OFFSET, limit 1..100 default 100 | filters `tipo`, `activo`; tariffs fetched for the products of the page only |
| D7-21 `GET /api/v1/admin/planes` | commerce.yaml | null | OFFSET, 1..100 default 100 | filter `activo`; benefits fetched for the plans of the page only |
| D6-18 `GET /api/v1/admin/market/servicios` | commerce.yaml | null | OFFSET, 1..100 default 100 | filter `activo` |
| D6-20 `GET /api/v1/admin/market/promociones` | commerce.yaml | null | OFFSET, 1..100 default 100 | filter `activo`; `usos` counted by the database in the same query |
| D6-22 `GET /api/v1/admin/market/popups` | commerce.yaml | null | OFFSET, 1..100 default 100 | filters `activo`, `pantalla` |
| D3-15 `GET /api/v1/admin/dashboard/overview` | `openapi/dashboard/dashboard.yaml` | null | NONE | the served six-block shape; `sections`; 400 `UNKNOWN_DASHBOARD_SECTION` |
| D7-22 `PUT /api/v1/admin/planes/{codigo}` | commerce.yaml | null | NATURAL (upsert by `codigo`) | 201 or 200, `{data: plan}`, audit `plan_suscripcion_guardado` |
| D7-19 `PUT /api/v1/admin/comercial/productos/{codigo}/tarifas/{periodo}/{fase}` | commerce.yaml | 7 | NATURAL (upsert by product, period, phase) | 201 or 200, `{data: tariff}`, 404 `PRODUCT_NOT_FOUND`, audit `tarifa_producto_guardada` |

All eight are `x-implementation: TARGET`. Code: `AdminCatalogReads.kt` (page envelope, request parsing, the two commands) and `AdminV1Gateway` (`pageProductosComerciales`, `pagePlanes`, `pageServices`, `pagePromotions`, `pagePopupRules`, `putPlan`, `putTarifa`); handlers in `AdminV1Routes.kt`; the overview route only gains its cache header. Tests: `AdminCatalogReadRoutesTest` (13) and `AdminCatalogWriteRoutesTest` (8). The legacy POST routes of the current client (`POST /api/v1/admin/planes`, `POST .../productos/{id}/tarifas`) are unchanged.

### 10.2 How the design was mapped (decisions taken, reversible)

- **The five catalog reads keep the client's field names** (lead instruction): camelCase product and tariff fields (`tipoProducto`, `planId`, `etiquetaPromocional`, `tarifas`), snake_case service, promotion, popup and benefit fields. The design names (`tipo`, `planCodigo`, `etiqueta`, `tariffs`, `benefits`, `valueBool`...) are not emitted.
- **Default page size 100, not 50.** The current client asks for the whole catalog without parameters; before this change the read was unbounded. A default of 100 (the maximum) keeps today's catalogs complete and `total` shows when one stops fitting.
- Fields the client already received only when different from their default are now always emitted (`@EncodeDefault`).
- Left out because the design does not define them (section 10 of PRODUCT_DECISIONS_PENDING.md): `q` (D7-17), `storeMappings` (D7-17, DEC-031), `efectos` (D6-18), `vigente` (D6-20), `ctas` and `conMetricas`/`metricas` (D6-22).
- D7-22 and D7-19 are each ONE `INSERT .. ON CONFLICT` statement on a production UNIQUE constraint, then the audit row (mandatory: a failed audit write is a 5xx, never a silent success). The design asks for statement and audit row in one database transaction; that needs a database function, which does not exist. The existing POST routes already write the same tables the same way. If the lead reads "money or entitlements" as covering price and plan DEFINITIONS, these two must move behind a function.
- The bodies of D7-22 and D7-19 are parsed by a strict hand-written parser (unknown, missing or mistyped field is 400), not as `@CanonicalCommand` DTOs: the architecture rule for those DTOs forbids by NAME a field called `posicion` (it means ranking position there), and here `posicion` is the display order the contract requires. The news commands follow the same route. The lead may prefer to teach the rule the difference.
- D7-22: the optional `If-Match` of the design family is not offered (no version column is exposed by the plan read). The path `codigo` is limited to 64 characters of letters, digits, `_`, `.`, `-`.
- D3-15: `x-error-codes` lists the code the route already answers (`UNKNOWN_DASHBOARD_SECTION`); the design row only says 401, 403, 503.

### 10.3 Not implemented in FA13

| Contract | Class | Why |
|---|---|---|
| D6-19 | NEEDS_SCHEMA | no database function replaces a service and its effects in one transaction |
| D7-23 | NEEDS_SCHEMA | no database function replaces the benefit set of a plan in one transaction |
| D6-21 | DESIGN vs SCHEMA | design enum {porcentaje, importe}; production CHECK {porcentaje, cantidad, tickets_extra} |
| D6-23 | DESIGN pending | `ctas` required, no mapping to `cta_principal` / `cta_secundario` |
| D6-06 | DESIGN pending | `ctas` mapping and the plan filter rule are not defined |

### 10.4 Implementable, not reached

D7-18 (needs the request field names chosen), D7-29, D7-05. Count: 3.

### 10.5 Proposed amendment row FA13 (text for CONTRACT_FREEZE.md, OposiBot repo)

| ID | Fecha | Contrato / fichero | Cambio | Motivo | DEC |
|---|---|---|---|---|---|
| FA13 | 2026-10-08 | `openapi/commerce/commerce.yaml` (D7-17, D7-21, D6-18, D6-20, D6-22, D7-22, D7-19 anadidos junto a D7-27); NUEVO `openapi/dashboard/dashboard.yaml` (D3-15); `root.yaml` (8 paths, tag `dashboard-admin`, una frase en `info.description`) | AMPLIACION ADITIVA, sin tocar ninguna operacion ni schema congelado: 8 operaciones nuevas, todas `x-implementation: TARGET`. Cinco lecturas de catalogo OFFSET en la ruta que ya llama OposiControl (`items` + duplicado legacy `data`, `total`, `limit` 1..100 defecto 100, `offset`), con los nombres de campo que ese cliente ya lee; resumen del panel tal como se sirve (D3-15); dos upserts por clave natural en rutas nuevas (D7-22 por `codigo`, D7-19 por producto, periodo y fase), 201 o 200 con `{data}`. Manifiesto `freeze-manifest.sha256` regenerado (1 fichero nuevo, 2 cambiados) y `OpenApiContractTest` ampliado (8 rutas; las cinco lecturas en `offsetLists`). `undocumented-routes.tsv` baja de 162 a 156 | Contratos con el cable determinado por el diseno y por lo que ya se sirve, sobre tablas y restricciones UNIQUE que existen en produccion | FUERA: D6-19 y D7-23 (falta funcion atomica), D6-21 (enum del diseno distinto del CHECK), D6-23 y D6-06 (mapeo de `ctas` y regla de plan sin definir), D7-20 (DEC-031). Ratificacion del lead pendiente |

### 10.6 Gates (2026-10-08, working tree, NOT committed)

| Gate | Command | Result |
|---|---|---|
| Lint | `npx --no-install --prefix tools/redocly redocly lint openapi/root.yaml --config openapi/redocly.yaml` | PASS (the same 3 explicitly ignored problems) |
| Backend tests | `./gradlew :backend:ktor:test --console=plain` | 1712 tests, 1711 passed, 1 failed (the expected tripwire `openApiIsFrozenNothingUnderOpenapiDiffersFromHead`) |
| Direct Supabase access | `bash scripts/verify-no-direct-supabase.sh` | PASS |
| Ops config | `bash scripts/verify-ops-config.sh` | PASS |

No migration was added, so the database gates were not run.

## 11. Fourth batch: FA14 (implemented in this change) and where the plan stands

### 11.1 Done

| Contract | File | Pagination | Notes |
|---|---|---|---|
| D7-29 `GET /api/v1/admin/market/promociones/{id}/usos` | `openapi/commerce/commerce.yaml` | CURSOR, limit 1..100 default 50 | `{items, nextCursor, stats}` (the design nests `items` and `stats` under `data`; the shared cursor envelope is used instead); three exact counters; `descuentoTotalCentimos` null until an aggregate function exists; 404 for an unknown promotion |

Code: `AdminPromotionUses.kt`, `AdminV1Gateway.listPromotionUses`, handler in `AdminV1Routes.kt`. Tests: two more in `AdminCatalogReadRoutesTest` (15 in total). `TARGET`, read-only, no migration.

Proposed amendment row FA14 (text for CONTRACT_FREEZE.md): `commerce/commerce.yaml` gains D7-29 (one read-only CURSOR operation, `x-implementation: TARGET`), `root.yaml` one path and one sentence; manifest regenerated (2 files changed); `OpenApiContractTest` extended (1 route, 1 cursor list). VALUE_ONLY by schema: `descuentoTotalCentimos` null. Lead ratification pending.

### 11.2 Not reached in FA14 (the builder stopped here on purpose, to keep the quality of what is delivered)

Still implementable, none started (21):

| Group | Contracts | To check before building |
|---|---|---|
| Consumer reads (BOT) | D7-05, D5-01, D5-16, D4-19, D3-01, D3-03, D7-01 | D3-01, D3-03 and D7-01 are at paths a legacy app route already serves (`/api/v1/app/home`, `/estadisticas`, `/commercial/catalog`): same-path rule applies; D4-19 reads question resources and depends on the access rule (watch DEC-025); D5-16 `nivel` null (DEC-028) |
| Admin reads (CONTROL) | D6-15 | `gastadosEquivalenteCentimos` null (DEC-042); same path as the served `/tickets/resumen` |
| Admin writes | D7-18, D5-27 | D7-18: field names of D7-17 (lead decision taken); D5-27: same path as EP-181, must use `admin_grant_tickets` and answer 422 while the reward is undecided |
| Consumer writes | D5-08, D5-14, D5-15, D5-06, D5-09, D5-10 | D5-15 is at a path a legacy app route serves; the others write forum content while the forum read contracts (D5-02, D5-03, D5-07) are still product-blocked, so check they make sense alone |
| Question resources | D4-13, D4-14, D4-15, D4-16, D4-17 | They live under `/api/v1/admin/preguntas/{id}/recursos`, inside the protected question authoring code: need an explicit go-ahead from the lead before any change |

### 11.3 Totals (123 contracts that `openapi/` did not declare at the base)

| Bucket | Count | Contracts |
|---|---|---|
| In spec + implemented | 30 | FA11: D4-01..D4-11, D6-01, D6-02. FA12: D5-20, D5-21, D5-22, D5-25, D5-26, D5-28, D5-29, D7-27. FA13: D7-17, D7-21, D6-18, D6-20, D6-22, D3-15, D7-22, D7-19. FA14: D7-29 |
| NEEDS_SCHEMA | 12 | D4-20, D4-21, D5-30, D6-03, D1-20, D3-14, D5-12, D7-07, D7-30, D8-05, D6-19, D7-23 |
| Same-path wire conflict | 8 | D8-02, D8-03, D8-04, D5-23, D5-24, D6-12, D6-13, D6-14 |
| Product or external blocked | 38 | the 37 of section 4 plus D7-03 |
| Design not defined / precondition | 4 | D6-21, D6-23, D6-06; D3-05 (no ADR for DEC-038) |
| Internal (no HTTP route) | 10 | section 3, class INTERNAL |
| Still implementable | 21 | section 11.2 |
| Total | 123 | |
