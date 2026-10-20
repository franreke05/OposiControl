package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** Lo que las rutas de tienda necesitan. Sin plataformas activadas no se construye y las rutas fallan cerradas (500). */
internal class StorePaymentsRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: StorePaymentsService,
    val idempotency: IdempotencyStore,
    val limit: suspend (EconomyOperation, CanonicalUserId) -> Unit = { _, _ -> },
    val webhookLimit: suspend (ApplicationCall) -> Unit = {},
    /** Sondeo de Voided Purchases (solo con Google activado). */
    val google: GooglePlayProvider? = null,
    /** Get Notification History (solo con Apple activado y la App Store Server API configurada). */
    val apple: AppStoreProvider? = null,
    /** NEW-4: retencion de la bandeja (dias); null = no se purga nada. */
    val retentionDays: Int? = null,
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
            log: (String) -> Unit = {},
        ): StorePaymentsRuntime? {
            val settings = StorePaymentsSettings.fromEnvironment(environment)
            if (settings.google == null && settings.apple == null) return null
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val store = PostgrestIdempotencyStore.fromEnvironment(environment) ?: return null
            val google = settings.google?.let { g ->
                val account = GoogleServiceAccount.parse(File(g.serviceAccountFile).readText())
                GooglePlayProvider(g, HttpGooglePlayApi(g.packageName, account), GoogleOidcVerifier(g.pubsubAudience, g.pubsubServiceAccount, JwksGoogleKeySource()))
            }
            val apple = settings.apple?.let { a ->
                AppStoreProvider(
                    a,
                    a.rootCaPaths.map { readCertificateDer(it) ?: throw StoreConfigException("PAYMENTS_APPLE_ROOT_CA_PATHS: un certificado no se puede leer") },
                    api = a.api?.let { LibraryAppStoreServerApi.fromConfig(it, a.bundleId) },
                )
            }
            val providers = listOfNotNull<StorePaymentProvider>(google, apple).associateBy { it.platform }
            val limiter = auth?.rateLimiter
            return StorePaymentsRuntime(
                resolver = resolver,
                service = StorePaymentsService(upstream, providers, settings.catalog, settings.sandbox, settings.decisions, log = log),
                idempotency = store,
                limit = { operation, userId ->
                    if (limiter != null) {
                        val decision = limiter.checkSubject(operation.limit, userId.value)
                        if (!decision.allowed) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
                    }
                },
                webhookLimit = { call ->
                    if (limiter != null && auth != null) {
                        val decision = limiter.checkIp(OposiBotsAuthOperation.PAYMENT_WEBHOOK, call.trustedClientIp(auth.configuration))
                        if (!decision.allowed) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
                    }
                },
                google = google,
                apple = apple,
                retentionDays = settings.retentionDays,
            )
        }
    }
}

/** Tope del cuerpo de un aviso de tienda: un ASSN V2 lleva tres JWS anidados con su cadena x5c. Se comprueba ANTES de verificar nada. */
internal const val STORE_WEBHOOK_BODY_MAX_BYTES: Long = 256L * 1024

/**
 * Pagos de tienda (docs/payments/STORE_PAYMENTS_CONTRACT.md):
 *
 *  - S-01 `GET  /api/v1/app/store/account-token`        token opaco de la cuenta (obfuscatedAccountId / appAccountToken)
 *  - S-02 `GET  /api/v1/app/store/products`             productos de tienda en venta (de config)
 *  - S-03 `POST /api/v1/app/store/purchases/verify`     verificar una compra con la tienda (`Idempotency-Key` obligatoria)
 *  - S-04 `POST /api/v1/app/store/purchases/restore`    restaurar: varias compras, cada una como S-03
 *  - S-05 `GET  /api/v1/app/store/entitlements`         saldo y premium del usuario
 *  - W-02 `POST /api/v1/webhooks/store/google-play`     RTDN por Pub/Sub push (OIDC)
 *  - W-03 `POST /api/v1/webhooks/store/app-store`       App Store Server Notifications V2 (JWS)
 */
internal fun Route.storePaymentRoutes(runtime: StorePaymentsRuntime?) {
    val store: IdempotencyStore = runtime?.idempotency ?: StoreMisconfiguredStore
    route("/api/v1/app/store") {
        get("/account-token") {
            val rt = runtime.orStoreMisconfigured()
            val identity = call.storeGate(runtime, EconomyOperation.ORDER_READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(rt.service.accountToken(identity.userId))
        }
        get("/products") {
            val rt = runtime.orStoreMisconfigured()
            call.storeGate(runtime, EconomyOperation.ORDER_READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(rt.service.products())
        }
        get("/entitlements") {
            val rt = runtime.orStoreMisconfigured()
            val identity = call.storeGate(runtime, EconomyOperation.ORDER_READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(rt.service.entitlements(identity.userId))
        }
        post("/purchases/verify") {
            val rt = runtime.orStoreMisconfigured()
            val identity = call.storeIdentity()
            val request = call.receiveCanonicalCommand<StoreVerifyRequest>(nullableFields = setOf("transactionId", "accountToken"))
            call.respond(rt.service.verify(identity.userId, request))
        }.install(CanonicalIdempotency) {
            routeTemplate = "/api/v1/app/store/purchases/verify"
            required = true
            this.store = store
            before = { call -> call.storeGate(runtime, EconomyOperation.ORDER_WRITE) }
            actor = { call -> call.storeIdentity().userId.value }
        }
        post("/purchases/restore") {
            val rt = runtime.orStoreMisconfigured()
            val identity = call.storeIdentity()
            val request = call.receiveCanonicalCommand<StoreRestoreRequest>()
            call.respond(rt.service.restore(identity.userId, request))
        }.install(CanonicalIdempotency) {
            routeTemplate = "/api/v1/app/store/purchases/restore"
            required = false
            this.store = store
            before = { call -> call.storeGate(runtime, EconomyOperation.ORDER_WRITE) }
            actor = { call -> call.storeIdentity().userId.value }
        }
    }
    // Avisos: SIN JWT. Orden fijo: limite por IP, tope de cuerpo, autenticacion (OIDC / JWS), bandeja, proceso.
    post("/api/v1/webhooks/store/{platform}") {
        val rt = runtime.orStoreMisconfigured()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        rt.webhookLimit(call)
        val platform = when (call.parameters["platform"]) {
            "google-play" -> StorePlatform.GOOGLE_PLAY
            "app-store" -> StorePlatform.APP_STORE
            else -> throw CanonicalErrorCode.NOT_FOUND.exception()
        }
        val raw = call.canonicalRawBody(STORE_WEBHOOK_BODY_MAX_BYTES)
        call.respond(WebhookAckDto(rt.service.webhook(platform, { name -> call.request.headers[name] }, raw)))
    }
}

private val StoreIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-store-identity")

private suspend fun ApplicationCall.storeGate(runtime: StorePaymentsRuntime?, operation: EconomyOperation): CanonicalUserIdentity {
    attributes.getOrNull(StoreIdentityKey)?.let { return it }
    val rt = runtime.orStoreMisconfigured()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(StoreIdentityKey, identity)
    return identity
}

private fun ApplicationCall.storeIdentity(): CanonicalUserIdentity =
    attributes.getOrNull(StoreIdentityKey) ?: throw CanonicalErrorCode.INTERNAL.exception()

private fun StorePaymentsRuntime?.orStoreMisconfigured(): StorePaymentsRuntime = this ?: throw CanonicalErrorCode.INTERNAL.exception()

private object StoreMisconfiguredStore : IdempotencyStore {
    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult = throw CanonicalErrorCode.INTERNAL.exception()

    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) = Unit

    override suspend fun release(claim: IdempotencyClaim) = Unit
}

/**
 * Reconciliacion de tienda (`PAYMENTS_STORE_JOB_SECONDS`, 0 = apagado): reintenta la bandeja pendiente, consume / reconoce en Play lo
 * acreditado y no terminado, relee de la tienda las suscripciones que llegaron a su fin, sondea Voided Purchases una vez al dia y Get Notification
 * History cada 6 horas (Apple, solo con la API). Los cursores y la hora del ultimo sondeo viven en la base (tienda_sondeo): sobreviven a un
 * reinicio y los comparten las replicas; cada ventana se solapa con la anterior y la bandeja deduplica por id de evento.
 */
internal object StorePaymentsJob {
    const val VOIDED_SOURCE = "voided_purchases"
    const val HISTORY_SOURCE = "notification_history"
    private const val DAY_MS = 86_400_000L
    fun start(scope: CoroutineScope, runtime: StorePaymentsRuntime, intervalSeconds: Long, log: (String) -> Unit = {}, clock: () -> Instant = Instant::now): Job? {
        if (intervalSeconds <= 0) return null
        return scope.launch(Dispatchers.IO) {
            delay(10_000)
            while (isActive) {
                runCatching { runtime.service.reprocessPending() }.onFailure { log("store job reprocess failed: ${it::class.simpleName}") }
                runCatching { runtime.service.finishPending() }.onFailure { log("store job finish failed: ${it::class.simpleName}") }
                runCatching { runtime.service.maintenance(runtime.retentionDays) }.onFailure { log("store job maintenance failed: ${it::class.simpleName}") }
                runCatching { StoreInboxMetrics.update(runtime.service.inboxStats(), log) }.onFailure { log("store job inbox stats failed: ${it::class.simpleName}") }
                runCatching { runtime.service.syncSubscriptions() }.onFailure { log("store job subscription sync failed: ${it::class.simpleName}") }
                runCatching { pollVoided(runtime, clock()) }.onFailure { log("store job voided poll failed: ${it::class.simpleName}") }
                runCatching { pollAppleHistory(runtime, clock(), log) }.onFailure { log("store job apple history poll failed: ${it::class.simpleName}") }
                delay(intervalSeconds * 1_000)
            }
        }
    }

    /**
     * Voided Purchases (Google, `type=0`): una vez al dia segun la base. Ventana: desde el cursor menos un dia de solape (nunca mas de 30 dias,
     * el limite de Google); primera vez, 3 dias. El cursor avanza SOLO si todo lo leido quedo en la bandeja.
     */
    suspend fun pollVoided(runtime: StorePaymentsRuntime, now: Instant): Int {
        val google = runtime.google ?: return 0
        val cursor = runtime.service.pollCursor(StorePlatform.GOOGLE_PLAY, VOIDED_SOURCE)
        if (cursor != null && cursor.second.isAfter(now.minusSeconds(86_400))) return 0
        val nowMs = now.toEpochMilli()
        val start = maxOf(nowMs - 30 * DAY_MS + 3_600_000L, (cursor?.first ?: (nowMs - 3 * DAY_MS)) - DAY_MS)
        val events = google.voidedSince(start)
        events.forEach { (event, fingerprint) -> runtime.service.ingest(event, fingerprint) }
        runtime.service.advancePollCursor(StorePlatform.GOOGLE_PLAY, VOIDED_SOURCE, nowMs)
        return events.size
    }

    /**
     * Get Notification History (Apple, produccion): recupera avisos que no llegaron (Apple reintenta 5 veces y luego los deja). Cada 6 horas segun la
     * base; ventana desde el cursor menos una hora de solape (nunca mas de 180 dias); primera vez, 3 dias. Sin la App Store Server API, nada.
     */
    suspend fun pollAppleHistory(runtime: StorePaymentsRuntime, now: Instant, log: (String) -> Unit = {}): Int {
        val apple = runtime.apple?.takeIf { it.hasServerApi } ?: return 0
        val cursor = runtime.service.pollCursor(StorePlatform.APP_STORE, HISTORY_SOURCE)
        if (cursor != null && cursor.second.isAfter(now.minusSeconds(6 * 3_600L))) return 0
        val nowMs = now.toEpochMilli()
        val start = maxOf(nowMs - 180 * DAY_MS + DAY_MS, (cursor?.first ?: (nowMs - 3 * DAY_MS)) - 3_600_000L)
        val events = apple.notificationHistory(StoreEnvironment.PRODUCTION, start, nowMs, log)
        events.forEach { (event, fingerprint) -> runtime.service.ingest(event, fingerprint) }
        runtime.service.advancePollCursor(StorePlatform.APP_STORE, HISTORY_SOURCE, nowMs)
        return events.size
    }
}

/**
 * F4 (auditoria Control 2026-10-19): metrica y alerta del atasco de la bandeja de tienda. `store_inbox_backlog{estado}` (filas) y
 * `store_inbox_oldest_seconds{estado}` (edad de la mas antigua) para `pendiente` y `retenido`; un log WARN cuando hay retenidos (reembolsos
 * apagados con avisos guardados) o un pendiente de mas de una hora. Sin ids ni tokens.
 */
internal object StoreInboxMetrics {
    const val BACKLOG_METRIC = "store_inbox_backlog"
    const val OLDEST_METRIC = "store_inbox_oldest_seconds"
    /** N3-6: 1 en cuanto el runtime de tienda existe (alguna tienda activada). La regla `StoreInboxMetricsAbsent` la cruza con la bandeja. */
    const val ENABLED_METRIC = "store_payments_enabled"
    private val enabled = java.util.concurrent.atomic.AtomicLong(0)

    fun markEnabled() {
        if (enabled.getAndSet(1) == 0L) {
            io.micrometer.core.instrument.Gauge.builder(ENABLED_METRIC) { enabled.get().toDouble() }.register(BackendPrometheusMetrics.registry)
        }
    }
    private val rows = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val oldest = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    private fun gauge(map: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>, name: String, estado: String) =
        map.computeIfAbsent(estado) { key ->
            java.util.concurrent.atomic.AtomicLong(0).also { holder ->
                io.micrometer.core.instrument.Gauge.builder(name) { holder.get().toDouble() }.tag("estado", key).register(BackendPrometheusMetrics.registry)
            }
        }

    /** NEW-4: tambien los muertos (dead letter) y los huerfanos (reembolso / reversion sin compra todavia). */
    val STATES = listOf("pendiente", "retenido", "muerto", "huerfano_reembolso", "huerfano_reversion")

    fun update(stats: List<StoreInboxBacklog>, log: (String) -> Unit) {
        STATES.forEach { estado ->
            val s = stats.firstOrNull { it.estado == estado }
            gauge(rows, BACKLOG_METRIC, estado).set(s?.rows ?: 0)
            gauge(oldest, OLDEST_METRIC, estado).set(s?.oldestSeconds ?: 0)
        }
        stats.firstOrNull { it.estado == "retenido" && it.rows > 0 }?.let { log("ALERT store inbox: ${it.rows} refund notifications retained while PAYMENTS_REFUNDS_ENABLED is off (oldest ${it.oldestSeconds}s)") }
        stats.firstOrNull { it.estado == "pendiente" && it.oldestSeconds > 3_600 }?.let { log("ALERT store inbox: ${it.rows} pending notifications, oldest ${it.oldestSeconds}s, max attempts ${it.maxAttempts}") }
        stats.firstOrNull { it.estado == "muerto" && it.rows > 0 }?.let { log("ALERT store inbox: ${it.rows} dead-letter notifications need a manual decision (app_store_inbox_requeue)") }
        stats.filter { it.estado.startsWith("huerfano") && it.oldestSeconds > 86_400 }.forEach { log("ALERT store inbox: ${it.rows} ${it.estado} older than a day (oldest ${it.oldestSeconds}s)") }
    }

    fun value(name: String, estado: String): Long? = (if (name == BACKLOG_METRIC) rows else oldest)[estado]?.get()
}
