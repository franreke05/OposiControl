package com.oposicontrol.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Re-auditoria de Control, ronda 4 (docs/payments/ROUND4_FIXES.md): N4-1 (no se vende lo que no se puede entregar), N4-2 (los aplazamientos por
 * decision G1 / D8 quedan retenidos, sin contar intentos, y se vuelven a pedir al encender su interruptor) y N4-6 (el re-enlace de huerfanos hace
 * una fila por transaccion desde el trabajo). N4-3 / N4-4 viven en StoreServerSyncTest; N4-5 / N4-6 en SQL en scripts/db-pg17-gate.sh.
 */
class StoreRound4RegressionTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val tester = CanonicalUserId.fromJwtSubject("a0000000-0000-4000-8000-000000000001")
    private val stranger = CanonicalUserId.fromJwtSubject("a0000000-0000-4000-8000-000000000002")

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        val handlers = mutableMapOf<String, (String) -> SupabaseRawResponse>()
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return handlers[function]?.invoke(body) ?: SupabaseRawResponse(200, "true")
        }
        fun names() = rpcs.map { it.first }
        fun calls(function: String): List<JsonObject> = rpcs.filter { it.first == function }.map { Json.parseToJsonElement(it.second).jsonObject }
    }

    private class Provider(var facts: StorePurchaseFacts) : StorePaymentProvider {
        override val platform = StorePlatform.GOOGLE_PLAY
        override suspend fun verify(storeProductId: String, kind: StoreKind, token: String) = facts
        override suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant): StoreEvent = error("not used")
        override suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject) = facts
        override suspend fun finish(kind: StoreKind, storeProductId: String, purchaseKey: String) = Unit
    }

    private val catalog = StoreCatalog.parse("google_play:ot_100=PACK_100:CONSUMABLE,google_play:premium/monthly=PREMIUM_M:SUBSCRIPTION")

    private fun pack() = StorePurchaseFacts(StorePlatform.GOOGLE_PLAY, StoreKind.CONSUMABLE, "ot_100", "gp-1", account, StoreEnvironment.PRODUCTION, 1,
        StorePurchaseState.PURCHASED, now.toEpochMilli())

    private fun sub() = StorePurchaseFacts(StorePlatform.GOOGLE_PLAY, StoreKind.SUBSCRIPTION, "premium/monthly", "gp-sub", account, StoreEnvironment.PRODUCTION, 1,
        StorePurchaseState.PURCHASED, now.toEpochMilli(), StoreSubscriptionFacts("activa", "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z"))

    private fun service(upstream: Upstream, decisions: StorePaymentDecisions, sandbox: String = "reject", users: String? = null, facts: StorePurchaseFacts = pack()) =
        StorePaymentsService(upstream, mapOf(StorePlatform.GOOGLE_PLAY to Provider(facts)), catalog, StoreSandboxPolicy.parse(sandbox, users), decisions, clock = { now })

    private fun kinds(response: StoreProductsResponse) = response.items.map { it.kind }.toSet()

    // ---------- N4-1 ----------

    /** N4-1: con G1 apagado (defecto) ningun pack ni suscripcion de produccion se ofrece: nadie paga algo que verify no puede entregar. */
    @Test
    fun withLiveCreditOffNoProductionProductIsListedSoNobodyPaysForSomethingUndeliverable() {
        assertEquals(emptySet(), kinds(service(Upstream(), StorePaymentDecisions.CONSERVATIVE).products(stranger)))
        assertEquals(emptySet(), kinds(service(Upstream(), StorePaymentDecisions(subscriptionsEnabled = true)).products(stranger)))
        assertEquals(emptySet(), kinds(service(Upstream(), StorePaymentDecisions.CONSERVATIVE).products(null)))
    }

    /** N4-1: la evidencia de sandbox sigue siendo posible: el probador de la allowlist (o `accept` en local/test) ve los packs con G1 apagado. */
    @Test
    fun sandboxTestersStillSeeThePacksWithLiveCreditOff() {
        val allowlist = service(Upstream(), StorePaymentDecisions.CONSERVATIVE, sandbox = "allowlist", users = tester.value)
        assertEquals(setOf("CONSUMABLE"), kinds(allowlist.products(tester)))
        assertEquals(emptySet(), kinds(allowlist.products(stranger)))
        assertEquals(setOf("CONSUMABLE"), kinds(service(Upstream(), StorePaymentDecisions.CONSERVATIVE, sandbox = "accept").products(stranger)))
        assertEquals(setOf("CONSUMABLE", "SUBSCRIPTION"), kinds(service(Upstream(), StorePaymentDecisions(subscriptionsEnabled = true), sandbox = "allowlist", users = tester.value).products(tester)))
    }

    /** N4-1: con G1 encendido se lista como antes (D8 sigue decidiendo las suscripciones). */
    @Test
    fun withLiveCreditOnTheCatalogIsListedAsBefore() {
        assertEquals(setOf("CONSUMABLE"), kinds(service(Upstream(), StorePaymentDecisions(liveCredit = true)).products(stranger)))
        assertEquals(setOf("CONSUMABLE", "SUBSCRIPTION"), kinds(service(Upstream(), StorePaymentDecisions(liveCredit = true, subscriptionsEnabled = true)).products(stranger)))
    }

    // ---------- N4-2 ----------

    private fun row(id: String, kind: String) = StoreInboxRow("google_play", id, "COMPRA", "gp-1", "produccion", buildJsonObject { put("kind", kind) }, reclamo = "c-$id")

    /** N4-2: un aviso de compra con G1 apagado queda RETENIDO (no `pendiente`: no cuenta intento, no llega a `muerto`), con su motivo. */
    @Test
    fun aLiveCreditDeferralIsRetainedNotAFailedAttempt() = runTest {
        val upstream = Upstream()
        assertEquals("DEFERRED", service(upstream, StorePaymentDecisions(deadLetterAttempts = 20)).processInbox(row("rtdn:1", "CONSUMABLE")))
        val mark = upstream.calls("app_store_inbox_mark").single()
        assertEquals("retenido", mark["p_estado"]!!.jsonPrimitive.content)
        assertEquals("LIVE_CREDIT_DISABLED", mark["p_error"]!!.jsonPrimitive.content)
        assertFalse("app_store_credit_purchase" in upstream.names())
    }

    /** N4-2: igual para una suscripcion con D8 apagado. */
    @Test
    fun aSubscriptionsDisabledDeferralIsRetainedToo() = runTest {
        val upstream = Upstream()
        service(upstream, StorePaymentDecisions(liveCredit = true), facts = sub()).processInbox(row("rtdn:2", "SUBSCRIPTION").copy(tipo = "SUSCRIPCION"))
        val mark = upstream.calls("app_store_inbox_mark").single()
        assertEquals("retenido", mark["p_estado"]!!.jsonPrimitive.content)
        assertEquals("SUBSCRIPTIONS_DISABLED", mark["p_error"]!!.jsonPrimitive.content)
        assertFalse("app_store_upsert_subscription" in upstream.names())
    }

    private suspend fun retainedAsked(decisions: StorePaymentDecisions): List<String> {
        val upstream = Upstream().apply { handlers["app_store_inbox_pending"] = { SupabaseRawResponse(200, "[]") } }
        service(upstream, decisions).reprocessPending()
        val args = upstream.calls("app_store_inbox_pending").single()
        assertFalse("p_incluir_retenidos" in args, args.toString())
        return args["p_retenidos"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
    }

    /** N4-2: el trabajo vuelve a pedir cada retenido SOLO cuando el interruptor que lo retuvo esta encendido. */
    @Test
    fun theJobReAsksEachRetainedReasonOnlyWhenItsSwitchIsOn() = runTest {
        assertEquals(emptyList(), retainedAsked(StorePaymentDecisions.CONSERVATIVE))
        assertEquals(listOf("LIVE_CREDIT_DISABLED"), retainedAsked(StorePaymentDecisions(liveCredit = true)))
        assertEquals(listOf("SUBSCRIPTIONS_DISABLED"), retainedAsked(StorePaymentDecisions(subscriptionsEnabled = true)))
        assertEquals(listOf("REFUNDS_DISABLED"), retainedAsked(StorePaymentDecisions(refundsEnabled = true)))
        assertEquals(listOf("LIVE_CREDIT_DISABLED", "REFUNDS_DISABLED", "SUBSCRIPTIONS_DISABLED"),
            retainedAsked(StorePaymentDecisions(liveCredit = true, refundsEnabled = true, subscriptionsEnabled = true)))
    }

    /** N4-2: los retenidos por decision tienen su propia serie; no disparan la alerta de reembolsos ni la de pendientes atascados. */
    @Test
    fun decisionHoldsAreMeasuredApartAndRaiseNoAlert() = runTest {
        val upstream = Upstream().apply {
            handlers["app_store_inbox_stats"] = { SupabaseRawResponse(200, """[{"estado":"retenido_decision","n":4,"mas_antiguo":"2026-10-16T12:00:00+00:00","max_intentos":0}]""") }
        }
        val alerts = CopyOnWriteArrayList<String>()
        StoreInboxMetrics.update(service(upstream, StorePaymentDecisions.CONSERVATIVE).inboxStats(), { alerts += it })
        assertTrue("retenido_decision" in StoreInboxMetrics.STATES)
        assertEquals(4L, StoreInboxMetrics.value(StoreInboxMetrics.BACKLOG_METRIC, "retenido_decision"))
        assertEquals(0L, StoreInboxMetrics.value(StoreInboxMetrics.BACKLOG_METRIC, "pendiente"))
        assertEquals(0L, StoreInboxMetrics.value(StoreInboxMetrics.OLDEST_METRIC, "pendiente"))
        assertEquals(0L, StoreInboxMetrics.value(StoreInboxMetrics.BACKLOG_METRIC, "retenido"))
        assertTrue(alerts.isEmpty(), alerts.toString())
    }

    // ---------- N4-6 ----------

    /**
     * N4-6: el re-enlace de huerfanos ya no es UN lote de hasta 200 filas en una transaccion: el trabajo pide una fila por llamada (una transaccion,
     * como mucho un cerrojo de clave y uno de cartera), sigue por el cursor de la anterior y para al vaciarse (o a las 50).
     */
    @Test
    fun orphanReconcileRunsOneRowPerTransactionFollowingTheCursor() = runTest {
        val upstream = Upstream()
        val answers = ArrayDeque(listOf(
            """[{"proveedor":"app_store","evento_id":"assn:a","recibido_en":"2026-10-20T10:00:00.123456+00:00","resultado":"REVERSAL_RECORDED"}]""",
            """[{"proveedor":"app_store","evento_id":"assn:b","recibido_en":"2026-10-20T10:00:01+00:00","resultado":"BUSY"}]""",
            """[{"proveedor":"google_play","evento_id":"rtdn:c","recibido_en":"2026-10-20T10:00:02+00:00","resultado":"REFUNDED"}]""",
            "[]",
        ))
        upstream.handlers["app_store_reconcile_orphan_step"] = { SupabaseRawResponse(200, answers.removeFirst()) }
        val logs = CopyOnWriteArrayList<String>()
        StorePaymentsService(upstream, emptyMap(), catalog, StoreSandboxPolicy.parse("reject", null), StorePaymentDecisions(refundsEnabled = true), clock = { now }, log = { logs += it })
            .maintenance(retentionDays = null)
        assertFalse("app_store_reconcile_orphans" in upstream.names(), "the 200-row batch is not used: ${upstream.names()}")
        val steps = upstream.calls("app_store_reconcile_orphan_step")
        assertEquals(4, steps.size)
        assertEquals(JsonNull, steps[0]["p_tras_recibido"])
        assertEquals("2026-10-20T10:00:00.123456+00:00", steps[1]["p_tras_recibido"]!!.jsonPrimitive.content)
        assertEquals("assn:a", steps[1]["p_tras_evento"]!!.jsonPrimitive.content)
        assertEquals("google_play", steps[3]["p_tras_proveedor"]!!.jsonPrimitive.content)
        assertTrue(steps.all { it["p_reembolsos_habilitados"]!!.jsonPrimitive.content == "true" })
        assertTrue(logs.any { it.contains("2 orphan notification(s) re-linked") }, logs.toString())
    }

    @Test
    fun orphanReconcileStopsAfterFiftyRowsPerRun() = runTest {
        val upstream = Upstream()
        var n = 0
        upstream.handlers["app_store_reconcile_orphan_step"] = {
            n++
            SupabaseRawResponse(200, """[{"proveedor":"app_store","evento_id":"assn:$n","recibido_en":"2026-10-20T10:00:00+00:00","resultado":"BUSY"}]""")
        }
        StorePaymentsService(upstream, emptyMap(), catalog, StoreSandboxPolicy.parse("reject", null), StorePaymentDecisions.CONSERVATIVE, clock = { now }).maintenance(null)
        assertEquals(50, upstream.calls("app_store_reconcile_orphan_step").size)
        assertTrue(upstream.calls("app_store_reconcile_orphan_step").all { it["p_reembolsos_habilitados"]!!.jsonPrimitive.content == "false" })
    }

}
