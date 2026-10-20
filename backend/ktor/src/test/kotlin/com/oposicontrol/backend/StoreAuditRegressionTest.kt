package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regresion de la auditoria de OposiControl 2026-10-19 (AuditStoreAdversarialTest.kt, A-K1..A-K8), con los proveedores REALES de Google /
 * Apple montados en la ruta. A-K3 y A-K6 confirmaban defectos (F7, F2/F4) y estan INVERTIDOS: ahora prueban el comportamiento corregido.
 */
class StoreAuditRegressionTest {
    private val now = Instant.now()
    private val json = Json { ignoreUnknownKeys = true }
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val otherAccount = "7a2d3d2f-2222-4b7c-8c2f-3b3c3d3e3f40"
    private val bundle = "com.oposibot.test"
    private val appAppleId = 1234567890L
    private val pki = StoreTestPki()
    private val keys = GoogleTestKeys()

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) },
    )

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        val handlers = mutableMapOf<String, (String) -> SupabaseRawResponse>()
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return handlers[function]?.invoke(body) ?: SupabaseRawResponse(500, "{}")
        }
        fun names() = rpcs.map { it.first }
    }

    private class Api : GooglePlayApi {
        var product: JsonObject = JsonObject(emptyMap())
        val consumed = CopyOnWriteArrayList<String>()
        override fun getProduct(token: String) = product
        override fun getSubscription(token: String): JsonObject = JsonObject(emptyMap())
        override fun consume(productId: String, token: String) { consumed += token }
        override fun acknowledgeSubscription(subscriptionId: String, token: String) = Unit
        override fun voided(startMillis: Long, pageToken: String?): JsonObject = JsonObject(emptyMap())
    }

    private val upstream = Upstream().apply {
        handlers["app_store_inbox_put"] = { SupabaseRawResponse(200, """[{"nuevo":true,"estado":"pendiente","resultado":null,"misma_huella":true}]""") }
        handlers["app_store_inbox_mark"] = { SupabaseRawResponse(200, "") }
        handlers["app_store_refund_reversed"] = { SupabaseRawResponse(200, "\"NOOP\"") }
        handlers["app_store_refund_purchase"] = { SupabaseRawResponse(200, """[{"outcome":"REFUNDED","orden_id":null,"estado":"reembolsada","saldo":0}]""") }
        handlers["app_store_entitlements"] = { SupabaseRawResponse(200, """[{"saldo":0,"es_premium":false,"estado_suscripcion":"sin_suscripcion"}]""") }
    }
    private val api = Api()
    private val catalog = StoreCatalog.parse("google_play:ot_100=PACK_100:CONSUMABLE,app_store:com.oposibot.ot100=PACK_100:CONSUMABLE")

    private fun service(refunds: Boolean = true, subscription: String = "projects/p/subscriptions/rtdn"): StorePaymentsService {
        val gcfg = GooglePlayConfig("com.oposibot.app", "unused.json", GoogleTestKeys.AUDIENCE, GoogleTestKeys.SERVICE_ACCOUNT, subscription)
        val google = GooglePlayProvider(gcfg, api, GoogleOidcVerifier(GoogleTestKeys.AUDIENCE, GoogleTestKeys.SERVICE_ACCOUNT, keys.source), clock = { now })
        val apple = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), onlineChecks = false), listOf(pki.rootDer))
        return testStoreService(upstream, mapOf(StorePlatform.GOOGLE_PLAY to google, StorePlatform.APP_STORE to apple), catalog,
            StoreSandboxPolicy.parse("reject", null), refunds, clock = { now })
    }

    private fun Application.module(svc: StorePaymentsService) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { storePaymentRoutes(StorePaymentsRuntime(resolver, svc, InMemoryIdempotencyStore { now })) }
    }

    private fun pubsub(data: String, subscription: String = "projects/p/subscriptions/rtdn") =
        """{"message":{"data":"${Base64.getEncoder().encodeToString(data.toByteArray())}","messageId":"m-1"},"subscription":"$subscription"}"""

    private val rtdnTest = """{"version":"1.0","packageName":"com.oposibot.app","eventTimeMillis":"1","testNotification":{"version":"1.0"}}"""

    private suspend fun HttpClient.hook(platform: String, body: String, bearer: String? = null): HttpResponse = post("/api/v1/webhooks/store/$platform") {
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun tx(revocation: Long? = null, quantity: Int = 1, token: String? = account, env: String = "Production") = buildString {
        append("""{"transactionId":"2000000001","originalTransactionId":"2000000001","bundleId":"$bundle","productId":"com.oposibot.ot100",""")
        append(""""purchaseDate":${now.toEpochMilli() - 1000},"quantity":$quantity,"type":"Consumable","signedDate":${now.toEpochMilli()},"environment":"$env"""")
        token?.let { append(""","appAccountToken":"$it"""") }
        revocation?.let { append(""","revocationDate":$it,"revocationReason":0""") }
        append("}")
    }

    private fun assn(type: String, txJws: String, uuid: String, env: String = "Production", signer: StoreTestPki = pki) = signer.sign(
        """{"notificationType":"$type","notificationUUID":"$uuid","version":"2.0","signedDate":${now.toEpochMilli()},""" +
            """"data":{"environment":"$env","bundleId":"$bundle","appAppleId":$appAppleId,"signedTransactionInfo":"$txJws"}}""",
    )

    private suspend fun result(r: HttpResponse): JsonObject? { val t = r.bodyAsText(); return runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() }

    // A-K1: OIDC con aud de OTRO endpoint (otra suscripcion push del mismo proyecto), firmado por la clave buena de Google: 401 y cero RPC.
    @Test
    fun rtdnWithAnotherAudienceIs401AndWritesNothing() = testApplication {
        application { module(service()) }
        val r = createClient { }.hook("google-play", pubsub(rtdnTest), keys.token(aud = "https://evil.example/push"))
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(upstream.rpcs.isEmpty(), "zero RPCs: ${upstream.names()}")
    }

    // A-K2: confusion de algoritmo: HS256 con la clave PUBLICA RSA como secreto y alg=none. 401, cero RPC.
    @Test
    fun rtdnAlgorithmConfusionAndAlgNoneAre401() = testApplication {
        application { module(service()) }
        val client = createClient { }
        val hs = JWT.create().withKeyId(keys.kid).withIssuer("https://accounts.google.com").withAudience(GoogleTestKeys.AUDIENCE)
            .withClaim("email", GoogleTestKeys.SERVICE_ACCOUNT).withClaim("email_verified", true).withExpiresAt(Date.from(now.plusSeconds(600)))
            .sign(Algorithm.HMAC256(keys.public.encoded))
        val none = JWT.create().withKeyId(keys.kid).withIssuer("https://accounts.google.com").withAudience(GoogleTestKeys.AUDIENCE)
            .withClaim("email", GoogleTestKeys.SERVICE_ACCOUNT).withClaim("email_verified", true).withExpiresAt(Date.from(now.plusSeconds(600)))
            .sign(Algorithm.none())
        val noExp = JWT.create().withKeyId(keys.kid).withIssuer("https://accounts.google.com").withAudience(GoogleTestKeys.AUDIENCE)
            .withClaim("email", GoogleTestKeys.SERVICE_ACCOUNT).withClaim("email_verified", true)
            .sign(Algorithm.RSA256(null, java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as java.security.interfaces.RSAPrivateKey))
        for (t in listOf(hs, none, noExp, keys.token(emailVerified = false), keys.token(iss = "https://accounts.google.com.evil"))) {
            assertEquals(HttpStatusCode.Unauthorized, client.hook("google-play", pubsub(rtdnTest), t).status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.hook("google-play", pubsub(rtdnTest), null).status)
        assertTrue(upstream.rpcs.isEmpty(), "zero RPCs: ${upstream.names()}")
    }

    // A-K3 (INVERTIDO, F7): la suscripcion Pub/Sub es OBLIGATORIA con Google activado (el arranque falla sin ella) y otro nombre es 401, cero RPC.
    @Test
    fun rtdnSubscriptionNameIsAlwaysRequiredAndChecked() = testApplication {
        application { module(service()) }
        val r = createClient { }.hook("google-play", pubsub(rtdnTest, subscription = "projects/other/subscriptions/x"), keys.token())
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(upstream.rpcs.isEmpty(), "zero RPCs: ${upstream.names()}")
        val sa = java.io.File.createTempFile("svc-account", ".json").apply { writeText("{}"); deleteOnExit() }
        val failure = runCatching {
            StorePaymentsSettings.fromEnvironment(
                mapOf(
                    STORE_GOOGLE_ENABLED_ENV to "true", "PAYMENTS_GOOGLE_PACKAGE_NAME" to "com.x", "PAYMENTS_GOOGLE_PUBSUB_AUDIENCE" to "https://a",
                    "PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT" to "s@x", "PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE" to sa.path,
                    "PAYMENTS_STORE_PRODUCTS" to "google_play:ot_100=PACK_100:CONSUMABLE",
                )::get,
            )
        }.exceptionOrNull()
        assertTrue(failure is StoreConfigException && failure.message.orEmpty().contains("PAYMENTS_GOOGLE_PUBSUB_SUBSCRIPTION"), "startup must fail: $failure")
    }

    // A-K4: ASSN firmado por una cadena NO de Apple (otra raiz con los mismos OIDs), y ASSN de otro bundle / entorno Xcode: 401, cero RPC.
    @Test
    fun appleNotificationsFromAForeignChainOtherBundleOrXcodeAre401AndWriteNothing() = testApplication {
        application { module(service()) }
        val client = createClient { }
        val foreign = StoreTestPki()
        val forged = assn("ONE_TIME_CHARGE", foreign.sign(tx()), "u-1", signer = foreign)
        assertEquals(HttpStatusCode.Unauthorized, client.hook("app-store", """{"signedPayload":"$forged"}""").status)
        // envoltorio legitimo con una transaccion interior forjada
        val innerForged = assn("ONE_TIME_CHARGE", foreign.sign(tx()), "u-2")
        assertEquals(HttpStatusCode.Unauthorized, client.hook("app-store", """{"signedPayload":"$innerForged"}""").status)
        val otherBundle = pki.sign("""{"notificationType":"TEST","notificationUUID":"u-3","version":"2.0","signedDate":1,"data":{"environment":"Production","bundleId":"com.evil","appAppleId":$appAppleId}}""")
        assertEquals(HttpStatusCode.Unauthorized, client.hook("app-store", """{"signedPayload":"$otherBundle"}""").status)
        val xcode = pki.sign("""{"notificationType":"TEST","notificationUUID":"u-4","version":"2.0","signedDate":1,"data":{"environment":"Xcode","bundleId":"$bundle","appAppleId":$appAppleId}}""")
        assertEquals(HttpStatusCode.Unauthorized, client.hook("app-store", """{"signedPayload":"$xcode"}""").status)
        assertTrue(upstream.rpcs.isEmpty(), "zero RPCs: ${upstream.names()}")
    }

    // A-K5: verify con una compra cuyo token de cuenta es de OTRO usuario (la base lo rechaza) o que no lo trae: 422, sin consume.
    @Test
    fun verifyWithAnotherUsersAccountTokenOrNoneIsRejectedAndNeverConsumed() = testApplication {
        application { module(service()) }
        val client = createClient { }
        upstream.handlers["app_store_credit_purchase"] = { SupabaseRawResponse(400, """{"code":"P0001","message":"STORE_ACCOUNT_MISMATCH"}""") }
        fun product(token: String?) = Json.parseToJsonElement(
            """{"productLineItem":[{"productId":"ot_100","productOfferDetails":{"quantity":1,"consumptionState":"CONSUMPTION_STATE_YET_TO_BE_CONSUMED"}}],"purchaseStateContext":{"purchaseState":"PURCHASED"}""" +
                (token?.let { ""","obfuscatedExternalAccountId":"$it"""" } ?: "") + "}",
        ).jsonObject
        suspend fun verify(idem: String, accountToken: String? = null) = client.post("/api/v1/app/store/purchases/verify") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); header("Idempotency-Key", idem); contentType(ContentType.Application.Json)
            setBody("""{"platform":"GOOGLE_PLAY","storeProductId":"ot_100","kind":"CONSUMABLE","token":"gp-x"""" + (accountToken?.let { ""","accountToken":"$it"""" } ?: "") + "}")
        }
        api.product = product(otherAccount)
        val r1 = verify("11111111-1111-4111-8111-111111111111")
        assertEquals(HttpStatusCode.UnprocessableEntity, r1.status, r1.bodyAsText())
        api.product = product(null)
        val r2 = verify("22222222-2222-4222-8222-222222222222")
        assertEquals(HttpStatusCode.UnprocessableEntity, r2.status)
        api.product = product(otherAccount)
        val r3 = verify("33333333-3333-4333-8333-333333333333", accountToken = account)   // el cliente dice el suyo, la tienda firma otro
        assertEquals(HttpStatusCode.UnprocessableEntity, r3.status)
        assertTrue(api.consumed.isEmpty(), "never consumed")
        assertEquals(1, upstream.names().count { it == "app_store_credit_purchase" }, "only r1 reached the DB (which refused): ${upstream.names()}")
    }

    // A-K6 (INVERTIDO, F2 + F4): con reembolsos APAGADOS el REFUND queda RETENIDO (no cuenta como intento ni se pierde) y el REFUND_REVERSED
    // posterior se REGISTRA en la base (REVERSAL_RECORDED: la base manda a revision el reembolso que llegue despues, S1). Al encender los
    // reembolsos el trabajo vuelve a listar los retenidos y aplica el REFUND (la base lo deja en revision, no debita).
    @Test
    fun aDeferredRefundIsRetainedWithoutCountingAttemptsAndAnEarlyReversalIsRecorded() = testApplication {
        upstream.handlers["app_store_refund_reversed"] = { SupabaseRawResponse(200, "\"REVERSAL_RECORDED\"") }
        upstream.handlers["app_store_refund_purchase"] = { SupabaseRawResponse(200, """[{"outcome":"EN_REVISION","orden_id":null,"estado":"en_revision","saldo":100}]""") }
        application { module(service(refunds = false)) }
        val client = createClient { }
        val refund = assn("REFUND", pki.sign(tx(revocation = now.toEpochMilli() - 500)), "r-1")
        val r1 = client.hook("app-store", """{"signedPayload":"$refund"}""")
        assertEquals("DEFERRED", result(r1)?.get("result")?.jsonPrimitive?.content)
        assertFalse("app_store_refund_purchase" in upstream.names())
        val mark = Json.parseToJsonElement(upstream.rpcs.last { it.first == "app_store_inbox_mark" }.second).jsonObject
        assertEquals("retenido", mark["p_estado"]?.jsonPrimitive?.content, "REFUNDS_DISABLED is a retained state, not a failed attempt")
        val reversed = assn("REFUND_REVERSED", pki.sign(tx()), "r-2")
        val r2 = client.hook("app-store", """{"signedPayload":"$reversed"}""")
        assertEquals(HttpStatusCode.OK, r2.status)
        assertEquals("REVERSAL_RECORDED", result(r2)?.get("result")?.jsonPrimitive?.content)
        // reembolsos encendidos: el trabajo pide tambien los retenidos y aplica el REFUND
        val refundRow = Json.parseToJsonElement(upstream.rpcs.first { it.first == "app_store_inbox_put" }.second).jsonObject
        upstream.handlers["app_store_inbox_pending"] = {
            SupabaseRawResponse(200, """[{"proveedor":"app_store","evento_id":"assn:r-1","tipo":"REEMBOLSO","clave_compra":"2000000001","entorno":"produccion","datos":${refundRow["p_datos"]},"intentos":0}]""")
        }
        upstream.rpcs.clear()
        assertEquals(1, service(refunds = true).reprocessPending())
        val pending = Json.parseToJsonElement(upstream.rpcs.first { it.first == "app_store_inbox_pending" }.second).jsonObject
        assertEquals("true", pending["p_incluir_retenidos"]?.jsonPrimitive?.content)
        assertTrue("app_store_refund_purchase" in upstream.names(), upstream.names().toString())
    }

    // A-K7: Apple consumible con quantity 2 (verify): rechazado sin tocar la base.
    @Test
    fun appleQuantityTwoIsRejectedBeforeTheDatabase() = testApplication {
        application { module(service()) }
        val r = createClient { }.post("/api/v1/app/store/purchases/verify") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); header("Idempotency-Key", "44444444-4444-4444-8444-444444444444"); contentType(ContentType.Application.Json)
            setBody("""{"platform":"APP_STORE","storeProductId":"com.oposibot.ot100","kind":"CONSUMABLE","token":"${pki.sign(tx(quantity = 2))}"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertFalse("app_store_credit_purchase" in upstream.names())
    }

    // A-K8: cuerpo de aviso > 256 KiB: 413 antes de autenticar o escribir nada (con y sin Content-Length).
    @Test
    fun anOversizedWebhookBodyIs413AndWritesNothing() = testApplication {
        application { module(service()) }
        val big = """{"signedPayload":"${"a".repeat(300 * 1024)}"}"""
        val r = createClient { }.hook("app-store", big)
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertTrue(upstream.rpcs.isEmpty())
    }
}
