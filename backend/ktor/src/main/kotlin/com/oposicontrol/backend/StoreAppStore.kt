package com.oposicontrol.backend

import com.apple.itunes.storekit.model.AutoRenewStatus
import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.model.JWSRenewalInfoDecodedPayload
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload
import com.apple.itunes.storekit.model.NotificationTypeV2
import com.apple.itunes.storekit.model.Type
import com.apple.itunes.storekit.verification.SignedDataVerifier
import com.apple.itunes.storekit.verification.VerificationException
import com.apple.itunes.storekit.verification.VerificationStatus
import com.auth0.jwt.JWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

// =====================================================================
// App Store: JWS de StoreKit 2 (`Transaction.jwsRepresentation`) y App Store Server Notifications V2 verificados con la App Store Server
// Library oficial de Apple (`SignedDataVerifier`: cadena x5c hoja -> WWDR -> raiz de Apple configurada, OIDs de Apple, ES256, bundleId,
// appAppleId y entorno). Nada de `verifyReceipt`. El entorno sale del payload firmado; Xcode / LocalTesting se rechazan siempre.
// El estado se re-deriva de la transaccion firmada (revocationDate, expiresDate) y de la renovacion firmada, no del tipo de aviso.
// =====================================================================

internal class AppStoreProvider(
    config: AppStoreConfig,
    rootCertificates: List<ByteArray>,
    private val clock: () -> Instant = Instant::now,
    /** App Store Server API (opcional, clave In-App Purchase). null = solo JWS firmados (cliente y avisos). */
    private val api: AppStoreServerApi? = null,
) : StorePaymentProvider {
    override val platform = StorePlatform.APP_STORE

    private val json = Json { ignoreUnknownKeys = true }
    private val production = SignedDataVerifier(
        rootCertificates.map { ByteArrayInputStream(it) }.toSet(), config.bundleId, config.appAppleId, Environment.PRODUCTION, config.onlineChecks,
    )
    private val sandbox = SignedDataVerifier(
        rootCertificates.map { ByteArrayInputStream(it) }.toSet(), config.bundleId, config.appAppleId, Environment.SANDBOX, config.onlineChecks,
    )

    init {
        require(rootCertificates.isNotEmpty()) { "App Store: hace falta al menos un certificado raiz" }
    }

    /**
     * La prueba del cliente se verifica SIEMPRE (firma, cadena, bundle, entorno). Con la App Store Server API ademas se relee de Apple (restaurar y
     * verificar re-validan con la tienda): Get Transaction Info para un pack, Get All Subscription Statuses para una suscripcion. Lo que manda
     * es lo que devuelve Apple, verificado igual.
     */
    override suspend fun verify(storeProductId: String, kind: StoreKind, token: String): StorePurchaseFacts {
        val tx = decodeTransaction(token) ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val signedFacts = factsOf(tx, null, null)
        if (signedFacts.storeProductId != storeProductId || signedFacts.kind != kind) throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
        val server = api ?: return signedFacts
        val facts = withContext(Dispatchers.IO) {
            when (kind) {
                StoreKind.CONSUMABLE -> {
                    val fresh = decodeTransaction(server.transactionInfo(signedFacts.environment, signedFacts.purchaseKey))
                        ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
                    factsOf(fresh, null, null)
                }
                StoreKind.SUBSCRIPTION -> subscriptionFromApi(server, signedFacts.environment, signedFacts.purchaseKey)
            }
        }
        if (facts.storeProductId != storeProductId || facts.kind != kind || facts.purchaseKey != signedFacts.purchaseKey) {
            throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        }
        return facts
    }

    override suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant): StoreEvent {
        val body = runCatching { Json.parseToJsonElement(String(rawBody, StandardCharsets.UTF_8)).jsonObject }.getOrNull()
            ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val signed = body.storeStr("signedPayload")?.takeIf { it.isNotBlank() } ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        return eventFromSignedPayload(signed)
    }

    /** Un `signedPayload` de ASSN V2 (del aviso o de Get Notification History) -> evento, verificado con la libreria de Apple. */
    fun eventFromSignedPayload(signed: String): StoreEvent {
        val verifier = verifierFor(peekEnvironment(signed, nested = true)) ?: throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
        val notification = try {
            verifier.verifyAndDecodeNotification(signed)
        } catch (e: VerificationException) {
            throw appleVerificationFailure(e, CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID)
        }
        val uuid = notification.notificationUUID?.takeIf { it.isNotBlank() && it.length <= 150 } ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val eventId = "assn:$uuid"
        // N4-4: la huella es la del signedPayload (el mismo por webhook y por Notification History), no la del cuerpo HTTP que lo envuelve.
        val print = sha256Hex(signed.toByteArray(StandardCharsets.UTF_8))
        val type = notification.notificationType
        val eventType = when (type) {
            NotificationTypeV2.TEST -> StoreEventType.TEST
            NotificationTypeV2.ONE_TIME_CHARGE -> StoreEventType.PURCHASE
            NotificationTypeV2.SUBSCRIBED, NotificationTypeV2.DID_RENEW, NotificationTypeV2.DID_FAIL_TO_RENEW, NotificationTypeV2.GRACE_PERIOD_EXPIRED,
            NotificationTypeV2.EXPIRED, NotificationTypeV2.DID_CHANGE_RENEWAL_STATUS, NotificationTypeV2.DID_CHANGE_RENEWAL_PREF,
            NotificationTypeV2.OFFER_REDEEMED, NotificationTypeV2.RENEWAL_EXTENDED, NotificationTypeV2.PRICE_INCREASE -> StoreEventType.SUBSCRIPTION
            NotificationTypeV2.REFUND, NotificationTypeV2.REVOKE -> StoreEventType.REFUND
            NotificationTypeV2.REFUND_REVERSED -> StoreEventType.REFUND_REVERSED
            NotificationTypeV2.CONSUMPTION_REQUEST -> StoreEventType.CONSUMPTION_REQUEST
            else -> StoreEventType.IGNORED
        }
        val data = notification.data
        val signedTx = data?.signedTransactionInfo
        if (signedTx == null || eventType == StoreEventType.TEST || eventType == StoreEventType.IGNORED) {
            return StoreEvent(platform, eventId, eventType, null, null, buildJsonObject { put("notificationType", notification.rawNotificationType) }, print)
        }
        val tx = try {
            verifier.verifyAndDecodeTransaction(signedTx)
        } catch (e: VerificationException) {
            throw appleVerificationFailure(e, CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID)
        }
        val renewal = data.signedRenewalInfo?.let {
            try {
                verifier.verifyAndDecodeRenewalInfo(it)
            } catch (e: VerificationException) {
                throw appleVerificationFailure(e, CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID)
            }
        }
        val facts = factsOf(tx, renewal, type, notification.signedDate)
        return StoreEvent(
            platform, eventId, eventType, facts.purchaseKey, facts.environment,
            buildJsonObject {
                put("kind", facts.kind.name)
                put("notificationType", notification.rawNotificationType)
                put("subtype", notification.rawSubtype)
                put("facts", json.encodeToJsonElement(StorePurchaseFacts.serializer(), facts))
            },
            print,
        )
    }

    /**
     * Los hechos ya se verificaron al recibir el aviso y la bandeja solo la escribe el servidor (service_role, RPC). Para una suscripcion, con la
     * App Store Server API se relee su estado ACTUAL (Get All Subscription Statuses): el orden de llegada de los avisos deja de importar.
     */
    override suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject): StorePurchaseFacts? {
        val stored = (data["facts"] as? JsonObject)?.let { json.decodeFromJsonElement(StorePurchaseFacts.serializer(), it) } ?: return null
        val server = api
        if (server == null || stored.kind != StoreKind.SUBSCRIPTION) return stored
        return withContext(Dispatchers.IO) { subscriptionFromApi(server, stored.environment, stored.purchaseKey) }
    }

    /** Sincronizacion del trabajo: estado actual de una suscripcion (solo con la API; sin ella, null y se espera al aviso). */
    override suspend fun currentSubscription(purchaseKey: String, environment: StoreEnvironment): StorePurchaseFacts? {
        val server = api ?: return null
        return withContext(Dispatchers.IO) { subscriptionFromApi(server, environment, purchaseKey) }
    }

    /**
     * Get Notification History (recuperar huecos): cada `signedPayload` verificado como un aviso. Uno con firma mala se salta (y se cuenta).
     * N4-3 / N5-1: como mucho [maxPages] paginas desde [startToken] (null = primera); [AppleHistoryRead.nextToken] = token de la siguiente si Apple
     * aun tenia mas (se guarda y se sigue en el siguiente sondeo, con la MISMA ventana).
     */
    suspend fun notificationHistory(
        environment: StoreEnvironment, startMillis: Long, endMillis: Long, log: (String) -> Unit = {}, maxPages: Int = 100, startToken: String? = null,
    ): AppleHistoryRead {
        val server = api ?: return AppleHistoryRead(emptyList(), null)
        return withContext(Dispatchers.IO) {
            val events = mutableListOf<Pair<StoreEvent, String>>()
            var token: String? = startToken
            var pages = 0
            var skipped = 0
            do {
                val page = server.notificationHistory(environment, startMillis, endMillis, token)
                page.signedPayloads.forEach { signed ->
                    try {
                        val event = eventFromSignedPayload(signed)
                        events += event to (event.fingerprint ?: sha256Hex(signed.toByteArray(StandardCharsets.UTF_8)))
                    } catch (e: BackendDomainException) {
                        if (e.status.value >= 500) throw e
                        skipped++
                    }
                }
                token = page.nextToken
                pages++
            } while (token != null && pages < maxPages)
            if (skipped > 0) log("store apple history: $skipped notification(s) failed verification and were skipped")
            AppleHistoryRead(events, token)
        }
    }

    val hasServerApi: Boolean get() = api != null

    private fun subscriptionFromApi(server: AppStoreServerApi, environment: StoreEnvironment, originalTransactionId: String): StorePurchaseFacts {
        val item = server.subscriptionStatuses(environment, originalTransactionId).firstOrNull { it.originalTransactionId == originalTransactionId }
            ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val tx = item.signedTransaction?.let { decodeTransaction(it) } ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val renewal = item.signedRenewal?.let { s ->
            val verifier = verifierFor(peekEnvironment(s, nested = false)) ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
            try {
                verifier.verifyAndDecodeRenewalInfo(s)
            } catch (e: VerificationException) {
                throw appleVerificationFailure(e, CanonicalErrorCode.STORE_PURCHASE_INVALID)
            }
        }
        // Lectura del estado ACTUAL: su version es la hora de lectura (como Google), siempre >= la de cualquier aviso ya aplicado.
        return factsOf(tx, renewal, null, clock().toEpochMilli(), item.status)
    }

    override suspend fun finish(kind: StoreKind, storeProductId: String, purchaseKey: String) = Unit

    private fun decodeTransaction(jws: String): JWSTransactionDecodedPayload? {
        val verifier = verifierFor(peekEnvironment(jws, nested = false)) ?: return null
        return try {
            verifier.verifyAndDecodeTransaction(jws)
        } catch (e: VerificationException) {
            throw appleVerificationFailure(e, CanonicalErrorCode.STORE_PURCHASE_INVALID)
        }
    }

    private fun verifierFor(environment: String?): SignedDataVerifier? = when (environment) {
        "Production" -> production
        "Sandbox" -> sandbox
        else -> null   // Xcode, LocalTesting o nada: nunca (la libreria no verificaria la firma en esos entornos)
    }

    /** Lee SIN verificar el entorno declarado para escoger el verificador; el verificador lo vuelve a comprobar tras validar la firma. */
    private fun peekEnvironment(jws: String, nested: Boolean): String? = runCatching {
        val claims = JWT.decode(jws)
        if (nested) (claims.getClaim("data").asMap()?.get("environment") as? String) else claims.getClaim("environment").asString()
    }.getOrNull()

    private fun factsOf(
        tx: JWSTransactionDecodedPayload,
        renewal: JWSRenewalInfoDecodedPayload?,
        type: NotificationTypeV2?,
        signedDate: Long? = null,
        appleStatus: Int? = null,
    ): StorePurchaseFacts {
        val kind = when (tx.type) {
            Type.CONSUMABLE -> StoreKind.CONSUMABLE
            Type.AUTO_RENEWABLE_SUBSCRIPTION -> StoreKind.SUBSCRIPTION
            else -> throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
        }
        val environment = when (tx.environment) {
            Environment.PRODUCTION -> StoreEnvironment.PRODUCTION
            Environment.SANDBOX -> StoreEnvironment.SANDBOX
            else -> throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        }
        val productId = tx.productId ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val key = (if (kind == StoreKind.SUBSCRIPTION) tx.originalTransactionId else tx.transactionId) ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val revoked = tx.revocationDate != null
        val now = clock().toEpochMilli()
        val subscription = if (kind == StoreKind.SUBSCRIPTION) {
            val expires = tx.expiresDate
            val grace = renewal?.gracePeriodExpiresDate
            when {
                revoked || appleStatus == APPLE_STATUS_REVOKED ->
                    StoreSubscriptionFacts("caducada", iso(tx.purchaseDate), iso(tx.revocationDate ?: now), null, if (type == NotificationTypeV2.REVOKE) "revocada" else "reembolso")
                // Get All Subscription Statuses: el estado lo dice Apple; las fechas, la transaccion y la renovacion firmadas.
                appleStatus == APPLE_STATUS_EXPIRED -> StoreSubscriptionFacts("caducada", iso(tx.purchaseDate), iso(expires), null, "expiro")
                appleStatus == APPLE_STATUS_BILLING_RETRY -> StoreSubscriptionFacts("pausada", iso(tx.purchaseDate), iso(expires), null, null)
                appleStatus == APPLE_STATUS_GRACE && grace != null -> StoreSubscriptionFacts("activa", iso(tx.purchaseDate), iso(grace), iso(grace), null)
                grace != null && grace > now -> StoreSubscriptionFacts("activa", iso(tx.purchaseDate), iso(grace), iso(grace), null)
                expires != null && expires > now && renewal?.autoRenewStatus == AutoRenewStatus.OFF ->
                    StoreSubscriptionFacts("cancelada", iso(tx.purchaseDate), iso(expires), null, "usuario_cancelo")
                expires != null && expires > now -> StoreSubscriptionFacts("activa", iso(tx.purchaseDate), iso(expires), iso(renewal?.renewalDate ?: expires), null)
                renewal?.isInBillingRetryPeriod == true -> StoreSubscriptionFacts("pausada", iso(tx.purchaseDate), iso(expires), null, null)
                else -> StoreSubscriptionFacts("caducada", iso(tx.purchaseDate), iso(expires), null, "expiro")
            }
        } else null
        return StorePurchaseFacts(
            platform = platform,
            kind = kind,
            storeProductId = productId,
            purchaseKey = key,
            accountToken = tx.appAccountToken?.toString(),
            environment = environment,
            quantity = tx.quantity ?: 1,
            state = if (revoked) StorePurchaseState.REVOKED else StorePurchaseState.PURCHASED,
            versionMillis = signedDate ?: tx.signedDate ?: now,
            subscription = subscription,
            needsFinish = false,
            audit = listOfNotNull(
                tx.transactionId?.let { "transaccionTienda" to it },
                tx.price?.let { "precioMilesimas" to it.toString() },
                tx.currency?.let { "moneda" to it },
                tx.storefront?.let { "storefront" to it },
            ).toMap(),
        )
    }

    private fun iso(millis: Long?): String? = millis?.let { Instant.ofEpochMilli(it).toString() }
}

/**
 * F6 (auditoria Control 2026-10-19): un fallo REINTENTABLE de la verificacion de Apple (estado de revocacion OCSP indeterminado, p. ej. caida
 * del OCSP de Apple) es 503 UPSTREAM_UNAVAILABLE: la tienda / el cliente reintentan y no se marca la compra como invalida. El resto, [definitive].
 */
internal fun appleVerificationFailure(e: VerificationException, definitive: CanonicalErrorCode): BackendDomainException =
    if (e.status == VerificationStatus.RETRYABLE_VERIFICATION_FAILURE) CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = e) else definitive.exception()

/** N4-3: lo leido de Get Notification History y si se leyo ENTERO (sin `paginationToken` pendiente). */
internal data class AppleHistoryRead(val events: List<Pair<StoreEvent, String>>, val nextToken: String?) {
    /** Sin `paginationToken` pendiente: se leyo la ventana entera. */
    val complete: Boolean get() = nextToken == null
}
