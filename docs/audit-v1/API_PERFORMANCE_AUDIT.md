# API_PERFORMANCE_AUDIT — V1

Fecha: 2026-10-19 · Ciclo 1: inventario y análisis estático. **Nada medido todavía en este ciclo; ninguna optimización aplicada.** Toda mejora futura registrará: medición antes, hipótesis, cambio, medición después, impacto en seguridad/consistencia y tests de regresión.

Árbol analizado: `feature/december-v1-server` @ `86f0da9` (comparado con `develop` @ `7fb9000`).

## Arquitectura de acceso a datos

- Sin JDBC directo: todo es PostgREST por HTTP con un `HttpClient` JDK compartido y bloqueante dentro de `withUpstreamIo` (máx. 64 hilos IO por réplica).
- Valkey solo para rate limit (Lua EVAL, `OposiBotsAuthRateLimiter.kt:281`); no cachea datos ni roles.
- Coste fijo por petición `/api/v1/app/*`: verificación ES256 local (JWKS en caché 10 min) + 1 EVAL Valkey + 1 lectura **sin caché** de `perfiles_usuario` (`CanonicalIdentity.kt:114`) + RPC(s) del handler, que en muchos casos vuelven a leer el perfil por PK.
- Admin: rol y `estado_cuenta` en caché en memoria 90 s por réplica (≤1000 entradas; `AppV1Routes.kt:730-790, 1397`).

## Inventario de rutas (por familia)

Matriz completa fila a fila: `ops/inventory/route-profiles.tsv` en v1-server (332 combined / 100 bot / 240 control; protegida por `RouteProfileGateTest`).

| Método | Ruta | Archivo | Perfil / auth | Acceso a BD | Criticidad / notas |
|---|---|---|---|---|---|
| POST/GET | `/v1/auth/*` | OposiBotsAuthRoutes.kt:29-160 | bot / límite IP+sujeto | GoTrue + RPC `oposibots_claim_oauth_credential` | auth |
| GET/POST/PATCH | `/api/v1/app/me*`, `/legal/documents` | CanonicalProfileRoutes.kt:44-101 | bot / JWT | RPCs perfil + idem | auth |
| GET | `/api/v1/public/especialidades`, `/api/v1/app/examenes*`, `/me/examenes`, `/me/entitlement` | CatalogRoutes.kt:53-91 | bot / público, JWT | 5-7 lecturas PostgREST secuenciales + firma Storage | lectura; H7 |
| GET | `/v1/especialidades`, `/v1/examenes*` | V1Routes.kt:16-35 | compartido / ninguna | `select=*` sin límite; caché 30 s, 512 entradas | sin single-flight (H11) |
| POST/GET/PUT | `/api/v1/app/.../intentos*` | AttemptRoutes.kt:105-186 | bot / JWT + idem | RPCs `app_*_exam_attempt*`, idem | camino caliente; H1, H2 |
| GET | `/api/v1/app/wallet*` | WalletRoutes.kt:46-55 | bot / JWT | keyset con índice | OK |
| POST/GET | `/api/v1/app/wallet/canjes`, `/wallet/paquetes`, `/payments/orders*` | EconomyRoutes.kt:82-134 | bot / JWT + idem | RPCs redeem/order con lock por cartera | **económico** |
| POST | `/api/v1/webhooks/payments/{provider}` | EconomyRoutes.kt:149 | bot / firma + IP | RPC credit/refund | **económico** |
| GET/PUT | `/api/v1/app/plan/*` | PlanQuota.kt:124-137 | bot / JWT | RPCs plan | económico |
| GET/PUT/POST | `/api/v1/app/retos/*`, `/racha` | Engagement.kt:164-195 | bot / JWT | RPCs reto | lock global H3 |
| GET | `/api/v1/app/estadisticas/*` | Stats.kt:105-117 | bot / JWT | RPCs stats | lectura |
| GET/PUT | `/api/v1/app/ranking*` | Ranking.kt:90-95 | bot / JWT | RPCs ranking | lectura |
| GET | `/api/v1/app/me/invitacion*` | Referral.kt:116-121 | bot / JWT | RPCs referral | — |
| GET/PUT/DELETE | `/api/v1/app/opositeca*` | SavedItems.kt:223-244 | bot / JWT | RPCs saved (keyset) | OK |
| * | `/api/v1/app/oposired/**` (17 rutas) | Foro.kt:222-310 | bot / JWT + límite | RPCs `app_foro_*` + Storage | H4, H5, H8 |
| * | `/api/v1/admin/**` | AdminV1Routes.kt:334-1735 | control / admin (caché 90 s) | PostgREST (~92 `select=*`), RPCs `admin_*` | H6, H9 |
| GET | `/api/v1/admin/dashboard/overview` | AdminDashboardOverview.kt:1063 | control | ~13 `count=exact` en paralelo + 2 RPC | H6 |
| GET | `/api/v1/admin/analytics/*`, `/rankings/*` | AdminAnalytics.kt, AdminRankings.kt | control | count exact + vistas; offset | admin |
| * | `/admin/**`, `/content/**` legacy | AdminGateway.kt, Application.kt:856-1065 | control | `select=*` sin límite; borrado Storage uno a uno | legacy |
| GET | `/health*`, `/metrics` | Application.kt, Observability.kt, PrometheusMetrics.kt | todos | ready → Supabase | `/metrics` abierto en v1-server (SEC-V1-03) |

Rutas Control V1 del panel (29, `AdminControl*.kt` en `feature/december-v1-control-panel`): no están en v1-server ni en OpenAPI; ver `CONTROL_PANEL_V1_AUDIT.md`.

## Hipótesis de rendimiento priorizadas

| ID | Tipo | Evidencia | Hipótesis | Qué medir (entorno local, nunca MAIN sin GO) |
|---|---|---|---|---|
| PERF-H1 | CONFIRMED_IN_CODE | `20261022101700_v1_ranking.sql:176-183`; `v1_stats.sql:407-408` | El job de ranking reescribe todas las filas de `estado_competitivo_usuario` en una transacción de hasta 30 s sin `IS DISTINCT FROM`; cada primera respuesta hace `FOR UPDATE` de la misma fila → picos cada 15 min | `EXPLAIN (ANALYZE, BUFFERS)` del UPDATE del job; `pg_locks`/`wait_event` durante el job con carga de respuestas |
| PERF-H2 | CONFIRMED_IN_CODE | AttemptRoutes + `app_record_attempt_answer` | 4-5 llamadas upstream en serie por respuesta (Valkey, perfil, idem_begin, RPC, idem_complete) | p50/p95/p99 de `PUT .../respuestas/{p}` con k6 `write_heavy`; `auto_explain.log_nested_statements` |
| PERF-H3 | CONFIRMED_IN_CODE | `20261022101200_v1_daily_challenge.sql:196-245, 313` | Lock asesor global `reto-job` en la primera apertura diaria de cada usuario + `order by random()` sobre el pool completo → cola a medianoche | `EXPLAIN ANALYZE` de `private.reto_pool(...) order by random() limit 1`; prueba de ráfaga de N usuarios a las 00:00 |
| PERF-H4 | CONFIRMED_IN_CODE | `20261022101900_v1_foro.sql:449-453` | `ORDER BY CASE ...` y cursor dentro de CASE impiden usar índices → top-N sort por página | `EXPLAIN ANALYZE select public.app_foro_list($u,null,'recientes',null,20)` con 10⁴-10⁵ posts sintéticos |
| PERF-H5 | CONFIRMED_IN_CODE | `v1_foro.sql:844-860` | `foro_reconcile()` recuenta toda la tabla cada 900 s en cada réplica bot | `EXPLAIN ANALYZE select count(*) from public.foro_reconcile()` |
| PERF-H6 | CONFIRMED + HYPOTHESIS | `AdminDashboardOverview.kt:678-889`; `AdminV1Routes.kt:7603-7605` | ~13 `count=exact` por carga + 2 sobre `respuestas_intento`; faltarían índices `intentos_examen(iniciado_en)`, `eventos_app(occurred_en)` | `EXPLAIN ANALYZE select public.admin_dashboard_active_users(30)`; verificar `pg_indexes` (lectura) |
| PERF-H7 | HYPOTHESIS | `CatalogGateway.kt:267-273` | Keyset del catálogo sin índice `(publicado_en desc, id desc) WHERE activo AND publicado_en IS NOT NULL` | Consulta PostgREST equivalente con y sin índice parcial |
| PERF-H8 | CONFIRMED_IN_CODE | `Foro.kt:261`; `ForoImages.kt:43-44, 199, 244` | Sweep de imágenes inline en DELETE (un objeto por llamada); decodificación de hasta 12 MP (~48 MB) por subida en el pool IO compartido sin límite global, contenedor 768 MB | Subidas concurrentes en local midiendo heap/OOM |
| PERF-H9 | CONFIRMED_IN_CODE | `AdminV1Routes.kt:5354-5390, 7468, 7172, 7334, 7228` | Lecturas admin sin límite y escaneos N-pass (lotes de importación, moderación, carteras, concesiones, conversaciones) | Tamaño de respuesta y latencia con datos sintéticos de volumen V1 |
| PERF-H10 | HYPOTHESIS | `v1_ranking.sql:156-159` | Filtro incremental `ec.actualizado_en > $hasta` sin índice → seq scan + join por ejecución | `EXPLAIN ANALYZE` del SELECT incremental |
| PERF-H11 | CONFIRMED_IN_CODE | `V1Routes.kt`, PublicReadCache | Caché pública sin single-flight; variar parámetros vacía la caché al llegar a 512 → estampida y vector de abuso barato | k6 con claves de filtro aleatorias contra `/v1/examenes` |
| PERF-H12 | CONFIRMED_IN_CODE | `CanonicalIdentity.kt:114` | Lectura de perfil sin caché por petición: mayor fuente individual de llamadas upstream (coincide con la medición local de 2026-10-08) | Contar peticiones PostgREST por request en k6 `mixed` |

Prioridad: en rutas económicas no se optimiza a costa de transaccionalidad; H1/H3 primero (contención real), después H2/H12 (latencia de respuesta), H4-H6 cuando haya volumen.

## Mediciones existentes (no de este ciclo)

`docs/engineering/PERFORMANCE_RESULTS_LOCAL.md` (2026-10-08, estación local, no capacidad): read_heavy 100 VU p95 83 ms; 500 VU p95 2,8 s; mixed 100 VU p95 259 ms; 500 VU p95 6,4 s; PostgREST consumió 3+ núcleos. Producción idle p95 3,6 ms (2026-09-23). **Ninguna ruta V1 nueva (foro, retos, stats, ranking, economía) ha sido medida bajo carga.**

Herramientas: `ops/loadtest` (k6 con guarda de host de producción; escenarios read_heavy, mixed, write_heavy, auth), `ops/local-exam/perf-ab.mjs` (A/B de dos builds).
