package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pagos de tienda de punta a punta en Ktor (ruta -> servicio -> RPC) con proveedores falsos y un upstream que graba cada RPC. La semantica de la
 * base (credito una vez, ligado a un usuario, reembolso huerfano, ECO-04, suscripciones) la prueba `supabase/baseline/assertions.sql` en PG17.
 * Aqui: identidad, orden de las comprobaciones, "cero filas" en cada rechazo, bandeja antes del 2xx (ECO-02), finish de Play y reconciliacion.
 */
class StorePaymentRoutesTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val key = "11111111-1111-4111-8111-111111111111"

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
        fun args(function: String): JsonObject = Json.parseToJsonElement(rpcs.last { it.first == function }.second).jsonObject
    }

    private class FakeProvider(override val platform: StorePlatform) : StorePaymentProvider {
        var facts: StorePurchaseFacts? = null
        var event: StoreEvent? = null
        var authFails = false
        var factsFailure: Exception? = null
        var finishFails = false
        val finished = CopyOnWriteArrayList<String>()
        var verified = 0
        override suspend fun verify(storeProductId: String, kind: StoreKind, token: String): StorePurchaseFacts {
            verified++
            return facts!!
        }
        override suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant): StoreEvent {
            if (authFails) throw CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()
            return event!!
        }
        override suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject): StorePurchaseFacts? {
            factsFailure?.let { throw it }
            return facts
        }
        override suspend fun finish(kind: StoreKind, storeProductId: String, purchaseKey: String) {
            if (finishFails) throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
            finished += "$kind|$storeProductId|$purchaseKey"
        }
    }

    private val upstream = Upstream()
    private val google = FakeProvider(StorePlatform.GOOGLE_PLAY)
    private val apple = FakeProvider(StorePlatform.APP_STORE)
    private val catalog = StoreCatalog.parse("google_play:ot_100=PACK_100:CONSUMABLE,google_play:premium/monthly=PREMIUM_M:SUBSCRIPTION,app_store:com.x.ot100=PACK_100:CONSUMABLE")
    private var sandbox = StoreSandboxPolicy.parse("reject", null)
    private var refundsEnabled = true

    private fun service() = testStoreService(upstream, mapOf(StorePlatform.GOOGLE_PLAY to google, StorePlatform.APP_STORE to apple), catalog, sandbox, refundsEnabled, clock = { now })

    private fun pack(
        state: StorePurchaseState = StorePurchaseState.PURCHASED,
        quantity: Int = 1,
        env: StoreEnvironment = StoreEnvironment.PRODUCTION,
        token: String? = account,
        needsFinish: Boolean = true,
        platform: StorePlatform = StorePlatform.GOOGLE_PLAY,
        product: String = "ot_100",
    ) = StorePurchaseFacts(platform, StoreKind.CONSUMABLE, product, "gp-token-1", token, env, quantity, state, now.toEpochMilli(), null, needsFinish, mapOf("pedidoTienda" to "GPA.1"))

    private fun sub(estado: String, version: Long) = StorePurchaseFacts(
        StorePlatform.GOOGLE_PLAY, StoreKind.SUBSCRIPTION, "premium/monthly", "gp-sub-1", account, StoreEnvironment.PRODUCTION, 1, StorePurchaseState.PURCHASED, version,
        StoreSubscriptionFacts(estado, "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", null, null, null), needsFinish = false,
    )

    init {
        upstream.handlers["app_store_credit_purchase"] = { ok("""[{"outcome":"CREDITED","orden_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","estado":"pagada","saldo":100}]""") }
        upstream.handlers["app_store_entitlements"] = { ok("""[{"saldo":100,"es_premium":false,"estado_suscripcion":"sin_suscripcion","producto_codigo":null,"proveedor":null,"fin_en":null}]""") }
        upstream.handlers["app_store_account_token"] = { ok("\"$account\"") }
        upstream.handlers["app_store_inbox_put"] = { ok("""[{"nuevo":true,"estado":"pendiente","resultado":null,"misma_huella":true}]""") }
        upstream.handlers["app_store_inbox_mark"] = { ok("") }
        upstream.handlers["app_store_mark_finalized"] = { ok("true") }
        upstream.handlers["app_store_poll_get"] = { ok("[]") }
        upstream.handlers["app_store_poll_advance"] = { ok("1") }
        upstream.handlers["app_store_subscriptions_due"] = { ok("[]") }
        upstream.handlers["app_store_refund_purchase"] = { ok("""[{"outcome":"REFUNDED","orden_id":null,"estado":"reembolsada","saldo":0}]""") }
        upstream.handlers["app_store_refund_reversed"] = { ok("\"EN_REVISION\"") }
        upstream.handlers["app_store_upsert_subscription"] = { body ->
            val estado = Json.parseToJsonElement(body).jsonObject["p_estado"]!!.jsonPrimitive.content
            ok("""[{"outcome":"UPDATED","estado":"$estado","fin_en":"2026-11-01T00:00:00+00:00","es_premium":${estado == "activa"}}]""")
        }
    }

    private fun ok(body: String) = SupabaseRawResponse(200, body)

    private fun rpcError(message: String) = SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":null,"hint":null}""")

    private fun Application.module(svc: StorePaymentsService = service()) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { storePaymentRoutes(StorePaymentsRuntime(resolver, svc, InMemoryIdempotencyStore { now })) }
    }

    private suspend fun HttpClient.postJson(path: String, body: String, idem: String? = key, token: String? = TEST_TOKEN): HttpResponse = post(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        idem?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.getAs(path: String): HttpResponse = get(path) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }

    private fun verifyBody(product: String = "ot_100", kind: String = "CONSUMABLE", token: String = "gp-token-1", platform: String = "GOOGLE_PLAY", accountToken: String? = null) =
        """{"platform":"$platform","storeProductId":"$product","kind":"$kind","token":"$token"""" + (accountToken?.let { ""","accountToken":"$it"""" } ?: "") + "}"

    private fun code(response: String) = json.parseToJsonElement(response).jsonObject["code"]!!.jsonPrimitive.content

    private fun webhookEvent(type: StoreEventType, eventId: String = "rtdn:m-1", key: String? = "gp-token-1", env: StoreEnvironment? = null, kind: StoreKind = StoreKind.CONSUMABLE) =
        StoreEvent(StorePlatform.GOOGLE_PLAY, eventId, type, key, env, buildJsonObject { put("kind", kind.name) })

    private suspend fun HttpClient.webhook(platform: String = "google-play"): HttpResponse = post("/api/v1/webhooks/store/$platform") {
        header(HttpHeaders.Authorization, "Bearer oidc")
        contentType(ContentType.Application.Json)
        setBody("""{"message":{"data":"e30=","messageId":"m-1"}}""")
    }

    // ---------- S-03 verify ----------

    @Test
    fun verifyGrantsSendsOnlyJwtUserStoreTokenAndCatalogCodeAndConsumesAfterTheCredit() = testApplication {
        application { module() }
        google.facts = pack()
        val response = createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody())
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("GRANTED", body["result"]!!.jsonPrimitive.content)
        assertEquals("PACK_100", body["productCode"]!!.jsonPrimitive.content)
        assertEquals(100, body["balance"]!!.jsonPrimitive.content.toInt())
        val args = upstream.args("app_store_credit_purchase")
        assertEquals(TEST_UID, args["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(account, args["p_cuenta_token"]!!.jsonPrimitive.content)
        assertEquals("PACK_100", args["p_producto"]!!.jsonPrimitive.content)
        assertEquals("gp-token-1", args["p_clave"]!!.jsonPrimitive.content)
        assertEquals("produccion", args["p_entorno"]!!.jsonPrimitive.content)
        assertFalse(args.keys.any { it.contains("importe") || it.contains("precio") || it.contains("cantidad") }, "the client never decides amounts")
        // consume DESPUES del credito y solo entonces se marca terminado
        assertEquals(listOf("app_store_credit_purchase", "app_store_mark_finalized", "app_store_entitlements"), upstream.names())
        assertEquals(listOf("CONSUMABLE|ot_100|gp-token-1"), google.finished.toList())
    }

    @Test
    fun verifyNeedsAuthenticationAndAnIdempotencyKey() = testApplication {
        application { module() }
        google.facts = pack()
        val client = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/app/store/purchases/verify", verifyBody(), token = null).status)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(), idem = null).bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty() && google.verified == 0)
    }

    @Test
    fun anUnknownSkuOrKindIsRejectedBeforeAskingTheStoreAndWritesNothing() = testApplication {
        application { module() }
        google.facts = pack()
        val client = createClient { }
        assertEquals("STORE_PRODUCT_UNKNOWN", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(product = "ot_999")).bodyAsText()))
        assertEquals("STORE_PRODUCT_UNKNOWN", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(kind = "SUBSCRIPTION"), idem = "22222222-2222-4222-8222-222222222222").bodyAsText()))
        assertEquals("VALIDATION", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(platform = "STRIPE"), idem = "33333333-3333-4333-8333-333333333333").bodyAsText()))
        assertEquals(0, google.verified)
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun aPendingPurchaseIsNeitherGrantedNorFinished() = testApplication {
        application { module() }
        google.facts = pack(state = StorePurchaseState.PENDING)
        val response = createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody())
        assertEquals("PENDING", json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("app_store_entitlements"), upstream.names())
        assertTrue(google.finished.isEmpty())
    }

    @Test
    fun quantityAboveOneCancelledOrRevokedPurchasesAreRejectedWithZeroWrites() = testApplication {
        application { module() }
        val client = createClient { }
        listOf(pack(quantity = 2), pack(state = StorePurchaseState.CANCELLED), pack(state = StorePurchaseState.REVOKED)).forEachIndexed { i, facts ->
            google.facts = facts
            assertEquals("STORE_PURCHASE_INVALID", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(), idem = "4444444$i-4444-4444-8444-444444444444").bodyAsText()))
        }
        assertTrue(upstream.rpcs.isEmpty())
        assertTrue(google.finished.isEmpty())
    }

    @Test
    fun aMissingOrForeignAccountTokenIsRejectedWithZeroWrites() = testApplication {
        application { module() }
        val client = createClient { }
        google.facts = pack(token = null)
        assertEquals("STORE_ACCOUNT_MISMATCH", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody()).bodyAsText()))
        google.facts = pack(token = "not-a-uuid")
        assertEquals("STORE_ACCOUNT_MISMATCH", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(), idem = "55555555-5555-4555-8555-555555555555").bodyAsText()))
        google.facts = pack()
        assertEquals("STORE_ACCOUNT_MISMATCH", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(accountToken = "9f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"), idem = "66666666-6666-4666-8666-666666666666").bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun theDatabaseDecidesTheBindingTokenOfAnotherUserOrAlreadyBoundIs409() = testApplication {
        application { module() }
        google.facts = pack()
        upstream.handlers["app_store_credit_purchase"] = { rpcError("STORE_PURCHASE_BOUND_TO_OTHER") }
        val response = createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody())
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("STORE_PURCHASE_BOUND_TO_OTHER", code(response.bodyAsText()))
        upstream.handlers["app_store_credit_purchase"] = { rpcError("STORE_ACCOUNT_MISMATCH") }
        assertEquals("STORE_ACCOUNT_MISMATCH", code(createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody(), idem = "77777777-7777-4777-8777-777777777777").bodyAsText()))
        assertTrue(google.finished.isEmpty(), "nothing is consumed for a refused purchase")
    }

    @Test
    fun aPurchaseHeldForReviewIsAConflictAndIsNotConsumed() = testApplication {
        application { module() }
        google.facts = pack()
        upstream.handlers["app_store_credit_purchase"] = { ok("""[{"outcome":"EN_REVISION","orden_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","estado":"en_revision","saldo":0}]""") }
        assertEquals("STORE_PURCHASE_IN_REVIEW", code(createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody()).bodyAsText()))
        assertTrue(google.finished.isEmpty())
    }

    @Test
    fun sandboxIsRejectedInProductionUnlessTheUserIsAllowlisted() = testApplication {
        google.facts = pack(env = StoreEnvironment.SANDBOX)
        application { module() }
        val client = createClient { }
        assertEquals("STORE_SANDBOX_REJECTED", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody()).bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun anAllowlistedTesterMayBuyInSandboxAndItIsMarkedSandbox() = testApplication {
        sandbox = StoreSandboxPolicy.parse("allowlist", TEST_UID)
        google.facts = pack(env = StoreEnvironment.SANDBOX)
        application { module() }
        assertEquals(HttpStatusCode.OK, createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody()).status)
        assertEquals("sandbox", upstream.args("app_store_credit_purchase")["p_entorno"]!!.jsonPrimitive.content)
    }

    @Test
    fun aConsumeFailureStillGrantsAndLeavesThePurchaseForReconciliation() = testApplication {
        google.facts = pack()
        google.finishFails = true
        val svc = service()
        application { module(svc) }
        val response = createClient { }.postJson("/api/v1/app/store/purchases/verify", verifyBody())
        assertEquals("GRANTED", json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertFalse("app_store_mark_finalized" in upstream.names(), "not marked: the job retries it")
        // reconciliacion: el trabajo consume y marca
        google.finishFails = false
        upstream.handlers["app_store_pending_finalize"] = { ok("""[{"tipo":"compra","proveedor":"google_play","clave":"gp-token-1","tienda_producto_id":"ot_100","creado_en":"2026-10-19T12:00:00+00:00"}]""") }
        assertEquals(1, svc.finishPending())
        assertEquals(listOf("CONSUMABLE|ot_100|gp-token-1"), google.finished.toList())
        assertEquals("compra", upstream.args("app_store_mark_finalized")["p_tipo"]!!.jsonPrimitive.content)
    }

    @Test
    fun aStoreKit2JwsOfSeveralKilobytesIsAcceptedButTheTokenIsBounded() = testApplication {
        application { module() }
        apple.facts = pack(platform = StorePlatform.APP_STORE, product = "com.x.ot100", needsFinish = false)
        val client = createClient { }
        val jws = "a".repeat(20 * 1024)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/app/store/purchases/verify", verifyBody(platform = "APP_STORE", product = "com.x.ot100", token = jws)).status)
        val tooLong = "a".repeat(StorePaymentsService.MAX_STORE_TOKEN_LENGTH + 1)
        assertEquals("VALIDATION", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(platform = "APP_STORE", product = "com.x.ot100", token = tooLong), idem = "88888888-8888-4888-8888-888888888888").bodyAsText()))
    }

    @Test
    fun theSameKeyAndBodyReplaysTheAnswerWithoutTouchingTheStoreAgain() = testApplication {
        application { module() }
        google.facts = pack()
        val client = createClient { }
        client.postJson("/api/v1/app/store/purchases/verify", verifyBody())
        val second = client.postJson("/api/v1/app/store/purchases/verify", verifyBody())
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, google.verified)
        assertEquals(1, upstream.names().count { it == "app_store_credit_purchase" })
    }

    @Test
    fun subscriptionVerifyGrantsWithPremiumAndRefusesWithoutAccess() = testApplication {
        application { module() }
        upstream.handlers["app_store_entitlements"] = { ok("""[{"saldo":0,"es_premium":true,"estado_suscripcion":"activa","producto_codigo":"PREMIUM_M","proveedor":"google_play","fin_en":"2026-11-01T00:00:00+00:00"}]""") }
        google.facts = sub("activa", now.toEpochMilli())
        val client = createClient { }
        val response = client.postJson("/api/v1/app/store/purchases/verify", verifyBody(product = "premium", kind = "SUBSCRIPTION", token = "gp-sub-1"))
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("GRANTED", body["result"]!!.jsonPrimitive.content, response.bodyAsText())
        assertEquals("PREMIUM_M", body["productCode"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01T00:00:00Z", body["premiumUntil"]!!.jsonPrimitive.content)
        assertEquals("PREMIUM_M", upstream.args("app_store_upsert_subscription")["p_producto"]!!.jsonPrimitive.content)
        google.facts = sub("caducada", now.toEpochMilli())
        assertEquals("STORE_PURCHASE_INVALID", code(client.postJson("/api/v1/app/store/purchases/verify", verifyBody(product = "premium", kind = "SUBSCRIPTION", token = "gp-sub-1"), idem = "99999999-9999-4999-8999-999999999999").bodyAsText()))
    }

    @Test
    fun restoreAnswersPerItemAndAFailingItemDoesNotStopTheOthers() = testApplication {
        application { module() }
        google.facts = pack()
        val response = createClient { }.postJson(
            "/api/v1/app/store/purchases/restore",
            """{"platform":"GOOGLE_PLAY","purchases":[{"storeProductId":"ot_100","kind":"CONSUMABLE","token":"t1"},{"storeProductId":"nope","kind":"CONSUMABLE","token":"t2"}]}""",
            idem = null,
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val items = json.parseToJsonElement(response.bodyAsText()).jsonObject["items"].toString()
        assertTrue(items.contains("\"GRANTED\"") && items.contains("STORE_PRODUCT_UNKNOWN"), items)
    }

    @Test
    fun accountTokenProductsAndEntitlementsAreServed() = testApplication {
        application { module() }
        val client = createClient { }
        assertEquals(account, json.parseToJsonElement(client.getAs("/api/v1/app/store/account-token").bodyAsText()).jsonObject["accountToken"]!!.jsonPrimitive.content)
        val products = client.getAs("/api/v1/app/store/products").bodyAsText()
        assertTrue(products.contains("\"basePlanId\":\"monthly\"") && products.contains("\"storeProductId\":\"premium\""), products)
        assertEquals(100, json.parseToJsonElement(client.getAs("/api/v1/app/store/entitlements").bodyAsText()).jsonObject["balance"]!!.jsonPrimitive.content.toInt())
    }

    // ---------- W-02 / W-03 avisos ----------

    @Test
    fun aWebhookWithBadAuthenticationIs401AndWritesNothing() = testApplication {
        application { module() }
        google.authFails = true
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.webhook().status)
        assertTrue(upstream.rpcs.isEmpty())
        assertEquals(HttpStatusCode.NotFound, createClient { }.webhook("stripe").status)
    }

    @Test
    fun aWebhookIsStoredInTheInboxBeforeItIsProcessedAndAcknowledged() = testApplication {
        application { module() }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        google.facts = pack(needsFinish = false)
        val response = createClient { }.webhook()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("app_store_inbox_put", "app_store_credit_purchase", "app_store_inbox_mark"), upstream.names())
        assertTrue(upstream.args("app_store_inbox_put")["p_huella"]!!.jsonPrimitive.content.matches(Regex("^[0-9a-f]{64}$")))
        assertNull(upstream.args("app_store_credit_purchase")["p_usuario"]?.jsonPrimitive?.contentOrNullSafe(), "a webhook has no JWT user: the DB resolves the owner")
        assertEquals("procesado", upstream.args("app_store_inbox_mark")["p_estado"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSameNotificationRetriedIsAReplayAndProcessesNothing() = testApplication {
        application { module() }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        upstream.handlers["app_store_inbox_put"] = { ok("""[{"nuevo":false,"estado":"procesado","resultado":"GRANTED","misma_huella":true}]""") }
        val response = createClient { }.webhook()
        assertEquals("REPLAY", json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("app_store_inbox_put"), upstream.names())
    }

    @Test
    fun aRefundWithRefundsSwitchedOffIsPersistedAnd200NeverA503WithoutRecord() = testApplication {
        refundsEnabled = false
        application { module() }
        google.event = webhookEvent(StoreEventType.REFUND)
        val response = createClient { }.webhook()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("DEFERRED", json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("app_store_inbox_put", "app_store_inbox_mark"), upstream.names())
        val mark = upstream.args("app_store_inbox_mark")
        assertEquals("retenido", mark["p_estado"]!!.jsonPrimitive.content, "F4: retained, not a failed attempt")
        assertEquals("REFUNDS_DISABLED", mark["p_error"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefundBeforeThePaymentIsHeldAsAnOrphanAndAVoidAfterAccountDeletionToo() = testApplication {
        application { module() }
        google.event = webhookEvent(StoreEventType.REFUND, key = "gp-token-2")
        upstream.handlers["app_store_refund_purchase"] = { ok("""[{"outcome":"REFUND_ORPHAN_HELD","orden_id":null,"estado":null,"saldo":null}]""") }
        val response = createClient { }.webhook()
        assertEquals("REFUND_ORPHAN_HELD", json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        val args = upstream.args("app_store_refund_purchase")
        assertEquals("gp-token-2", args["p_clave"]!!.jsonPrimitive.content)
        assertEquals("rtdn:m-1", args["p_evento_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aStoreOutageWhileProcessingLeavesTheEventPendingAndTheJobRetriesIt() = testApplication {
        val svc = service()
        application { module(svc) }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        google.factsFailure = CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        val response = createClient { }.webhook()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("pendiente", upstream.args("app_store_inbox_mark")["p_estado"]!!.jsonPrimitive.content)
        assertFalse("app_store_credit_purchase" in upstream.names())
        google.factsFailure = null
        google.facts = pack(needsFinish = false)
        upstream.handlers["app_store_inbox_pending"] = { ok("""[{"proveedor":"google_play","evento_id":"rtdn:m-1","tipo":"COMPRA","clave_compra":"gp-token-1","entorno":null,"datos":{"kind":"CONSUMABLE"},"intentos":1}]""") }
        assertEquals(1, svc.reprocessPending())
        assertTrue("app_store_credit_purchase" in upstream.names())
        assertEquals("procesado", upstream.args("app_store_inbox_mark")["p_estado"]!!.jsonPrimitive.content)
    }

    @Test
    fun whenTheInboxItselfFailsTheStoreGetsA5xxSoItRetries() = testApplication {
        application { module() }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        upstream.handlers["app_store_inbox_put"] = { throw IllegalStateException("db down") }
        assertEquals(HttpStatusCode.ServiceUnavailable, createClient { }.webhook().status)
    }

    @Test
    fun aSandboxNotificationInProductionIsRecordedButNotApplied() = testApplication {
        application { module() }
        google.event = webhookEvent(StoreEventType.PURCHASE, env = StoreEnvironment.SANDBOX)
        val response = createClient { }.webhook()
        assertEquals("SANDBOX_IGNORED", json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertFalse("app_store_credit_purchase" in upstream.names())
        google.event = webhookEvent(StoreEventType.PURCHASE, eventId = "rtdn:m-2")
        google.facts = pack(env = StoreEnvironment.SANDBOX)   // Google solo sabe el entorno tras releer la API
        assertEquals("SANDBOX_IGNORED", json.parseToJsonElement(createClient { }.webhook().bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        assertFalse("app_store_credit_purchase" in upstream.names())
    }

    @Test
    fun subscriptionGraceThenHoldThenRecoveryAreAppliedInOrder() = testApplication {
        application { module() }
        val client = createClient { }
        listOf("activa" to 1L, "pausada" to 2L, "activa" to 3L).forEachIndexed { i, (estado, version) ->
            google.event = webhookEvent(StoreEventType.SUBSCRIPTION, eventId = "rtdn:s-$i", key = "gp-sub-1", kind = StoreKind.SUBSCRIPTION)
            google.facts = sub(estado, version)
            assertEquals(HttpStatusCode.OK, client.webhook().status)
            val args = upstream.args("app_store_upsert_subscription")
            assertEquals(estado, args["p_estado"]!!.jsonPrimitive.content)
            assertEquals(version, args["p_version"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun refundReversedOnAPackGoesToReviewAndConsumptionRequestsSendNothing() = testApplication {
        application { module() }
        val client = createClient { }
        google.event = webhookEvent(StoreEventType.REFUND_REVERSED)
        assertEquals("EN_REVISION", json.parseToJsonElement(client.webhook().bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
        google.event = webhookEvent(StoreEventType.CONSUMPTION_REQUEST, eventId = "rtdn:c-1")
        assertEquals("RECORDED_NO_CONSENT", json.parseToJsonElement(client.webhook().bodyAsText()).jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun theVoidedPurchasesPollFeedsTheInboxLikeANotification() = runTest {
        val api = StoreGooglePlayProviderTest.FakeApi().apply {
            voidedPages = listOf(Json.parseToJsonElement("""{"voidedPurchases":[{"purchaseToken":"gp-token-1","orderId":"GPA.7"}]}""").jsonObject)
        }
        val provider = GooglePlayProvider(GooglePlayConfig("p", "f", "a", "s", "projects/p/subscriptions/rtdn"), api, GoogleOidcVerifier("a", "s", GoogleTestKeys().source), clock = { now })
        val svc = testStoreService(upstream, mapOf(StorePlatform.GOOGLE_PLAY to provider), catalog, sandbox, true, clock = { now })
        val runtime = StorePaymentsRuntime(resolver, svc, InMemoryIdempotencyStore { now }, google = provider)
        assertEquals(1, StorePaymentsJob.pollVoided(runtime, now))
        assertEquals("void:GPA.7", upstream.args("app_store_inbox_put")["p_evento_id"]!!.jsonPrimitive.content)
        assertEquals("REEMBOLSO", upstream.args("app_store_inbox_put")["p_tipo"]!!.jsonPrimitive.content)
        assertTrue("app_store_refund_purchase" in upstream.names())
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content

    /** F4: un aviso ya RETENIDO que la tienda reenvia se vuelve a procesar (no es un REPLAY), y la metrica de atasco se publica. */
    @Test
    fun aRetainedNotificationRedeliveredIsReprocessedAndTheBacklogIsMeasured() = runTest {
        upstream.handlers["app_store_inbox_put"] = { ok("""[{"nuevo":false,"estado":"retenido","resultado":null,"misma_huella":true,"reclamo":"c-1"}]""") }
        google.event = webhookEvent(StoreEventType.REFUND)
        assertEquals("REFUNDED", service().webhook(StorePlatform.GOOGLE_PLAY, { null }, "{}".toByteArray()))
        assertTrue("app_store_refund_purchase" in upstream.names())
        upstream.handlers["app_store_inbox_stats"] = { ok("""[{"estado":"retenido","n":3,"mas_antiguo":"2026-10-19T11:00:00+00:00","max_intentos":0}]""") }
        val alerts = CopyOnWriteArrayList<String>()
        StoreInboxMetrics.update(service().inboxStats(), { alerts += it })
        assertEquals(3L, StoreInboxMetrics.value(StoreInboxMetrics.BACKLOG_METRIC, "retenido"))
        assertEquals(3600L, StoreInboxMetrics.value(StoreInboxMetrics.OLDEST_METRIC, "retenido"))
        assertEquals(0L, StoreInboxMetrics.value(StoreInboxMetrics.BACKLOG_METRIC, "pendiente"))
        assertTrue(alerts.single().startsWith("ALERT store inbox: 3 refund notifications retained"), alerts.toString())
        val pending = upstream.args("app_store_inbox_stats")
        assertTrue(pending.isEmpty())
    }

    /** F4 + N4-2: con reembolsos encendidos el trabajo pide tambien los retenidos por reembolsos; apagados, no (cada motivo con su interruptor). */
    @Test
    fun theJobAsksForRetainedRowsOnlyWhenRefundsAreOn() = runTest {
        upstream.handlers["app_store_inbox_pending"] = { ok("[]") }
        service().reprocessPending()
        assertTrue(upstream.args("app_store_inbox_pending")["p_retenidos"].toString().contains("\"REFUNDS_DISABLED\""))
        refundsEnabled = false
        service().reprocessPending()
        assertFalse(upstream.args("app_store_inbox_pending")["p_retenidos"].toString().contains("\"REFUNDS_DISABLED\""))
    }

    /** NEW-3 (re-auditoria Control 2026-10-19): un reenvio mientras OTRO trabajador tiene el aviso reclamado no se procesa en paralelo. */
    @Test
    fun aRedeliveryWhileAnotherWorkerHoldsTheClaimIsNotProcessedTwice() = runTest {
        upstream.handlers["app_store_inbox_put"] = { ok("""[{"nuevo":false,"estado":"pendiente","resultado":null,"misma_huella":true,"reclamo":null}]""") }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        google.facts = pack(needsFinish = false)
        assertEquals("IN_PROGRESS", service().webhook(StorePlatform.GOOGLE_PLAY, { null }, "{}".toByteArray()))
        assertEquals(listOf("app_store_inbox_put"), upstream.names())
    }

    /** NEW-3: el reclamo de la bandeja viaja hasta el mark; el trabajo renueva el reclamo de cada fila antes de procesarla y salta las perdidas. */
    @Test
    fun theClaimTravelsToTheMarkAndTheJobRenewsItPerRow() = runTest {
        upstream.handlers["app_store_inbox_put"] = { ok("""[{"nuevo":true,"estado":"pendiente","resultado":null,"misma_huella":true,"reclamo":"c-7"}]""") }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        google.facts = pack(needsFinish = false)
        service().webhook(StorePlatform.GOOGLE_PLAY, { null }, "{}".toByteArray())
        assertEquals("c-7", upstream.args("app_store_inbox_mark")["p_reclamo"]!!.jsonPrimitive.content)
        upstream.rpcs.clear()
        upstream.handlers["app_store_inbox_pending"] = {
            ok("""[{"proveedor":"google_play","evento_id":"rtdn:a","tipo":"COMPRA","clave_compra":"gp-token-1","entorno":null,"datos":{"kind":"CONSUMABLE"},"intentos":0,"reclamo":"c-a"},""" +
                """{"proveedor":"google_play","evento_id":"rtdn:b","tipo":"COMPRA","clave_compra":"gp-token-1","entorno":null,"datos":{"kind":"CONSUMABLE"},"intentos":0,"reclamo":"c-b"}]""")
        }
        upstream.handlers["app_store_inbox_renew"] = { body -> ok(if (body.contains("\"c-a\"")) "true" else "false") }
        assertEquals(2, service().reprocessPending())
        assertEquals(1, upstream.names().count { it == "app_store_credit_purchase" }, "the row whose claim was lost is skipped: ${upstream.names()}")
        assertEquals("c-a", upstream.args("app_store_inbox_mark")["p_reclamo"]!!.jsonPrimitive.content)
    }

    /** NEW-4 / NEW-1 / NEW-2: dead letter tras N intentos solo si se configura; mantenimiento: re-escaneo de huerfanos siempre, purga solo con retencion. */
    @Test
    fun deadLetterThresholdOrphanRescanAndRetentionAreConfiguredNotImplicit() = runTest {
        upstream.handlers["app_store_inbox_put"] = { ok("""[{"nuevo":true,"estado":"pendiente","resultado":null,"misma_huella":true,"reclamo":"c-1"}]""") }
        google.event = webhookEvent(StoreEventType.PURCHASE)
        google.factsFailure = CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        testStoreService(upstream, mapOf(StorePlatform.GOOGLE_PLAY to google), catalog, sandbox, true, clock = { now }, deadLetterAttempts = 12)
            .webhook(StorePlatform.GOOGLE_PLAY, { null }, "{}".toByteArray())
        assertEquals("12", upstream.args("app_store_inbox_mark")["p_max_intentos"]!!.jsonPrimitive.content)
        service().webhook(StorePlatform.GOOGLE_PLAY, { null }, "{}".toByteArray())
        assertEquals("0", upstream.args("app_store_inbox_mark")["p_max_intentos"]!!.jsonPrimitive.content, "default: never dead-letter")
        upstream.rpcs.clear()
        upstream.handlers["app_store_reconcile_orphan_step"] = { ok("[]") }
        upstream.handlers["app_store_inbox_purge"] = { ok("0") }
        refundsEnabled = false
        service().maintenance(retentionDays = null)
        assertEquals(listOf("app_store_reconcile_orphan_step"), upstream.names(), "no retention configured: nothing is deleted")
        assertEquals("false", upstream.args("app_store_reconcile_orphan_step")["p_reembolsos_habilitados"]!!.jsonPrimitive.content)
        service().maintenance(retentionDays = 400)
        assertEquals("400", upstream.args("app_store_inbox_purge")["p_dias"]!!.jsonPrimitive.content)
    }
}
