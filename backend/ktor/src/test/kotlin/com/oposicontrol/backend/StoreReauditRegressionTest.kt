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
 * Regresion de la RE-auditoria de OposiControl 2026-10-19 (ReauditStoreAdversarialTest.kt, R-K1..R-K5) con los proveedores REALES de Google /
 * Apple. R-K3 confirmaba un defecto (NEW-7: la huella se calculaba sobre el fichero y un PEM de la raiz fijada se rechazaba) y esta INVERTIDO.
 */
class StoreReauditRegressionTest {
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


    // ===================== RE-AUDIT OposiControl 2026-10-19 =====================
    private val down = OcspDownPki()
    private fun onlineService(): StorePaymentsService {
        val apple = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), onlineChecks = true), listOf(down.root.encoded))
        return testStoreService(upstream, mapOf(StorePlatform.APP_STORE to apple), catalog, StoreSandboxPolicy.parse("reject", null), true, clock = { now })
    }

    // R-K1 (F6 e2e): con comprobacion OCSP y una cadena sin respondedor OCSP el estado de revocacion es INDETERMINADO -> 503 en el aviso, cero RPC.
    @Test
    fun rk1AppleWebhookWithUndeterminedRevocationIs503AndWritesNothing() = testApplication {
        application { module(onlineService()) }
        val signed = down.sign("""{"notificationType":"ONE_TIME_CHARGE","notificationUUID":"ocsp-1","version":"2.0","signedDate":${now.toEpochMilli()},"data":{"environment":"Production","bundleId":"$bundle","appAppleId":$appAppleId,"signedTransactionInfo":"${down.sign(tx())}"}}""")
        val r = createClient { }.hook("app-store", """{"signedPayload":"$signed"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(upstream.rpcs.isEmpty(), "zero RPCs: ${upstream.names()}")
    }

    // R-K2 (F6 e2e): lo mismo en verify -> 503, nunca STORE_PURCHASE_INVALID, nunca credito.
    @Test
    fun rk2AppleVerifyWithUndeterminedRevocationIs503() = testApplication {
        application { module(onlineService()) }
        val r = createClient { }.post("/api/v1/app/store/purchases/verify") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); header("Idempotency-Key", "55555555-5555-4555-8555-555555555555"); contentType(ContentType.Application.Json)
            setBody("""{"platform":"APP_STORE","storeProductId":"com.oposibot.ot100","kind":"CONSUMABLE","token":"${down.sign(tx())}"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertFalse("app_store_credit_purchase" in upstream.names())
    }

    private fun appleEnv(rootPath: String) = mapOf(
        STORE_APPLE_ENABLED_ENV to "true", "PAYMENTS_APPLE_BUNDLE_ID" to "com.x", "PAYMENTS_APPLE_APP_APPLE_ID" to "123", "PAYMENTS_APPLE_ROOT_CA_PATHS" to rootPath,
        "PAYMENTS_STORE_PRODUCTS" to "app_store:com.x.ot100=PACK_100:CONSUMABLE",
    )

    // R-K3 (F8 + NEW-7, INVERTIDO): una lista con la raiz fijada + otra raiz cualquiera NO arranca; el mismo certificado fijado en PEM SI arranca
    // (la huella se calcula sobre el DER del certificado, no sobre los bytes del fichero).
    @Test
    fun rk3PinnedRootPlusAForeignRootRefusesAndPemOfThePinnedRootStarts() {
        val pinned = StoreTestPki().rootDer
        val pins = setOf(sha256Hex(pinned))
        val good = java.io.File.createTempFile("pinned", ".cer").apply { writeBytes(pinned); deleteOnExit() }
        val foreign = java.io.File.createTempFile("foreign", ".cer").apply { writeBytes(StoreTestPki().rootDer); deleteOnExit() }
        val ok = runCatching { StorePaymentsSettings.fromEnvironment(appleEnv(good.path)::get, pins) }
        assertTrue(ok.isSuccess, "pinned DER root starts: ${ok.exceptionOrNull()}")
        val mixed = runCatching { StorePaymentsSettings.fromEnvironment(appleEnv("${good.path},${foreign.path}")::get, pins) }.exceptionOrNull()
        assertTrue(mixed is StoreConfigException && mixed.message.orEmpty().contains("huella"), "mixed list must refuse: $mixed")
        val pem = java.io.File.createTempFile("pinned", ".pem").apply {
            writeText("-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pinned) + "\n-----END CERTIFICATE-----\n"); deleteOnExit()
        }
        val pemResult = runCatching { StorePaymentsSettings.fromEnvironment(appleEnv(pem.path)::get, pins) }.exceptionOrNull()
        assertTrue(pemResult == null, "NEW-7: the PEM of the pinned root starts (pin over the parsed DER): $pemResult")
        // y el default de produccion (sin override de pins) rechaza la raiz de pruebas
        assertTrue(runCatching { StorePaymentsSettings.fromEnvironment(appleEnv(good.path)::get) }.exceptionOrNull() is StoreConfigException)
    }

    // R-K4 (F7): suscripcion en blanco = ausente (no arranca); un push autentico SIN campo subscription es 401, cero RPC.
    @Test
    fun rk4BlankSubscriptionRefusesAndAPushWithoutSubscriptionIs401() = testApplication {
        val sa = java.io.File.createTempFile("svc-account", ".json").apply { writeText("{}"); deleteOnExit() }
        val failure = runCatching {
            StorePaymentsSettings.fromEnvironment(
                mapOf(
                    STORE_GOOGLE_ENABLED_ENV to "true", "PAYMENTS_GOOGLE_PACKAGE_NAME" to "com.x", "PAYMENTS_GOOGLE_PUBSUB_AUDIENCE" to "https://a",
                    "PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT" to "s@x", "PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE" to sa.path, "PAYMENTS_GOOGLE_PUBSUB_SUBSCRIPTION" to "   ",
                    "PAYMENTS_STORE_PRODUCTS" to "google_play:ot_100=PACK_100:CONSUMABLE",
                )::get,
            )
        }.exceptionOrNull()
        assertTrue(failure is StoreConfigException && failure.message.orEmpty().contains("PAYMENTS_GOOGLE_PUBSUB_SUBSCRIPTION"), "$failure")
        application { module(service()) }
        val noSub = """{"message":{"data":"${Base64.getEncoder().encodeToString(rtdnTest.toByteArray())}","messageId":"m-2"}}"""
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.hook("google-play", noSub, keys.token()).status)
        assertTrue(upstream.rpcs.isEmpty(), "zero RPCs: ${upstream.names()}")
    }

    // R-K5 (F12 contrato Ktor<->DB): un REFUND_REVERSED huerfano queda marcado en la bandeja con resultado EXACTO REVERSAL_ORPHAN_HELD
    // (app_store_credit_purchase lo busca por ese literal); y un aviso retenido reenviado con reembolsos apagados vuelve a 'retenido'.
    @Test
    fun rk5OrphanReversalMarkLiteralAndRetainedRedelivery() = testApplication {
        upstream.handlers["app_store_refund_reversed"] = { SupabaseRawResponse(200, "\"REVERSAL_ORPHAN_HELD\"") }
        application { module(service(refunds = false)) }
        val client = createClient { }
        val reversed = assn("REFUND_REVERSED", pki.sign(tx()), "o-1")
        assertEquals("REVERSAL_ORPHAN_HELD", result(client.hook("app-store", """{"signedPayload":"$reversed"}"""))?.get("result")?.jsonPrimitive?.content)
        val mark = Json.parseToJsonElement(upstream.rpcs.last { it.first == "app_store_inbox_mark" }.second).jsonObject
        assertEquals("procesado", mark["p_estado"]?.jsonPrimitive?.content)
        assertEquals("REVERSAL_ORPHAN_HELD", mark["p_resultado"]?.jsonPrimitive?.content)
        // NEW-3: la base reclama la fila retenida para este reenvio (devuelve su reclamo); sin reclamo seria IN_PROGRESS (otro la tiene).
        upstream.handlers["app_store_inbox_put"] = { SupabaseRawResponse(200, """[{"nuevo":false,"estado":"retenido","resultado":null,"misma_huella":true,"reclamo":"c-1"}]""") }
        upstream.rpcs.clear()
        val refund = assn("REFUND", pki.sign(tx(revocation = now.toEpochMilli() - 500)), "o-2")
        assertEquals("DEFERRED", result(client.hook("app-store", """{"signedPayload":"$refund"}"""))?.get("result")?.jsonPrimitive?.content)
        val mark2 = Json.parseToJsonElement(upstream.rpcs.last { it.first == "app_store_inbox_mark" }.second).jsonObject
        assertEquals("retenido", mark2["p_estado"]?.jsonPrimitive?.content)
        assertFalse("app_store_refund_purchase" in upstream.names())
    }

}

/** PKI de prueba cuyos certificados anuncian un respondedor OCSP en LOOPBACK cerrado (127.0.0.1:9): fallo de red = revocacion indeterminada. */
internal class OcspDownPki {
    private fun ec() = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var serial = 1000L
    private fun cert(subject: String, keys: java.security.KeyPair, issuer: String, issuerKeys: java.security.KeyPair, ca: Boolean, oid: String?, aia: Boolean): java.security.cert.X509Certificate {
        val n = Instant.now()
        val b = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(org.bouncycastle.asn1.x500.X500Name(issuer), java.math.BigInteger.valueOf(serial++),
            Date.from(n.minusSeconds(86_400)), Date.from(n.plusSeconds(86_400)), org.bouncycastle.asn1.x500.X500Name(subject), keys.public)
        b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, org.bouncycastle.asn1.x509.BasicConstraints(ca))
        b.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
            org.bouncycastle.asn1.x509.KeyUsage(if (ca) org.bouncycastle.asn1.x509.KeyUsage.keyCertSign or org.bouncycastle.asn1.x509.KeyUsage.cRLSign else org.bouncycastle.asn1.x509.KeyUsage.digitalSignature))
        oid?.let { b.addExtension(org.bouncycastle.asn1.ASN1ObjectIdentifier(it), false, org.bouncycastle.asn1.DERNull.INSTANCE) }
        if (aia) b.addExtension(org.bouncycastle.asn1.x509.Extension.authorityInfoAccess, false, org.bouncycastle.asn1.x509.AuthorityInformationAccess(
            org.bouncycastle.asn1.x509.AccessDescription(org.bouncycastle.asn1.x509.AccessDescription.id_ad_ocsp,
                org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier, "http://127.0.0.1:9/ocsp"))))
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(b.build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)))
    }
    private val rootKeys = ec()
    val root = cert("CN=Down Root", rootKeys, "CN=Down Root", rootKeys, true, null, false)
    private val intKeys = ec()
    private val intermediate = cert("CN=Down WWDR", intKeys, "CN=Down Root", rootKeys, true, StoreTestPki.WWDR_OID, true)
    private val leafKeys = ec()
    private val leaf = cert("CN=Down Signer", leafKeys, "CN=Down WWDR", intKeys, false, StoreTestPki.SIGNER_OID, true)
    fun sign(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val x5c = listOf(leaf, intermediate, root).joinToString(",") { "\"" + Base64.getEncoder().encodeToString(it.encoded) + "\"" }
        val h = enc.encodeToString("""{"alg":"ES256","x5c":[$x5c]}""".toByteArray())
        val p = enc.encodeToString(payload.toByteArray())
        return "$h.$p." + enc.encodeToString(com.auth0.jwt.algorithms.Algorithm.ECDSA256(null, leafKeys.private as java.security.interfaces.ECPrivateKey).sign(h.toByteArray(), p.toByteArray()))
    }
}
