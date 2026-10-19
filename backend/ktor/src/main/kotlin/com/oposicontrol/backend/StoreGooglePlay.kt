package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.RSAKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

// =====================================================================
// Google Play: verificacion en servidor con la Google Play Developer API (purchases.productsv2 / purchases.subscriptionsv2), consume /
// acknowledge DESPUES del credito, RTDN por Pub/Sub push autenticado con OIDC y sondeo de Voided Purchases. Fuentes en el contrato.
// La clave de una compra es el `purchaseToken` (nunca el orderId). Ningun token se registra en logs.
// =====================================================================

internal data class StoreHttpResponse(val status: Int, val body: String)

/** Puerto HTTP de las tiendas (bloqueante; se llama dentro de Dispatchers.IO). En pruebas se sustituye: nunca hay red en los tests. */
internal fun interface StoreHttp {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): StoreHttpResponse
}

internal class JavaStoreHttp(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : StoreHttp {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): StoreHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return StoreHttpResponse(response.statusCode(), response.body().orEmpty())
    }
}

/** La parte de la Play Developer API que usa el servidor. Respuestas JSON tal cual; los errores ya traducidos al sobre canonico. */
internal interface GooglePlayApi {
    fun getProduct(token: String): JsonObject
    fun getSubscription(token: String): JsonObject
    fun consume(productId: String, token: String)
    fun acknowledgeSubscription(subscriptionId: String, token: String)
    fun voided(startMillis: Long, pageToken: String?): JsonObject
}

/** Credenciales de la cuenta de servicio (fichero JSON de Play Console). La clave privada nunca sale de aqui. */
internal class GoogleServiceAccount(val clientEmail: String, val privateKey: RSAPrivateKey, val tokenUri: String) {
    companion object {
        fun parse(jsonText: String): GoogleServiceAccount {
            val obj = Json.parseToJsonElement(jsonText).jsonObject
            val email = obj["client_email"]?.jsonPrimitive?.contentOrNull ?: throw StoreConfigException("cuenta de servicio sin client_email")
            val pem = obj["private_key"]?.jsonPrimitive?.contentOrNull ?: throw StoreConfigException("cuenta de servicio sin private_key")
            val der = Base64.getMimeDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), ""))
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
            return GoogleServiceAccount(email, key, obj["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token")
        }
    }
}

internal class HttpGooglePlayApi(
    private val packageName: String,
    private val account: GoogleServiceAccount,
    private val http: StoreHttp = JavaStoreHttp(),
    private val clock: () -> Instant = Instant::now,
) : GooglePlayApi {
    private var accessToken: String? = null
    private var accessTokenExpires: Instant = Instant.EPOCH

    private val base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${enc(packageName)}/purchases"

    override fun getProduct(token: String): JsonObject = call("GET", "$base/productsv2/tokens/${enc(token)}")

    override fun getSubscription(token: String): JsonObject = call("GET", "$base/subscriptionsv2/tokens/${enc(token)}")

    override fun consume(productId: String, token: String) {
        call("POST", "$base/products/${enc(productId)}/tokens/${enc(token)}:consume", "")
    }

    override fun acknowledgeSubscription(subscriptionId: String, token: String) {
        call("POST", "$base/subscriptions/${enc(subscriptionId)}/tokens/${enc(token)}:acknowledge", "{}")
    }

    override fun voided(startMillis: Long, pageToken: String?): JsonObject =
        call("GET", "$base/voidedpurchases?type=0&maxResults=1000&startTime=$startMillis" + (pageToken?.let { "&token=${enc(it)}" } ?: ""))

    @Synchronized
    private fun bearer(): String {
        val now = clock()
        accessToken?.takeIf { now.isBefore(accessTokenExpires.minusSeconds(60)) }?.let { return it }
        val assertion = JWT.create()
            .withIssuer(account.clientEmail)
            .withAudience(account.tokenUri)
            .withClaim("scope", SCOPE)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(3600)))
            .sign(Algorithm.RSA256(null, account.privateKey))
        val response = try {
            http.send(
                "POST", account.tokenUri, mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                "grant_type=${enc("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${enc(assertion)}",
            )
        } catch (e: Exception) {
            throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = e)
        }
        if (response.status !in 200..299) throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        val obj = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrElse { throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception() }
        val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        accessToken = token
        accessTokenExpires = now.plusSeconds(obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L)
        return token
    }

    private fun call(method: String, url: String, body: String? = null): JsonObject {
        val response = try {
            http.send(method, url, mapOf("Authorization" to "Bearer ${bearer()}", "Content-Type" to "application/json"), body)
        } catch (e: BackendDomainException) {
            throw e
        } catch (e: Exception) {
            throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = e)
        }
        return when {
            response.status in 200..299 -> if (response.body.isBlank()) JsonObject(emptyMap()) else
                runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() }
            // token desconocido, de otra app, caducado o mal formado: definitivo
            response.status == 400 || response.status == 404 || response.status == 410 -> throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
            // 401/403 (credenciales), 429 y 5xx: transitorio, se reintenta
            else -> throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
    }
}

// ---------- OIDC de Pub/Sub push ----------

internal fun interface GoogleKeySource {
    fun key(kid: String?): RSAPublicKey?
}

/** JWKS de Google (`jwks_uri` de https://accounts.google.com/.well-known/openid-configuration), cacheado una hora; un `kid` nuevo refresca. */
internal class JwksGoogleKeySource(
    private val http: StoreHttp = JavaStoreHttp(),
    private val url: String = "https://www.googleapis.com/oauth2/v3/certs",
    private val clock: () -> Instant = Instant::now,
) : GoogleKeySource {
    private var keys: Map<String, RSAPublicKey> = emptyMap()
    private var fetchedAt: Instant = Instant.EPOCH

    @Synchronized
    override fun key(kid: String?): RSAPublicKey? {
        if (kid == null) return null
        val now = clock()
        val stale = now.isAfter(fetchedAt.plusSeconds(3600))
        val unknownAndRefreshable = kid !in keys && now.isAfter(fetchedAt.plusSeconds(60))
        if (stale || unknownAndRefreshable) {
            runCatching { http.send("GET", url, emptyMap(), null) }.getOrNull()?.takeIf { it.status == 200 }?.let { response ->
                keys = parseJwks(response.body)
                fetchedAt = now
            }
        }
        return keys[kid]
    }

    companion object {
        fun parseJwks(body: String): Map<String, RSAPublicKey> = runCatching {
            (Json.parseToJsonElement(body).jsonObject["keys"] as JsonArray).mapNotNull { element ->
                val k = element.jsonObject
                if (k["kty"]?.jsonPrimitive?.contentOrNull != "RSA") return@mapNotNull null
                val kid = k["kid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val n = BigInteger(1, Base64.getUrlDecoder().decode(k["n"]!!.jsonPrimitive.content))
                val e = BigInteger(1, Base64.getUrlDecoder().decode(k["e"]!!.jsonPrimitive.content))
                kid to (KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey)
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

/**
 * Token OIDC de Pub/Sub push: RS256 con las claves de Google, `iss` de Google, `aud` EXPLICITO (el configurado en la suscripcion push),
 * `email` = cuenta de servicio del push y `email_verified` = true, sin caducar. Cualquier fallo: 401 sin escribir nada.
 */
internal class GoogleOidcVerifier(private val audience: String, private val serviceAccount: String, private val keys: GoogleKeySource) {
    private val verifier = JWT.require(
        Algorithm.RSA256(object : RSAKeyProvider {
            override fun getPublicKeyById(keyId: String?): RSAPublicKey? = keys.key(keyId)
            override fun getPrivateKey(): RSAPrivateKey? = null
            override fun getPrivateKeyId(): String? = null
        }),
    )
        .withIssuer("accounts.google.com", "https://accounts.google.com")
        .withAudience(audience)
        .withClaim("email", serviceAccount)
        .withClaim("email_verified", true)
        .withClaimPresence("exp")
        .acceptLeeway(30)
        .build()

    fun verify(bearer: String) {
        try {
            verifier.verify(bearer)
        } catch (_: JWTVerificationException) {
            throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
        } catch (_: IllegalArgumentException) {
            throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
        }
    }
}

// ---------- proveedor ----------

internal class GooglePlayProvider(
    private val config: GooglePlayConfig,
    private val api: GooglePlayApi,
    private val oidc: GoogleOidcVerifier,
    private val clock: () -> Instant = Instant::now,
) : StorePaymentProvider {
    override val platform = StorePlatform.GOOGLE_PLAY

    override suspend fun verify(storeProductId: String, kind: StoreKind, token: String): StorePurchaseFacts = withContext(Dispatchers.IO) {
        when (kind) {
            StoreKind.CONSUMABLE -> readProduct(token).also { if (it.storeProductId != storeProductId) throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception() }
            StoreKind.SUBSCRIPTION -> readSubscription(token).also {
                if (it.storeProductId != storeProductId && it.storeProductId.substringBefore('/') != storeProductId) throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
            }
        }
    }

    override suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant): StoreEvent {
        val auth = header("Authorization")?.trim().orEmpty()
        if (!auth.startsWith("Bearer ", ignoreCase = true)) throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
        withContext(Dispatchers.IO) { oidc.verify(auth.substring(7).trim()) }
        val envelope = parseObject(String(rawBody, StandardCharsets.UTF_8)) ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        config.pubsubSubscription?.let { expected ->
            if (envelope.storeStr("subscription") != expected) throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
        }
        val message = envelope["message"] as? JsonObject ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val messageId = (message.storeStr("messageId") ?: message.storeStr("message_id"))?.takeIf { it.isNotBlank() && it.length <= 150 }
            ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        val data = message.storeStr("data")?.let { runCatching { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }.getOrNull() }?.let(::parseObject)
            ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
        // Autenticado pero de otra app: no es nuestro y no se escribe nada (Control: "package erroneo" -> 422, cero filas).
        if (data.storeStr("packageName") != config.packageName) throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val eventId = "rtdn:$messageId"
        val eventTime = data.storeStr("eventTimeMillis")?.toLongOrNull() ?: now.toEpochMilli()
        (data["subscriptionNotification"] as? JsonObject)?.let { n ->
            val token = n.storeStr("purchaseToken") ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
            return StoreEvent(platform, eventId, StoreEventType.SUBSCRIPTION, token, null,
                buildJsonObject { put("kind", StoreKind.SUBSCRIPTION.name); put("notificationType", n.storeInt("notificationType")); put("eventTimeMillis", eventTime) })
        }
        (data["oneTimeProductNotification"] as? JsonObject)?.let { n ->
            val token = n.storeStr("purchaseToken") ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
            return StoreEvent(platform, eventId, StoreEventType.PURCHASE, token, null,
                buildJsonObject { put("kind", StoreKind.CONSUMABLE.name); put("sku", n.storeStr("sku")); put("notificationType", n.storeInt("notificationType")) })
        }
        (data["voidedPurchaseNotification"] as? JsonObject)?.let { n ->
            val token = n.storeStr("purchaseToken") ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
            val kind = if (n.storeInt("productType") == 1) StoreKind.SUBSCRIPTION else StoreKind.CONSUMABLE
            return StoreEvent(platform, eventId, StoreEventType.REFUND, token, null,
                buildJsonObject { put("kind", kind.name); put("orderId", n.storeStr("orderId")); put("refundType", n.storeInt("refundType")) })
        }
        if (data["testNotification"] is JsonObject) return StoreEvent(platform, eventId, StoreEventType.TEST, null, null, JsonObject(emptyMap()))
        return StoreEvent(platform, eventId, StoreEventType.IGNORED, null, null, JsonObject(emptyMap()))
    }

    override suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject): StorePurchaseFacts? = withContext(Dispatchers.IO) {
        val token = purchaseKey ?: return@withContext null
        when (type) {
            StoreEventType.PURCHASE -> readProduct(token)
            StoreEventType.SUBSCRIPTION -> readSubscription(token)
            // void de una suscripcion: se relee y se cierra como reembolso (deniega al instante, D8)
            StoreEventType.REFUND -> readSubscription(token).let { f ->
                f.copy(subscription = f.subscription?.copy(estado = "caducada", finMotivo = "reembolso"), versionMillis = clock().toEpochMilli())
            }
            else -> null
        }
    }

    override suspend fun finish(kind: StoreKind, storeProductId: String, purchaseKey: String) = withContext(Dispatchers.IO) {
        when (kind) {
            StoreKind.CONSUMABLE -> {
                val current = readProduct(purchaseKey)
                if (current.needsFinish) api.consume(storeProductId, purchaseKey)
            }
            StoreKind.SUBSCRIPTION -> {
                val current = readSubscription(purchaseKey)
                if (current.needsFinish) api.acknowledgeSubscription(storeProductId, purchaseKey)
            }
        }
    }

    /** Sondeo diario de respaldo (Voided Purchases API, solo productos de compra unica: type=0). */
    suspend fun voidedSince(startMillis: Long): List<Pair<StoreEvent, String>> = withContext(Dispatchers.IO) {
        val events = mutableListOf<Pair<StoreEvent, String>>()
        var page: String? = null
        var pages = 0
        do {
            val response = api.voided(startMillis, page)
            (response["voidedPurchases"] as? JsonArray).orEmpty().forEach { element ->
                val v = element as? JsonObject ?: return@forEach
                val token = v.storeStr("purchaseToken") ?: return@forEach
                val id = v.storeStr("orderId") ?: sha256Hex(token.toByteArray(StandardCharsets.UTF_8))
                val event = StoreEvent(platform, "void:$id", StoreEventType.REFUND, token, null,
                    buildJsonObject { put("kind", StoreKind.CONSUMABLE.name); put("orderId", v.storeStr("orderId")); put("source", "voided_purchases_api") })
                events += event to sha256Hex(element.toString().toByteArray(StandardCharsets.UTF_8))
            }
            page = ((response["tokenPagination"] as? JsonObject)?.storeStr("nextPageToken"))
            pages++
        } while (page != null && pages < 20)
        events
    }

    private fun readProduct(token: String): StorePurchaseFacts {
        val obj = api.getProduct(token)
        val items = when (val li = obj["productLineItem"]) {
            is JsonArray -> li.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(li)
            else -> emptyList()
        }
        if (items.size != 1) throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val item = items.single()
        val productId = item.storeStr("productId") ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val details = item["productOfferDetails"] as? JsonObject
        val state = when ((obj["purchaseStateContext"] as? JsonObject)?.storeStr("purchaseState")) {
            "PURCHASED" -> StorePurchaseState.PURCHASED
            "PENDING" -> StorePurchaseState.PENDING
            "CANCELLED" -> StorePurchaseState.CANCELLED
            else -> throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        }
        return StorePurchaseFacts(
            platform = platform,
            kind = StoreKind.CONSUMABLE,
            storeProductId = productId,
            purchaseKey = token,
            accountToken = obj.storeStr("obfuscatedExternalAccountId"),
            environment = if (obj["testPurchaseContext"] != null) StoreEnvironment.SANDBOX else StoreEnvironment.PRODUCTION,
            quantity = details?.storeInt("quantity") ?: 1,
            state = state,
            versionMillis = clock().toEpochMilli(),
            needsFinish = state == StorePurchaseState.PURCHASED && details?.storeStr("consumptionState") != "CONSUMPTION_STATE_CONSUMED",
            audit = listOfNotNull(obj.storeStr("orderId")?.let { "pedidoTienda" to it }, obj.storeStr("regionCode")?.let { "region" to it }).toMap(),
        )
    }

    private fun readSubscription(token: String): StorePurchaseFacts {
        val obj = api.getSubscription(token)
        val line = ((obj["lineItems"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val productId = line.storeStr("productId") ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        val basePlan = (line["offerDetails"] as? JsonObject)?.storeStr("basePlanId")
        val expiry = line.storeStr("expiryTime")
        val (state, estado, motivo) = when (obj.storeStr("subscriptionState")) {
            "SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> Triple(StorePurchaseState.PURCHASED, "activa", null)
            "SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED" -> Triple(StorePurchaseState.PURCHASED, "pausada", null)
            "SUBSCRIPTION_STATE_CANCELED" -> Triple(StorePurchaseState.PURCHASED, "cancelada", "usuario_cancelo")
            "SUBSCRIPTION_STATE_EXPIRED" -> Triple(StorePurchaseState.PURCHASED, "caducada", "expiro")
            "SUBSCRIPTION_STATE_PENDING" -> Triple(StorePurchaseState.PENDING, "pausada", null)
            "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" -> Triple(StorePurchaseState.CANCELLED, "caducada", "expiro")
            else -> throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
        }
        return StorePurchaseFacts(
            platform = platform,
            kind = StoreKind.SUBSCRIPTION,
            storeProductId = basePlan?.let { "$productId/$it" } ?: productId,
            purchaseKey = token,
            accountToken = (obj["externalAccountIdentifiers"] as? JsonObject)?.storeStr("obfuscatedExternalAccountId"),
            environment = if (obj["testPurchase"] != null) StoreEnvironment.SANDBOX else StoreEnvironment.PRODUCTION,
            state = state,
            versionMillis = clock().toEpochMilli(),
            subscription = StoreSubscriptionFacts(
                estado = estado,
                inicio = obj.storeStr("startTime"),
                fin = expiry,
                renovacion = expiry.takeIf { estado == "activa" },
                finMotivo = motivo,
                linkedKey = obj.storeStr("linkedPurchaseToken"),
            ),
            needsFinish = state == StorePurchaseState.PURCHASED && obj.storeStr("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_PENDING",
            audit = listOfNotNull(line.storeStr("latestSuccessfulOrderId")?.let { "pedidoTienda" to it }, obj.storeStr("regionCode")?.let { "region" to it }).toMap(),
        )
    }

    private fun parseObject(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

internal fun JsonObject.storeStr(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull

internal fun JsonObject.storeInt(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

@Suppress("unused")
private fun JsonElement?.orEmptyArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
