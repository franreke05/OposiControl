# Performance audit (static) - bot profile hot paths

Branch `feature/canonical-api-slice-1` @ `f3c53d3`. Method: read-only code and DDL review. **Nothing here was measured**: no load was run, no capacity number is claimed. Classes: BLOCKING (will stop the test objective), LIKELY_BOTTLENECK, MINOR, FINE. "Expected effect" is derived arithmetic from the code, not a measurement. Paths are under `backend/ktor/src/main/kotlin/com/oposicontrol/backend/` unless stated.

## 1. Engine, server and upstream client

| Fact | Evidence |
|---|---|
| Engine = Netty via `embeddedServer(Netty, host, port, module)`; no `connectionGroupSize`/`workerGroupSize`/`callGroupSize`/`requestReadTimeout` configured, so Ktor defaults apply (sized from `availableProcessors`; in a `cpus: 1.0` container the JVM sees ~1 CPU) | Application.kt:153-158 |
| Graceful shutdown: grace 5 s, hard timeout 25 s, own shutdown hook; compose `stop_grace_period: 35s` | Application.kt:146-166; ops/deploy/compose.production.yaml |
| ONE shared JDK `java.net.http.HttpClient` for all PostgREST/RPC/Storage/JWKS calls (`by lazy`), connect timeout 5 s, per-request timeout 20 s (`UPSTREAM_*_TIMEOUT_MS`). No explicit executor, no explicit `version(...)` (JDK default prefers HTTP/2 with ALPN) | Observability.kt:79-89, 145-148, 170; SupabaseRequestTransport.kt:431 |
| Auth path uses a different client: Ktor `HttpClient(Java)` singleton per auth runtime, suspending, timeouts 5/15/15 s | OposiBotsAuthGateway.kt:939-945 |
| The per-gateway `newUpstreamHttpClient()` defaults (11 clients) exist only in CONTROL gateways (built only when `serverProfile.mountsControl`) | AdminGateway.kt:123, AdminAnalytics.kt:559, AdminRankings.kt:300, Application.kt:530 |
| No retry loop in `SupabaseRequestTransport` (grep: none). Bounded retries exist only for the idempotent profile-gate READ in refresh. Caddy `lb_retries 2` retries on connect failure | SupabaseRequestTransport.kt:443-455; OposiBotsAuthGateway.kt:593-598; ops/caddy/Caddyfile.production:105 |
| Blocking: `client.send(...)` is synchronous. It is made safe-ish by hopping to `Dispatchers.IO` through `withUpstreamIo`. Concurrency cap = Dispatchers.IO default (max(64, cores) = **64 threads per replica**), no semaphore, no bulkhead, unbounded dispatcher queue | SupabaseRequestTransport.kt:448; Observability.kt:507-510 |
| Cancellation does not interrupt a thread already inside `client.send` (the code says so itself) | Observability.kt:525-540 |
| Response compression: none in Ktor; Caddy `encode zstd gzip` | Caddyfile.production:58 |
| Request body cap 2 MB | RequestBodyLimit.kt:28 |
| Control-only heavy work (multipart, ffmpeg via MediaCompression, PDF import) is mounted only when `profile.mountsControl`; `routes.bot.txt` contains no admin/content/import route. CONFIRMED not reachable in `SERVER_PROFILE=bot` (protected flow, untouched) | ServerProfile.kt:27-30; Application.kt:1386 (control route); ops/inventory/routes.bot.txt |

## 2. Upstream calls per consumer request (counted from code)

Every authenticated `/api/v1/app/*` request first does: JWT verify (local ES256 with cached JWKS, on IO pool) -> **Valkey EVAL** (per-user limit, before profile read) -> **1 PostgREST read of `perfiles_usuario`** (CanonicalIdentity.kt:105-107, never cached) -> handler. All calls inside a handler are **sequential on one blocked IO thread**.

| Endpoint | Upstream HTTP calls (excl. identity) | Where |
|---|---|---|
| GET examenes (list) | listPublished + premiumRow + grantsFor + capabilitiesFor + signCovers (+ facetRows <=2001 rows if `includeFacets=true`) = 5-6 | CatalogService.kt:223-245, 375-396 |
| GET examenes/{id} | getPublished + openAttemptId + 3 (accessFor) = 5 | CatalogService.kt:263-286 |
| GET me/examenes (ADQUIRIDOS/COLECCION) | grants + 3 + signCovers + latestAttempts + groups(<=500 rows, first page) = 7 | CatalogService.kt:291-322 |
| GET me/entitlement | premiumRow + planOf + activeCredits = 3 | CatalogService.kt:431-461 |
| GET intentos/{id} (state) | attempt + frozenSet(<=201) + answers(<=201) + annulled = 4 | AttemptService.kt:293-300 |
| GET intentos/{id}/preguntas | attempt + questionPage + answers = 3 | AttemptService.kt:314-319 |
| GET resultado / revision | attempt + resultQuestions + answers + tagSummary = 4 / 3 | AttemptService.kt:414-454 |
| POST examenes/{id}/intentos | startAccess (4) + idem_begin + rpc start + attempt + idem_complete = 8 | AttemptService.kt:262-289; CanonicalIdempotency.kt:289-310 |
| PUT respuestas | idem_begin + rpc answer + idem_complete = 3 | AttemptService.kt:348-355 |

Thread-seconds per request = sum of the serial latencies. Illustration only (assumes an upstream call of ~40 ms, which is NOT measured): list = ~7 calls ~ 280 ms of one IO thread -> ceiling ~ 64/0.28 ~ 230 req/s per replica before Supabase latency even degrades; an answer ~ 4 calls ~ 160 ms -> ~ 400/s. These are order-of-magnitude arithmetic to rank hazards, not capacity claims.

## 3. Ranked hazards

| # | Hazard | Class | Expected effect under load | Minimal fix |
|---|---|---|---|---|
| 1 | Unbounded admission: 64 blocking IO threads per replica is the only limit, queue unbounded, no load shedding (Observability.kt:507-510; transport blocking at SupabaseRequestTransport.kt:448) | LIKELY_BOTTLENECK (becomes BLOCKING the moment Supabase p95 rises) | Throughput ceiling = 64 / (sum of serial upstream latency). When Supabase slows, requests pile up in the dispatcher queue, latency grows to the 20 s upstream timeout, Caddy times out at 25 s while the replica keeps working for clients that left (cancel does not interrupt), heap grows on a 768m/60% = ~460 MB heap. Valkey limiter and JWT verify share the same 64 threads (OposiBotsAuthRateLimiter.kt:171) so a slow Supabase also stalls rate-limit checks | A semaphore/admission gate in front of `withUpstreamIo` for consumer routes (e.g. N = 48) that answers 503 + `Retry-After` immediately when full; give limiter/JWT a small separate dispatcher. Tune N from the saturation test |
| 2 | Identity profile read on EVERY authenticated request, uncached (CanonicalIdentity.kt:105-107) | LIKELY_BOTTLENECK | +1 serial upstream call and +1 Valkey call per request; for light endpoints (entitlement: 3 calls) it is 25-33% of all upstream traffic | Short positive-only cache of `ACTIVA` identity keyed by userId, TTL <= 10 s, bounded size, no negative caching, local invalidate on block/deletion commands. **Security trade-off**: a block or deletion-pending state can take up to TTL to be seen by another replica - owner decision |
| 3 | Independent reads executed serially in `accessFor` (premium, grants, capabilities) and list/detail/my-exams (CatalogService.kt:375-379, 223-245, 291-322) | LIKELY_BOTTLENECK | Latency = sum instead of max; thread held ~2-3x longer than needed | `withUpstreamIoParallel` (already exists, Observability.kt:541) for the 3 reads + signCovers + facets. Reduces latency, NOT thread-seconds; combine with 4 |
| 4 | Global/slow-changing data re-fetched per request: `capabilities_especialidad`, facets (<=2001 rows, CatalogService.kt:250), the first public page, `planOf` | LIKELY_BOTTLENECK | Facets pulls up to 2001 rows each time `includeFacets=true`; capabilities per page | In-process TTL cache (30-60 s) keyed by specialty / filter; stale-while-error. Safe: published catalog is not per-user |
| 5 | Cover signing = 1 Storage call per list / my-exams page (CatalogService.kt:391-400; TTL 3600 s of the signed URL) | MINOR -> LIKELY under list-heavy traffic | +1 upstream call per page, signed URL recomputed every time | Cache `path -> signedUrl` for ~50 min (< URL TTL) |
| 6 | Write path overhead: idempotency = 2 extra RPCs per command (idem_begin/idem_complete) + Valkey + identity; `app_record_attempt_answer` takes `FOR UPDATE` on the attempt and re-aggregates `respuestas_intento` (O(answers in attempt), <=200) per answer (migration 20261008100100 lines ~304-306, 386, 473) | LIKELY_BOTTLENECK for write_heavy | ~4 serial calls (~4 round trips) per answer; same-attempt answers serialize on the row lock (intended) | Fold idem_begin/complete into the answer RPC (DB change, other owner); or accept and measure. Not in this task's write scope |
| 7 | JDK HttpClient: default HTTP/2 uses ONE connection per origin; behaviour at MAX_CONCURRENT_STREAMS and head-of-line blocking is not verified here; no explicit executor | MINOR (NOT VERIFIED) | Possible stream queueing with 64+ in-flight per replica x 2 replicas | Measure `jvm_threads_live_threads`/upstream latency; if suspect, compare with `version(HTTP_1_1)` (pooled connections) behind a flag |
| 8 | Netty not tuned (Application.kt:153); `requestReadTimeout`/idle not set, call group tiny at 1 CPU | MINOR | Handlers hop to IO, so call threads only route/serialize; slow-client protection relies on Caddy (not verified) | Leave until a profile shows event-loop saturation |
| 9 | Valkey limiter: one shared Lettuce connection, blocking `sync().eval` on Dispatchers.IO per check, script sent as EVAL (not EVALSHA); fail-closed 503 if Valkey down; 2 s command timeout, 1 s connect | MINOR | One IO thread blocked per check for 1 RTT; Valkey `cpus: 0.25`, `maxmemory 64mb` with volatile-ttl | Fine for now; watch Valkey CPU at 0.25 CPU and `evicted_keys` at 64 MB |
| 10 | Logging on hot path: only SLOW_REQUEST (>=1000 ms) at INFO and strategic events (Observability.kt:68-69, 275, 370); no payload dumps. Under saturation every slow request logs one line | MINOR | Log amplification only when already overloaded; json-file 20m x 5 | Raise `SLOW_REQUEST_THRESHOLD_MS` during load tests |
| 11 | Micrometer histogram: `percentilesHistogram(true)` (PrometheusMetrics.kt:48) over route-template labels | MINOR | Bounded cardinality (templates, not raw paths); adds per-series bucket memory/CPU | Leave; do not add user/exam id labels |
| 12 | Payload reads: all bot reads are limited (page limit+1, caps 200/500/2000; no `count=exact` in CatalogGateway/AttemptGateway). Largest: state/result read up to 201 frozen rows + 201 answers (AttemptGateway.kt:330) | MINOR | ~tens of KB per state call | Poll less / use questions page |
| 13 | Caddy single instance `cpus: 1.0`, 192 MB, does TLS + gzip/zstd for ALL traffic (compose.production.yaml) | NOT VERIFIED -> likely first CPU limit at high VU | TLS + compression CPU on 1 core | Watch Caddy CPU in the test; zstd level / only compress >1 KB |

Top 5 by expected impact: 1, 2, 3+4 (one change set), 6, 5.

## 4. Already well done

- ONE shared upstream HttpClient with connect + per-request timeouts; no thread can hang forever (Observability.kt:79-89,145-170).
- No retries on non-idempotent upstream writes; writes protected by `Idempotency-Key` + fenced idem RPCs; refresh gate retries are reads only and deadline-bounded.
- Keyset pagination with `limit+1`, hard caps everywhere, no `count=exact` and no `limit=1000/10000` on consumer paths; Storage signing batched into ONE request per page; `accessFor` reads user facts once per request (not per row) - no per-row N+1.
- JWKS cached, stale-while-error with refresh backoff and anti-stampede lock; verification is local CPU, not a network call (AppV1Routes.kt:232+).
- Rate limiter checks per-user BEFORE the profile read (cheap rejection), atomic Lua INCR+PEXPIRE, fail-closed; Caddy health ejection deliberately not status-based, avoiding cascading ejection.
- Control-heavy work isolated by `SERVER_PROFILE`; bot container has `pids_limit`, mem/cpu limits, `ExitOnOutOfMemoryError`, graceful drain.
- No payload dumps in logs; request-id correlation; metrics by route template.

## 5. DB side (static): access paths vs indexes

Sources: `C:/Users/franr/.oposibots-gate/baseline/baseline_main.sql` + migrations 20261008100000/100/200 + 20260928100100. Tables here are small-to-medium; "no supporting index" means a scan + sort that grows with the table, not that it is slow today. No index was created.

| Query (gateway) | Predicate / order | Supporting index | Verdict |
|---|---|---|---|
| `examenes` list/detail/facets (CatalogGateway 211-236) | `activo = true AND publicado_en IS NOT NULL AND publicado_en <= now [AND tipo/especialidad/comunidad/anio]` ORDER BY `publicado_en DESC, id DESC` LIMIT n+1 | Only single-column `idx_examenes_activo/tipo/especialidad/comunidad_id`, `idx_examenes_tipo_activo`; **nothing on `publicado_en`** | GAP: filter + top-N sort over all active exams per call. Propose `CREATE INDEX ... ON examenes (publicado_en DESC, id DESC) WHERE activo AND publicado_en IS NOT NULL` serving the unfiltered keyset page (filtered variants still use their single-column indexes + sort) |
| `intentos_examen` open attempt id (CatalogGateway 239) | `usuario_id, examen_id, estado='en_progreso'` ORDER BY iniciado_en DESC | `ux_intentos_examen_abierto (usuario_id, examen_id) WHERE estado='en_progreso'` | OK (<=1 row) |
| `intentos_examen` my open attempts (248-253) | `usuario_id, estado='en_progreso'` ORDER BY `actualizado_en DESC, id DESC` + inner join examenes | `idx_intentos_usuario` / `idx_intentos_examen_usuario_examen` then sort | Acceptable per user; if a user accumulates history, propose `(usuario_id, actualizado_en DESC, id DESC) WHERE estado='en_progreso'` |
| `intentos_examen` latest attempts (286-288) | `usuario_id, examen_id IN (...)` ORDER BY actualizado_en DESC LIMIT 500 | `idx_intentos_examen_usuario_examen` | OK |
| attempt by id + owner (AttemptGateway 226) | `id = ? AND usuario_id = ?` | PK | OK |
| `preguntas_intento` (236-271) | `intento_id [AND posicion > ?]` ORDER BY posicion | `UNIQUE (intento_id, posicion)` | OK |
| `respuestas_intento` (288) | `intento_id [AND pregunta_id IN]` ORDER BY pregunta_id | `UNIQUE (intento_id, pregunta_id)` | OK |
| `preguntas_examen` annulled (298) | `examen_id AND anulada` ORDER BY pregunta_id | PK `(examen_id, pregunta_id)` (+ residual filter) | OK |
| `resumen_intento_etiqueta` (312) | `intento_id` ORDER BY etiqueta_id | PK `(intento_id, etiqueta_id)` | OK |
| `contenido_desbloqueado_usuario` grants (259-267, 299) | `usuario_id [AND examen_id IN]`, `valido_desde <= now`, `valido_hasta` null or > now, ORDER BY `valido_desde DESC, id DESC` + join examenes | `idx_contenido_desbloqueado_usuario_usuario_id`, `..._examen_id` | Acceptable per user. Propose `(usuario_id, valido_desde DESC, id DESC)` if grants per user grow large |
| `creditos_contenido_usuario` (338) | `usuario_id, valido_desde<=now, valido_hasta>now` ORDER BY valido_hasta | `idx_creditos_contenido_usuario_usuario_id` | OK (small per user) |
| `estado_premium_usuario`, `perfiles_usuario`, `capacidades_especialidad`, `productos_comerciales` | PK / unique lookups | PKs, `productos_comerciales_codigo_key` | OK |
| `idempotency_keys` | unique `(actor_id, scope, idem_key)` + `expires_at` | both defined | OK |
| RPCs 20261008100100: start (`ON CONFLICT (usuario_id, examen_id) WHERE estado='en_progreso'`), answer, finish, abandon | by PK / `intento_id` unique / `(usuario_id, pregunta_id)` | `ux_intentos_examen_abierto`, `idx_respuestas_intento_usuario_pregunta`, PKs | OK. `etiquetas_pregunta` lookup by `pregunta_id` served by its PK prefix |

Redundant indexes (write amplification on hot tables, safe-to-drop candidates, each is a left prefix or duplicate of a PK/UNIQUE; verify with `pg_stat_user_indexes` before dropping):
`idx_intentos_usuario` (prefix of `idx_intentos_examen_usuario_examen`), `idx_respuestas_intento_intento` (prefix of `UNIQUE (intento_id, pregunta_id)`), `idx_estado_premium_usuario_usuario` (= PK), `idx_perfiles_usuario_username` (= UNIQUE username), `idx_opciones_pregunta_pregunta` (prefix of UNIQUE `(pregunta_id, letra)`), `idx_soluciones_pregunta_pregunta` (= UNIQUE `pregunta_id`), `idx_preguntas_examen_examen` and `idx_preguntas_examen_examen_posicion` (prefix/dup of PK and UNIQUE `(examen_id, posicion)`).
`idx_respuestas_intento` has 8 secondary indexes on the highest-volume write table (answers); every answer insert pays for all of them - candidates to review: `idx_respuestas_intento_examen`, `..._pregunta`, `..._ranking`, `..._opcion_*` against real usage.

## 6. Not verified

Supabase plan limits (PostgREST pool, connection and rate caps), real upstream latency, JDK HTTP/2 stream behaviour, Ktor default group sizes in the pinned Ktor version, whether `/health/ready` probes Supabase per call, Caddy retry matcher semantics for POST, exact `myActiveGrants` plan. Everything above is code reading until the test plan is executed.
