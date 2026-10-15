package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey

/** Operaciones economicas con limite por usuario. */
internal enum class EconomyOperation(val limit: OposiBotsAuthOperation) {
    REDEEM(OposiBotsAuthOperation.WALLET_WRITE),
    PACKS(OposiBotsAuthOperation.ORDER_READ),
    ORDER_READ(OposiBotsAuthOperation.ORDER_READ),
    ORDER_WRITE(OposiBotsAuthOperation.ORDER_WRITE),
}

/** Lo que las rutas economicas necesitan. Se construye en el arranque cuando hay resolver de identidad. */
internal class EconomyRuntime(
    val resolver: CanonicalIdentityResolver,
    val economy: EconomyService,
    val idempotency: IdempotencyStore,
    /** Limite por usuario (JWT sub ya verificado) ANTES de leer el perfil. Lanza 429. Por defecto no limita (pruebas). */
    val limit: suspend (EconomyOperation, CanonicalUserId) -> Unit = { _, _ -> },
    /** Limite por IP de los avisos del proveedor (no llevan usuario). Lanza 429. Por defecto no limita (pruebas). */
    val webhookLimit: suspend (ApplicationCall) -> Unit = {},
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): EconomyRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val store = PostgrestIdempotencyStore.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return EconomyRuntime(
                resolver = resolver,
                economy = EconomyService(upstream, PaymentProviders.fromEnvironment(environment)),
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
            )
        }
    }
}

/** Tope del cuerpo de un aviso del proveedor: se comprueba ANTES de calcular ningun HMAC. */
internal const val WEBHOOK_BODY_MAX_BYTES: Long = 64L * 1024

/**
 * Economia V1 (`openapi/wallet/wallet.yaml`, enmienda FA21):
 *
 *  - D6-03 `POST /api/v1/app/wallet/canjes`                          canjear un examen con OT (`Idempotency-Key` obligatoria)
 *  - D6-11 `GET  /api/v1/app/wallet/paquetes`                        paquetes de OT en venta (solo completos: cantidad y precio fijados)
 *  - D6-12 `POST /api/v1/app/payments/orders`                        crear un pedido de pago (`Idempotency-Key` obligatoria)
 *  - D6-13 `GET  /api/v1/app/payments/orders/{orderId}`              estado de un pedido propio
 *  - D6-14 `POST /api/v1/app/payments/orders/{orderId}/verify`       confirmar con justificante opaco
 *  - W-01  `POST /api/v1/webhooks/payments/{provider}`               aviso firmado del proveedor (sin JWT: autentica la firma)
 *
 * Identidad = SOLO el `sub` del JWT (deny-by-default), el limite por usuario va antes de leer el perfil, despues la forma de los ids
 * y solo entonces la clave de idempotencia y el cuerpo. Sin runtime las rutas existen pero fallan cerradas (500).
 */
internal fun Route.economyRoutes(runtime: EconomyRuntime?) {
    val store: IdempotencyStore = runtime?.idempotency ?: EconomyMisconfiguredStore
    route("/api/v1/app") {
        post("/wallet/canjes") {
            val rt = runtime.orMisconfigured()
            val identity = call.economyIdentity()
            val request = call.receiveCanonicalCommand<ExamRedeemRequest>(nullableFields = setOf("expectedPrice"))
            val result = rt.economy.redeem(identity.userId, request, call.idempotencyKey())
            if (result.replayed) {
                call.response.header(IdempotencyHeaders.REPLAYED, "true")
                call.respond(HttpStatusCode.OK, result.response)
            } else {
                call.respond(HttpStatusCode.Created, result.response)
            }
        }.install(CanonicalIdempotency) {
            routeTemplate = "/api/v1/app/wallet/canjes"
            required = true
            this.store = store
            before = { call -> call.economyGate(runtime, EconomyOperation.REDEEM) }
            actor = { call -> call.economyIdentity().userId.value }
        }
        get("/wallet/paquetes") {
            val rt = runtime.orMisconfigured()
            call.economyGate(runtime, EconomyOperation.PACKS)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(rt.economy.packs())
        }
        route("/payments/orders") {
            post {
                val rt = runtime.orMisconfigured()
                val identity = call.economyIdentity()
                val request = call.receiveCanonicalCommand<PaymentOrderRequest>()
                val result = rt.economy.createOrder(identity.userId, request, call.idempotencyKey())
                if (result.created) {
                    call.response.header(HttpHeaders.Location, "/api/v1/app/payments/orders/${result.response.order.id}")
                    call.respond(HttpStatusCode.Created, result.response)
                } else {
                    call.response.header(IdempotencyHeaders.REPLAYED, "true")
                    call.respond(HttpStatusCode.OK, result.response)
                }
            }.install(CanonicalIdempotency) {
                routeTemplate = "/api/v1/app/payments/orders"
                required = true
                this.store = store
                before = { call -> call.economyGate(runtime, EconomyOperation.ORDER_WRITE) }
                actor = { call -> call.economyIdentity().userId.value }
            }
            get("/{orderId}") {
                val rt = runtime.orMisconfigured()
                val identity = call.economyGate(runtime, EconomyOperation.ORDER_READ)
                requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
                call.respond(rt.economy.getOrder(identity.userId, call.parameters["orderId"].orEmpty().trim()))
            }
            post("/{orderId}/verify") {
                val rt = runtime.orMisconfigured()
                val identity = call.economyIdentity()
                val request = call.receiveCanonicalCommand<PaymentVerifyRequest>()
                call.respond(rt.economy.verify(identity.userId, call.parameters["orderId"].orEmpty().trim(), request))
            }.install(CanonicalIdempotency) {
                routeTemplate = "/api/v1/app/payments/orders/{orderId}/verify"
                required = false
                this.store = store
                before = { call -> call.economyGate(runtime, EconomyOperation.ORDER_WRITE) }
                actor = { call -> call.economyIdentity().userId.value }
            }
        }
    }
    // Aviso del proveedor: SIN JWT. Orden fijo: limite por IP, tope de cuerpo, proveedor de la lista blanca, firma; solo entonces la base.
    post("/api/v1/webhooks/payments/{provider}") {
        val rt = runtime.orMisconfigured()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        rt.webhookLimit(call)
        val raw = call.canonicalRawBody(WEBHOOK_BODY_MAX_BYTES)
        val result = rt.economy.webhook(call.parameters["provider"].orEmpty(), { name -> call.request.headers[name] }, raw)
        call.respond(WebhookAckDto(result))
    }
}

private val EconomyIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-economy-identity")

/** La puerta comun: `Cache-Control: no-store`, identidad ACTIVA con el limite por usuario antes de leer el perfil. Idempotente dentro de la llamada. */
private suspend fun ApplicationCall.economyGate(runtime: EconomyRuntime?, operation: EconomyOperation): CanonicalUserIdentity {
    attributes.getOrNull(EconomyIdentityKey)?.let { return it }
    val rt = runtime.orMisconfigured()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(EconomyIdentityKey, identity)
    return identity
}

private fun ApplicationCall.economyIdentity(): CanonicalUserIdentity =
    attributes.getOrNull(EconomyIdentityKey) ?: throw CanonicalErrorCode.INTERNAL.exception()

/** La clave ya validada por el plugin de idempotencia (obligatoria en las rutas que la usan). */
private fun ApplicationCall.idempotencyKey(): IdempotencyKey =
    IdempotencyKey.parse(request.headers[IdempotencyHeaders.KEY], required = true)!!

private fun EconomyRuntime?.orMisconfigured(): EconomyRuntime = this ?: throw CanonicalErrorCode.INTERNAL.exception()

private object EconomyMisconfiguredStore : IdempotencyStore {
    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult = throw CanonicalErrorCode.INTERNAL.exception()

    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) = Unit

    override suspend fun release(claim: IdempotencyClaim) = Unit
}
