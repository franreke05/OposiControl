package com.oposicontrol.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Google Play: OIDC de Pub/Sub, RTDN, lectura de compras (productsv2 / subscriptionsv2) y el cliente HTTP de la API, todo sin red. */
class StoreGooglePlayProviderTest {
    private val keys = GoogleTestKeys()
    private val config = GooglePlayConfig("com.oposibot.app", "unused.json", GoogleTestKeys.AUDIENCE, GoogleTestKeys.SERVICE_ACCOUNT, null)
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val now = Instant.now()

    internal class FakeApi : GooglePlayApi {
        var product: JsonObject = JsonObject(emptyMap())
        var subscription: JsonObject = JsonObject(emptyMap())
        var voidedPages: List<JsonObject> = emptyList()
        val consumed = CopyOnWriteArrayList<String>()
        val acknowledged = CopyOnWriteArrayList<String>()
        var failConsume = false
        override fun getProduct(token: String) = product
        override fun getSubscription(token: String) = subscription
        override fun consume(productId: String, token: String) {
            if (failConsume) throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
            consumed += "$productId|$token"
        }
        override fun acknowledgeSubscription(subscriptionId: String, token: String) {
            acknowledged += "$subscriptionId|$token"
        }
        override fun voided(startMillis: Long, pageToken: String?): JsonObject = voidedPages[pageToken?.toInt() ?: 0]
    }

    private val api = FakeApi()
    private val provider = GooglePlayProvider(config, api, GoogleOidcVerifier(GoogleTestKeys.AUDIENCE, GoogleTestKeys.SERVICE_ACCOUNT, keys.source), clock = { now })

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun product(state: String = "PURCHASED", quantity: Int = 1, test: Boolean = false, consumed: Boolean = false, token: String? = account, productId: String = "ot_100") = obj(
        """{"productLineItem":[{"productId":"$productId","productOfferDetails":{"quantity":$quantity,"consumptionState":"${if (consumed) "CONSUMPTION_STATE_CONSUMED" else "CONSUMPTION_STATE_YET_TO_BE_CONSUMED"}"}}],""" +
            """"purchaseStateContext":{"purchaseState":"$state"},"orderId":"GPA.1","regionCode":"ES"""" +
            (token?.let { ""","obfuscatedExternalAccountId":"$it"""" } ?: "") + (if (test) ""","testPurchaseContext":{"fopType":"TEST"}""" else "") + "}",
    )

    private fun subscription(state: String, ack: String = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", linked: String? = null, test: Boolean = false) = obj(
        """{"subscriptionState":"$state","acknowledgementState":"$ack","startTime":"2026-10-01T00:00:00Z",""" +
            """"lineItems":[{"productId":"premium","expiryTime":"2026-11-01T00:00:00Z","offerDetails":{"basePlanId":"monthly"}}],""" +
            """"externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$account"}""" +
            (linked?.let { ""","linkedPurchaseToken":"$it"""" } ?: "") + (if (test) ""","testPurchase":{}""" else "") + "}",
    )

    private fun pubsub(data: String, messageId: String = "m-1", subscription: String = "projects/p/subscriptions/rtdn") =
        """{"message":{"data":"${Base64.getEncoder().encodeToString(data.toByteArray())}","messageId":"$messageId","publishTime":"2026-10-19T10:00:00Z"},"subscription":"$subscription"}""".toByteArray()

    private fun rtdn(inner: String, pkg: String = "com.oposibot.app") = """{"version":"1.0","packageName":"$pkg","eventTimeMillis":"${now.toEpochMilli()}",$inner}"""

    private suspend fun parse(body: ByteArray, token: String? = keys.token()) =
        provider.parseNotification({ if (it == "Authorization") token?.let { t -> "Bearer $t" } else null }, body, now)

    // ---------- OIDC ----------

    @Test
    fun aValidPubSubOidcTokenAndPackageAreAccepted() = runTest {
        val event = parse(pubsub(rtdn(""""oneTimeProductNotification":{"version":"1.0","notificationType":1,"purchaseToken":"tok-1","sku":"ot_100"}""")))
        assertEquals("rtdn:m-1", event.eventId)
        assertEquals(StoreEventType.PURCHASE, event.type)
        assertEquals("tok-1", event.purchaseKey)
    }

    @Test
    fun wrongAudienceIssuerEmailUnverifiedExpiredForeignKeyOrMissingTokenAre401() = runTest {
        val body = pubsub(rtdn(""""testNotification":{"version":"1.0"}"""))
        listOf(
            keys.token(aud = "https://other.example"),
            keys.token(iss = "https://evil.example"),
            keys.token(email = "someone@else.iam.gserviceaccount.com"),
            keys.token(emailVerified = false),
            keys.token(expiresIn = -3600),
            keys.token(key = GoogleTestKeys.otherKey()),
            keys.token(kid = "unknown-kid"),
            "not.a.jwt",
            null,
        ).forEach { token ->
            val e = assertFailsWith<BackendDomainException> { parse(body, token) }
            assertEquals("WEBHOOK_SIGNATURE_INVALID", e.errorCode)
        }
    }

    @Test
    fun anAuthenticMessageForAnotherPackageIsRefusedAndAMalformedOneIs400() = runTest {
        assertEquals("STORE_PURCHASE_INVALID", assertFailsWith<BackendDomainException> { parse(pubsub(rtdn(""""testNotification":{}""", pkg = "com.other"))) }.errorCode)
        assertEquals("MALFORMED_BODY", assertFailsWith<BackendDomainException> { parse("{}".toByteArray()) }.errorCode)
    }

    @Test
    fun aConfiguredPushSubscriptionMustMatch() = runTest {
        val strict = GooglePlayProvider(config.copy(pubsubSubscription = "projects/p/subscriptions/rtdn"), api, GoogleOidcVerifier(GoogleTestKeys.AUDIENCE, GoogleTestKeys.SERVICE_ACCOUNT, keys.source))
        val body = pubsub(rtdn(""""testNotification":{}"""), subscription = "projects/p/subscriptions/other")
        assertEquals("WEBHOOK_SIGNATURE_INVALID", assertFailsWith<BackendDomainException> {
            strict.parseNotification({ if (it == "Authorization") "Bearer ${keys.token()}" else null }, body, now)
        }.errorCode)
    }

    @Test
    fun rtdnKindsMapToEvents() = runTest {
        assertEquals(StoreEventType.SUBSCRIPTION, parse(pubsub(rtdn(""""subscriptionNotification":{"version":"1.0","notificationType":6,"purchaseToken":"s-1"}"""))).type)
        val void = parse(pubsub(rtdn(""""voidedPurchaseNotification":{"purchaseToken":"tok-9","orderId":"GPA.9","productType":2,"refundType":1}"""), "m-2"))
        assertEquals(StoreEventType.REFUND, void.type)
        assertEquals("CONSUMABLE", void.data["kind"].toString().trim('"'))
        assertEquals(StoreEventType.TEST, parse(pubsub(rtdn(""""testNotification":{"version":"1.0"}"""), "m-3")).type)
    }

    // ---------- lectura de compras ----------

    @Test
    fun consumableStatesQuantityTestPurchaseAndConsumptionAreRead() = runTest {
        api.product = product()
        val facts = provider.verify("ot_100", StoreKind.CONSUMABLE, "tok-1")
        assertEquals(StorePurchaseState.PURCHASED, facts.state)
        assertEquals(account, facts.accountToken)
        assertTrue(facts.needsFinish)
        assertEquals("GPA.1", facts.audit["pedidoTienda"])
        api.product = product(state = "PENDING")
        assertEquals(StorePurchaseState.PENDING, provider.verify("ot_100", StoreKind.CONSUMABLE, "tok-1").state)
        api.product = product(quantity = 2)
        assertEquals(2, provider.verify("ot_100", StoreKind.CONSUMABLE, "tok-1").quantity)
        api.product = product(test = true)
        assertEquals(StoreEnvironment.SANDBOX, provider.verify("ot_100", StoreKind.CONSUMABLE, "tok-1").environment)
        api.product = product(productId = "ot_500")
        assertEquals("STORE_PRODUCT_UNKNOWN", assertFailsWith<BackendDomainException> { provider.verify("ot_100", StoreKind.CONSUMABLE, "tok-1") }.errorCode)
    }

    @Test
    fun subscriptionStatesMapToAccessStates() = runTest {
        val cases = mapOf(
            "SUBSCRIPTION_STATE_ACTIVE" to "activa",
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" to "activa",
            "SUBSCRIPTION_STATE_ON_HOLD" to "pausada",
            "SUBSCRIPTION_STATE_PAUSED" to "pausada",
            "SUBSCRIPTION_STATE_CANCELED" to "cancelada",
            "SUBSCRIPTION_STATE_EXPIRED" to "caducada",
        )
        cases.forEach { (state, estado) ->
            api.subscription = subscription(state)
            val facts = provider.verify("premium", StoreKind.SUBSCRIPTION, "s-1")
            assertEquals(estado, facts.subscription?.estado, state)
            assertEquals("premium/monthly", facts.storeProductId)
        }
        api.subscription = subscription("SUBSCRIPTION_STATE_PENDING")
        assertEquals(StorePurchaseState.PENDING, provider.verify("premium", StoreKind.SUBSCRIPTION, "s-1").state)
        api.subscription = subscription("SUBSCRIPTION_STATE_ACTIVE", ack = "ACKNOWLEDGEMENT_STATE_PENDING", linked = "s-0", test = true)
        val f = provider.verify("premium", StoreKind.SUBSCRIPTION, "s-1")
        assertTrue(f.needsFinish)
        assertEquals("s-0", f.subscription?.linkedKey)
        assertEquals(StoreEnvironment.SANDBOX, f.environment)
    }

    @Test
    fun aVoidedSubscriptionIsClosedAsRefunded() = runTest {
        api.subscription = subscription("SUBSCRIPTION_STATE_ACTIVE")
        val facts = provider.factsFor(StoreEventType.REFUND, "s-1", JsonObject(emptyMap()))
        assertEquals("caducada", facts?.subscription?.estado)
        assertEquals("reembolso", facts?.subscription?.finMotivo)
    }

    @Test
    fun finishConsumesOnlyWhatIsNotConsumedAndAcknowledgesOnlyPending() = runTest {
        api.product = product(consumed = true)
        provider.finish(StoreKind.CONSUMABLE, "ot_100", "tok-1")
        assertTrue(api.consumed.isEmpty())
        api.product = product()
        provider.finish(StoreKind.CONSUMABLE, "ot_100", "tok-1")
        assertEquals(listOf("ot_100|tok-1"), api.consumed.toList())
        api.subscription = subscription("SUBSCRIPTION_STATE_ACTIVE", ack = "ACKNOWLEDGEMENT_STATE_PENDING")
        provider.finish(StoreKind.SUBSCRIPTION, "premium", "s-1")
        assertEquals(listOf("premium|s-1"), api.acknowledged.toList())
    }

    @Test
    fun voidedPurchasesArePagedAndKeyedByOrderId() = runTest {
        api.voidedPages = listOf(
            obj("""{"voidedPurchases":[{"purchaseToken":"tok-1","orderId":"GPA.1","voidedTimeMillis":"1"}],"tokenPagination":{"nextPageToken":"1"}}"""),
            obj("""{"voidedPurchases":[{"purchaseToken":"tok-2","voidedTimeMillis":"2"}]}"""),
        )
        val events = provider.voidedSince(0)
        assertEquals(listOf("void:GPA.1", "void:" + sha256Hex("tok-2".toByteArray())), events.map { it.first.eventId })
        assertTrue(events.all { it.first.type == StoreEventType.REFUND })
    }

    // ---------- cliente HTTP de la API (sin red) ----------

    @Test
    fun theApiClientUsesAServiceAccountBearerAndMapsErrors() {
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val account = GoogleServiceAccount("svc@p.iam.gserviceaccount.com", rsa.private as java.security.interfaces.RSAPrivateKey, "https://oauth2.example.test/token")
        val calls = CopyOnWriteArrayList<String>()
        var status = 200
        val http = StoreHttp { method, url, headers, _ ->
            calls += "$method $url ${headers["Authorization"].orEmpty().take(13)}"
            when {
                url.startsWith("https://oauth2.example.test") -> StoreHttpResponse(200, """{"access_token":"at-1","expires_in":3600}""")
                else -> StoreHttpResponse(status, """{"productLineItem":[]}""")
            }
        }
        val client = HttpGooglePlayApi("com.oposibot.app", account, http)
        client.getProduct("tok/1")
        client.getProduct("tok-2")
        assertEquals(1, calls.count { it.startsWith("POST https://oauth2.example.test") }, "access token cached")
        assertTrue(calls.any { it.startsWith("GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.oposibot.app/purchases/productsv2/tokens/tok%2F1 Bearer at-1") })
        status = 404
        assertEquals("STORE_PURCHASE_INVALID", assertFailsWith<BackendDomainException> { client.getProduct("x") }.errorCode)
        status = 503
        assertEquals("UPSTREAM_UNAVAILABLE", assertFailsWith<BackendDomainException> { client.getProduct("x") }.errorCode)
        status = 403
        assertEquals("UPSTREAM_UNAVAILABLE", assertFailsWith<BackendDomainException> { client.getProduct("x") }.errorCode)
    }

    @Test
    fun jwksAreParsedFromGoogleFormat() {
        val n = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.public.modulus.toByteArray())
        val e = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.public.publicExponent.toByteArray())
        val parsed = JwksGoogleKeySource.parseJwks("""{"keys":[{"kty":"RSA","kid":"k1","n":"$n","e":"$e","alg":"RS256"}]}""")
        assertEquals(keys.public.modulus, parsed["k1"]?.modulus)
    }
}
