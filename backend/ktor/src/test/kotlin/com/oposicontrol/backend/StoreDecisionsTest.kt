package com.oposicontrol.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decisiones abiertas (StoreDecisions.kt, docs/payments/DECISIONS_PENDING.md): los defectos CONSERVADORES nunca acreditan de mas, la configuracion
 * que pide algo no implementado no arranca, y el documento lista cada decision con su clave.
 */
class StoreDecisionsTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val user = CanonicalUserId.fromJwtSubject("a0000000-0000-4000-8000-000000000001")

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return when (function) {
                "app_store_credit_purchase" -> SupabaseRawResponse(200, """[{"outcome":"CREDITED","orden_id":null,"estado":"pagada","saldo":100}]""")
                "app_store_upsert_subscription" -> SupabaseRawResponse(200, """[{"outcome":"UPDATED","estado":"caducada","fin_en":null,"es_premium":false}]""")
                "app_store_entitlements" -> SupabaseRawResponse(200, """[{"saldo":100,"es_premium":false,"estado_suscripcion":"sin_suscripcion"}]""")
                "app_store_inbox_put" -> SupabaseRawResponse(200, """[{"nuevo":true,"estado":"pendiente","resultado":null,"misma_huella":true,"reclamo":"c1"}]""")
                else -> SupabaseRawResponse(200, "true")
            }
        }
        fun names() = rpcs.map { it.first }
    }

    private class Provider(var facts: StorePurchaseFacts, var event: StoreEvent? = null) : StorePaymentProvider {
        override val platform = StorePlatform.GOOGLE_PLAY
        override suspend fun verify(storeProductId: String, kind: StoreKind, token: String) = facts
        override suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant) = event!!
        override suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject) = facts
        override suspend fun finish(kind: StoreKind, storeProductId: String, purchaseKey: String) = Unit
    }

    private val catalog = StoreCatalog.parse("google_play:ot_100=PACK_100:CONSUMABLE,google_play:premium/monthly=PREMIUM_M:SUBSCRIPTION")

    private fun pack(env: StoreEnvironment = StoreEnvironment.PRODUCTION) =
        StorePurchaseFacts(StorePlatform.GOOGLE_PLAY, StoreKind.CONSUMABLE, "ot_100", "gp-1", account, env, 1, StorePurchaseState.PURCHASED, now.toEpochMilli(), needsFinish = false)

    private fun sub(estado: String, motivo: String? = null) = StorePurchaseFacts(
        StorePlatform.GOOGLE_PLAY, StoreKind.SUBSCRIPTION, "premium/monthly", "gp-sub", account, StoreEnvironment.PRODUCTION, 1, StorePurchaseState.PURCHASED,
        now.toEpochMilli(), StoreSubscriptionFacts(estado, "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", null, motivo),
    )

    private fun service(upstream: Upstream, provider: Provider, decisions: StorePaymentDecisions = StorePaymentDecisions.CONSERVATIVE, sandbox: String = "reject", users: String? = null) =
        StorePaymentsService(upstream, mapOf(StorePlatform.GOOGLE_PLAY to provider), catalog, StoreSandboxPolicy.parse(sandbox, users), decisions, clock = { now })

    private fun verifyRequest(kind: String = "CONSUMABLE", product: String = "ot_100") = StoreVerifyRequest("GOOGLE_PLAY", product, kind, "token")

    @Test
    fun theConservativeDefaultNeverCreditsAProductionPurchaseAndAnswers503SoTheClientDoesNotFinishIt() = runTest {
        val upstream = Upstream()
        val e = assertFailsWith<BackendDomainException> { service(upstream, Provider(pack())).verify(user, verifyRequest()) }
        assertEquals(CanonicalErrorCode.UPSTREAM_UNAVAILABLE.name, e.errorCode)
        assertFalse("app_store_credit_purchase" in upstream.names())
    }

    @Test
    fun sandboxEvidenceIsStillPossibleWithLiveCreditOffForAllowlistedTesters() = runTest {
        val upstream = Upstream()
        val r = service(upstream, Provider(pack(StoreEnvironment.SANDBOX)), sandbox = "allowlist", users = user.value).verify(user, verifyRequest())
        assertEquals("GRANTED", r.result)
        assertTrue("app_store_credit_purchase" in upstream.names())
    }

    @Test
    fun aProductionPurchaseNotificationIsStoredAndLeftPendingNotCredited() = runTest {
        val upstream = Upstream()
        val event = StoreEvent(StorePlatform.GOOGLE_PLAY, "rtdn:1", StoreEventType.PURCHASE, "gp-1", null, buildJsonObject { put("kind", "CONSUMABLE") })
        assertEquals("DEFERRED", service(upstream, Provider(pack(), event)).webhook(StorePlatform.GOOGLE_PLAY, { null }, "{}".toByteArray()))
        assertFalse("app_store_credit_purchase" in upstream.names())
        val mark = upstream.rpcs.last { it.first == "app_store_inbox_mark" }.second
        assertTrue(mark.contains("\"p_estado\":\"pendiente\"") && mark.contains("LIVE_CREDIT_DISABLED"), mark)
    }

    @Test
    fun subscriptionsAreOffByDefaultNotSoldNotGrantedButLosingAccessIsAlwaysApplied() = runTest {
        val upstream = Upstream()
        val live = StorePaymentDecisions(liveCredit = true)
        assertTrue(service(upstream, Provider(sub("activa")), live).products().items.none { it.kind == "SUBSCRIPTION" })
        val e = assertFailsWith<BackendDomainException> { service(upstream, Provider(sub("activa")), live).verify(user, verifyRequest("SUBSCRIPTION", "premium")) }
        assertEquals(CanonicalErrorCode.UPSTREAM_UNAVAILABLE.name, e.errorCode)
        assertFalse("app_store_upsert_subscription" in upstream.names())
        // quitar acceso (caducada) nunca se frena
        assertEquals("GRANTED", service(upstream, Provider(sub("caducada", "expiro")), live).apply(sub("caducada", "expiro"), null))
        assertTrue("app_store_upsert_subscription" in upstream.names())
    }

    @Test
    fun d11IsOnlyAProposalASubscriptionRefundWaitsForTheRefundSwitchUnlessTheOwnerTurnsItOn() = runTest {
        val row = StoreInboxRow("google_play", "rtdn:9", "REEMBOLSO", "gp-sub", "produccion", buildJsonObject { put("kind", "SUBSCRIPTION") }, reclamo = "c1")
        val off = Upstream()
        assertEquals("DEFERRED", service(off, Provider(sub("caducada", "reembolso")), StorePaymentDecisions(liveCredit = true, subscriptionsEnabled = true)).processInbox(row))
        assertFalse("app_store_upsert_subscription" in off.names())
        val on = Upstream()
        val d11 = StorePaymentDecisions(liveCredit = true, subscriptionsEnabled = true, subscriptionRevocationImmediate = true)
        assertEquals("GRANTED", service(on, Provider(sub("caducada", "reembolso")), d11).processInbox(row))
        assertTrue("app_store_upsert_subscription" in on.names())
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun configurationDefaultsAreConservativeAndUnimplementedChoicesRefuseToStart() {
        assertEquals(StorePaymentDecisions.CONSERVATIVE, StorePaymentsSettings.fromEnvironment(env()).decisions)
        assertFailsWith<StoreConfigException> { StorePaymentDecisions.fromEnvironment(env(STORE_CREDIT_POLICY_ENV to "tolerance"), true) }
        assertFailsWith<StoreConfigException> { StorePaymentDecisions.fromEnvironment(env(STORE_CONSUMPTION_ENV to "true"), true) }
        assertFailsWith<StoreConfigException> { StorePaymentDecisions.fromEnvironment(env(STORE_LIVE_CREDIT_ENV to "true"), false) }
        val noEvidence = assertFailsWith<StoreConfigException> { StorePaymentDecisions.fromEnvironment(env(STORE_LIVE_CREDIT_ENV to "true"), true) }
        assertTrue(noEvidence.message!!.contains(STORE_SANDBOX_EVIDENCE_ENV))
        assertFailsWith<StoreConfigException> { StorePaymentDecisions.fromEnvironment(env(STORE_SUBSCRIPTIONS_ENV to "yes"), true) }
        val live = StorePaymentDecisions.fromEnvironment(env(STORE_LIVE_CREDIT_ENV to "true", STORE_SANDBOX_EVIDENCE_ENV to "ledger/2026-11-01", STORE_SUBSCRIPTIONS_ENV to "true"), true)
        assertTrue(live.liveCredit && live.subscriptionsEnabled && !live.subscriptionRevocationImmediate && !live.refundsEnabled)
    }

    @Test
    fun theAppStoreServerApiKeyIsAllOrNothingAndOptional() {
        val root = File.createTempFile("apple-root", ".der").apply { deleteOnExit() }
        val pki = StoreTestPki()
        root.writeBytes(pki.rootDer)
        val pin = setOf(sha256Hex(pki.rootDer))
        val base = arrayOf(
            STORE_APPLE_ENABLED_ENV to "true", "PAYMENTS_APPLE_BUNDLE_ID" to "com.x", "PAYMENTS_APPLE_APP_APPLE_ID" to "1",
            "PAYMENTS_APPLE_ROOT_CA_PATHS" to root.path, "PAYMENTS_STORE_PRODUCTS" to "app_store:a=PACK_100:CONSUMABLE",
        )
        assertEquals(null, StorePaymentsSettings.fromEnvironment(env(*base), pin).apple!!.api)
        val half = assertFailsWith<StoreConfigException> { StorePaymentsSettings.fromEnvironment(env(*base, APPLE_API_KEY_ID_ENV to "K"), pin) }
        assertTrue(half.message!!.contains(APPLE_API_KEY_FILE_ENV) && half.message!!.contains(APPLE_API_ISSUER_ID_ENV))
        val missingFile = assertFailsWith<StoreConfigException> {
            StorePaymentsSettings.fromEnvironment(env(*base, APPLE_API_KEY_FILE_ENV to "/missing.p8", APPLE_API_KEY_ID_ENV to "K", APPLE_API_ISSUER_ID_ENV to "I"), pin)
        }
        assertTrue(missingFile.message!!.contains(APPLE_API_KEY_FILE_ENV))
        val key = File.createTempFile("iap", ".p8").apply { writeText("x"); deleteOnExit() }
        val api = StorePaymentsSettings.fromEnvironment(env(*base, APPLE_API_KEY_FILE_ENV to key.path, APPLE_API_KEY_ID_ENV to "K", APPLE_API_ISSUER_ID_ENV to "I"), pin).apple!!.api
        assertEquals(AppStoreApiConfig(key.path, "K", "I"), api)
        // una clave que no es PKCS#8 EC no arranca (sin el valor en el mensaje)
        assertFailsWith<StoreConfigException> { LibraryAppStoreServerApi.fromConfig(api!!, "com.x") }
    }

    @Test
    fun storePaymentsEnabledIsPublishedForTheAbsentInboxAlert() {
        StoreInboxMetrics.markEnabled()
        StoreInboxMetrics.markEnabled()
        assertEquals(1.0, BackendPrometheusMetrics.registry.find(StoreInboxMetrics.ENABLED_METRIC).gauge()?.value())
    }

    @Test
    fun theDecisionsDocumentListsEveryDecisionWithItsKey() {
        val root = File("../..").takeIf { File(it, "docs/payments").isDirectory } ?: File(".")
        val doc = File(root, "docs/payments/DECISIONS_PENDING.md").readText()
        assertEquals(((1..11).map { "D$it" } + listOf("G1", "N3-6", "N3-7")).toSet(), STORE_DECISIONS.map { it.id }.toSet())
        STORE_DECISIONS.forEach { d ->
            assertTrue(Regex("\\|\\s*${Regex.escape(d.id)}\\s*\\|").containsMatchIn(doc), "falta ${d.id} en DECISIONS_PENDING.md")
            d.configKey?.let { assertTrue(doc.contains("`$it`"), "falta la clave $it (${d.id}) en DECISIONS_PENDING.md") }
        }
    }
}
