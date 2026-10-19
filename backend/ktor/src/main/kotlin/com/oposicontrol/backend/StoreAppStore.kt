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

    override suspend fun verify(storeProductId: String, kind: StoreKind, token: String): StorePurchaseFacts {
        val tx = decodeTransaction(token) ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val facts = factsOf(tx, null, null)
        if (facts.storeProductId != storeProductId || facts.kind != kind) throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
        return facts
    }

    override suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant): StoreEvent {
        val body = runCatching { Json.parseToJsonElement(String(rawBody, StandardCharsets.UTF_8)).jsonObject }.getOrNull()
            ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val signed = body.storeStr("signedPayload")?.takeIf { it.isNotBlank() } ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val verifier = verifierFor(peekEnvironment(signed, nested = true)) ?: throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
        val notification = try {
            verifier.verifyAndDecodeNotification(signed)
        } catch (e: VerificationException) {
            throw appleVerificationFailure(e, CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID)
        }
        val uuid = notification.notificationUUID?.takeIf { it.isNotBlank() && it.length <= 150 } ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val eventId = "assn:$uuid"
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
            return StoreEvent(platform, eventId, eventType, null, null, buildJsonObject { put("notificationType", notification.rawNotificationType) })
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
        )
    }

    /** Los hechos ya se verificaron al recibir el aviso y la bandeja solo la escribe el servidor (service_role, RPC). */
    override suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject): StorePurchaseFacts? =
        (data["facts"] as? JsonObject)?.let { json.decodeFromJsonElement(StorePurchaseFacts.serializer(), it) }

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

    private fun factsOf(tx: JWSTransactionDecodedPayload, renewal: JWSRenewalInfoDecodedPayload?, type: NotificationTypeV2?, signedDate: Long? = null): StorePurchaseFacts {
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
                revoked -> StoreSubscriptionFacts("caducada", iso(tx.purchaseDate), iso(tx.revocationDate), null, if (type == NotificationTypeV2.REVOKE) "revocada" else "reembolso")
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
