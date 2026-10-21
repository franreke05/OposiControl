package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verificacion en servidor con las APIs oficiales (tarea 2) y ciclo de vida de suscripciones (tarea 3), SIN red: el transporte [StoreHttp] graba
 * cada peticion y responde con cargas grabadas / forjadas firmadas por la PKI de PRUEBA ([StoreTestPki]); la clave In-App Purchase es una clave EC
 * generada aqui. Nunca claves reales.
 */
class StoreServerSyncTest {
    private val pki = StoreTestPki()
    private val bundle = "com.oposibot.test"
    private val appAppleId = 1234567890L
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val now = Instant.now()

    // ---------- clave In-App Purchase de PRUEBA (PKCS#8 PEM, P-256) ----------
    private val apiKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val apiKeyPem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(apiKeys.private.encoded) + "\n-----END PRIVATE KEY-----\n"

    private class RecordingHttp : StoreHttp {
        data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
        val calls = CopyOnWriteArrayList<Call>()
        val responses = ArrayDeque<StoreHttpResponse>()
        var failure: Exception? = null
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): StoreHttpResponse {
            calls += Call(method, url, headers, body)
            failure?.let { throw it }
            return responses.removeFirstOrNull() ?: StoreHttpResponse(500, "{}")
        }
    }

    private fun tx(
        type: String = "Auto-Renewable Subscription",
        product: String = "com.oposibot.premium",
        expires: Long? = now.plusSeconds(86_400).toEpochMilli(),
        revocation: Long? = null,
        tid: String = "2000000001",
        env: String = "Production",
    ) = pki.sign(
        """{"transactionId":"$tid","originalTransactionId":"1000000001","bundleId":"$bundle","productId":"$product","purchaseDate":${now.toEpochMilli() - 1000},""" +
            """"quantity":1,"type":"$type","signedDate":${now.toEpochMilli()},"environment":"$env","appAccountToken":"$account"""" +
            (expires?.let { ""","expiresDate":$it""" } ?: "") + (revocation?.let { ""","revocationDate":$it,"revocationReason":0""" } ?: "") + "}",
    )

    private fun renewal(autoRenew: Int = 1, grace: Long? = null, retry: Boolean = false) = pki.sign(
        """{"originalTransactionId":"1000000001","productId":"com.oposibot.premium","autoRenewStatus":$autoRenew,"isInBillingRetryPeriod":$retry,""" +
            """"signedDate":${now.toEpochMilli()},"environment":"Production"""" + (grace?.let { ""","gracePeriodExpiresDate":$it""" } ?: "") + "}",
    )

    private fun statuses(status: Int, signedTx: String, signedRenewal: String = renewal()) = StoreHttpResponse(200,
        """{"environment":"Production","bundleId":"$bundle","appAppleId":$appAppleId,"data":[{"subscriptionGroupIdentifier":"g1","lastTransactions":[""" +
            """{"status":$status,"originalTransactionId":"1000000001","signedTransactionInfo":"$signedTx","signedRenewalInfo":"$signedRenewal"}]}]}""")

    private fun provider(http: RecordingHttp) = AppStoreProvider(
        AppStoreConfig(bundle, appAppleId, emptyList(), onlineChecks = false), listOf(pki.rootDer), clock = { now },
        api = LibraryAppStoreServerApi(apiKeyPem, "KEYID12345", "11111111-2222-3333-4444-555555555555", bundle, http),
    )

    // ---------- App Store Server API: cliente oficial sobre el transporte grabado ----------

    @Test
    fun getTransactionInfoIsCalledWithAnEs256BearerSignedByTheInAppPurchaseKeyAndItsAnswerIsVerified() = runTest {
        val http = RecordingHttp()
        http.responses += StoreHttpResponse(200, """{"signedTransactionInfo":"${tx(type = "Consumable", product = "com.oposibot.ot100", expires = null)}"}""")
        val facts = provider(http).verify("com.oposibot.ot100", StoreKind.CONSUMABLE, tx(type = "Consumable", product = "com.oposibot.ot100", expires = null))
        assertEquals("2000000001", facts.purchaseKey)
        val call = http.calls.single()
        assertEquals("GET", call.method)
        assertEquals("https://api.storekit.apple.com/inApps/v1/transactions/2000000001", call.url)
        val bearer = call.headers.getValue("Authorization").removePrefix("Bearer ")
        val jwt = JWT.require(Algorithm.ECDSA256(apiKeys.public as ECPublicKey, null as ECPrivateKey?)).build().verify(bearer)
        assertEquals("KEYID12345", jwt.keyId)
        assertEquals("11111111-2222-3333-4444-555555555555", jwt.issuer)
        assertEquals(bundle, jwt.getClaim("bid").asString())
        assertEquals("appstoreconnect-v1", jwt.audience.single())
    }

    @Test
    fun theServerAnswerWinsARefundSinceTheClientSignedItIsSeenAsRevoked() = runTest {
        val http = RecordingHttp()
        http.responses += StoreHttpResponse(200, """{"signedTransactionInfo":"${tx(type = "Consumable", product = "com.oposibot.ot100", expires = null, revocation = now.toEpochMilli())}"}""")
        val facts = provider(http).verify("com.oposibot.ot100", StoreKind.CONSUMABLE, tx(type = "Consumable", product = "com.oposibot.ot100", expires = null))
        assertEquals(StorePurchaseState.REVOKED, facts.state)
    }

    @Test
    fun aServerAnswerSignedByAnotherChainOrForAnotherTransactionIsRefused() = runTest {
        val http = RecordingHttp()
        http.responses += StoreHttpResponse(200, """{"signedTransactionInfo":"${StoreTestPki().sign("{}")}"}""")
        assertFailsWith<BackendDomainException> { provider(http).verify("com.oposibot.ot100", StoreKind.CONSUMABLE, tx(type = "Consumable", product = "com.oposibot.ot100", expires = null)) }
        http.responses += StoreHttpResponse(200, """{"signedTransactionInfo":"${tx(type = "Consumable", product = "com.oposibot.ot100", expires = null, tid = "999")}"}""")
        val e = assertFailsWith<BackendDomainException> { provider(http).verify("com.oposibot.ot100", StoreKind.CONSUMABLE, tx(type = "Consumable", product = "com.oposibot.ot100", expires = null)) }
        assertEquals("STORE_PURCHASE_INVALID", e.errorCode)
    }

    @Test
    fun apiErrorsAreDefinitiveOnlyForUnknownIdsAndTransientOtherwise() = runTest {
        val consumable = tx(type = "Consumable", product = "com.oposibot.ot100", expires = null)
        for ((status, code) in listOf(404 to "STORE_PURCHASE_INVALID", 401 to "UPSTREAM_UNAVAILABLE", 429 to "UPSTREAM_UNAVAILABLE", 500 to "UPSTREAM_UNAVAILABLE")) {
            val http = RecordingHttp().apply { responses += StoreHttpResponse(status, """{"errorCode":4040010,"errorMessage":"x"}""") }
            assertEquals(code, assertFailsWith<BackendDomainException> { provider(http).verify("com.oposibot.ot100", StoreKind.CONSUMABLE, consumable) }.errorCode, "HTTP $status")
        }
        val down = RecordingHttp().apply { failure = java.io.IOException("connect timed out") }
        assertEquals("UPSTREAM_UNAVAILABLE", assertFailsWith<BackendDomainException> { provider(down).verify("com.oposibot.ot100", StoreKind.CONSUMABLE, consumable) }.errorCode)
    }

    // ---------- tarea 3: el estado de la suscripcion sale del estado firmado de Apple ----------

    @Test
    fun getAllSubscriptionStatusesDrivesTheLifecycleActiveGraceRetryExpiredRevokedAndAutoRenewOff() = runTest {
        suspend fun facts(response: StoreHttpResponse): StorePurchaseFacts {
            val http = RecordingHttp().apply { responses += response }
            return provider(http).currentSubscription("1000000001", StoreEnvironment.PRODUCTION)!!.also {
                assertEquals("https://api.storekit.apple.com/inApps/v1/subscriptions/1000000001", http.calls.single().url)
            }
        }
        val past = now.minusSeconds(60).toEpochMilli()
        assertEquals("activa", facts(statuses(1, tx())).subscription?.estado)
        assertEquals("cancelada" to "usuario_cancelo", facts(statuses(1, tx(), renewal(autoRenew = 0))).subscription!!.let { it.estado to it.finMotivo })
        val grace = facts(statuses(4, tx(expires = past), renewal(grace = now.plusSeconds(3 * 86_400).toEpochMilli(), retry = true))).subscription!!
        assertEquals("activa", grace.estado)
        assertEquals(Instant.ofEpochMilli(now.plusSeconds(3 * 86_400).toEpochMilli()).toString(), grace.fin)
        assertEquals("pausada", facts(statuses(3, tx(expires = past), renewal(retry = true))).subscription?.estado)
        assertEquals("caducada" to "expiro", facts(statuses(2, tx(expires = past), renewal(autoRenew = 0))).subscription!!.let { it.estado to it.finMotivo })
        assertEquals("caducada" to "reembolso", facts(statuses(5, tx(revocation = now.toEpochMilli()))).subscription!!.let { it.estado to it.finMotivo })
        // version = hora de lectura: un estado releido nunca es mas viejo que un aviso ya aplicado
        assertEquals(now.toEpochMilli(), facts(statuses(1, tx())).versionMillis)
    }

    @Test
    fun withTheApiASubscriptionNotificationIsReReadSoOutOfOrderDeliveryDoesNotMatter() = runTest {
        val http = RecordingHttp()
        val p = provider(http)
        // aviso VIEJO (renovacion) que llega tarde; Apple dice hoy que esta caducada
        val old = p.eventFromSignedPayload(pki.sign(
            """{"notificationType":"DID_RENEW","notificationUUID":"late-1","version":"2.0","signedDate":${now.toEpochMilli() - 86_400_000},""" +
                """"data":{"environment":"Production","bundleId":"$bundle","appAppleId":$appAppleId,"signedTransactionInfo":"${tx()}","signedRenewalInfo":"${renewal()}"}}""",
        ))
        http.responses += statuses(2, tx(expires = now.minusSeconds(60).toEpochMilli()), renewal(autoRenew = 0))
        assertEquals("caducada", p.factsFor(old.type, old.purchaseKey, old.data)!!.subscription?.estado)
    }

    @Test
    fun withoutTheApiNothingIsCalledAndTheSignedFactsAreUsed() = runTest {
        val p = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), onlineChecks = false), listOf(pki.rootDer), clock = { now })
        assertEquals("activa", p.verify("com.oposibot.premium", StoreKind.SUBSCRIPTION, tx()).subscription?.estado)
        assertEquals(null, p.currentSubscription("1000000001", StoreEnvironment.PRODUCTION))
        assertEquals(false, p.hasServerApi)
    }

    // ---------- job: cursores persistidos, Notification History, Voided Purchases, sincronizacion ----------

    /** Upstream con lo minimo: cursores en memoria de "la base" (lo que haria tienda_sondeo) y bandeja que acepta todo. */
    private class Db : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        var cursor: Pair<Long, String>? = null
        var token: String? = null
        var window: Pair<Long, Long>? = null
        /** Longitud maxima de token que acepta "la base" (N6-2: 4096 en 20261022103200, 65536 desde 20261022103300). */
        var maxToken = 65_536
        var due = "[]"
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            val args = Json.parseToJsonElement(body).jsonObject
            return when (function) {
                "app_store_poll_get" -> SupabaseRawResponse(200, cursor?.let {
                    """[{"cursor_ms":${it.first},"ultimo_sondeo_en":"${it.second}","pagina_token":${token?.let { t -> "\"$t\"" } ?: "null"},""" +
                        """"ventana_inicio_ms":${window?.first},"ventana_fin_ms":${window?.second}}]"""
                } ?: "[]")
                "app_store_poll_advance" -> {
                    val c = maxOf(cursor?.first ?: 0, args["p_cursor_ms"]!!.jsonPrimitive.content.toLong())
                    cursor = c to Instant.now().toString()
                    token = null
                    window = null
                    SupabaseRawResponse(200, "$c")
                }
                "app_store_poll_page" -> {
                    if (args["p_token"]!!.jsonPrimitive.content.length > maxToken) return SupabaseRawResponse(400, """{"message":"STORE_REQUEST_INVALID"}""")
                    token = args["p_token"]!!.jsonPrimitive.content
                    window = args["p_inicio_ms"]!!.jsonPrimitive.content.toLong() to args["p_fin_ms"]!!.jsonPrimitive.content.toLong()
                    cursor = (cursor?.first ?: window!!.first) to Instant.now().toString()
                    SupabaseRawResponse(200, "true")
                }
                "app_store_inbox_put" -> SupabaseRawResponse(200, """[{"nuevo":true,"estado":"pendiente","resultado":null,"misma_huella":true,"reclamo":"c"}]""")
                "app_store_subscriptions_due" -> SupabaseRawResponse(200, due)
                "app_store_upsert_subscription" -> SupabaseRawResponse(200, """[{"outcome":"UPDATED","estado":"${args["p_estado"]!!.jsonPrimitive.content}","fin_en":null,"es_premium":false}]""")
                "app_store_refund_purchase" -> SupabaseRawResponse(200, """[{"outcome":"REFUND_ORPHAN_HELD"}]""")
                else -> SupabaseRawResponse(200, "true")
            }
        }
        fun calls(function: String) = rpcs.filter { it.first == function }.map { Json.parseToJsonElement(it.second).jsonObject }
    }

    private val catalog = StoreCatalog.parse("app_store:com.oposibot.premium=PREMIUM_M:SUBSCRIPTION,google_play:premium/monthly=PREMIUM_M:SUBSCRIPTION,google_play:ot_100=PACK_100:CONSUMABLE")
    private val resolver = CanonicalIdentityResolver(verifySubject = { error("no") }, directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) })

    private fun runtime(db: Db, apple: AppStoreProvider? = null, google: GooglePlayProvider? = null): StorePaymentsRuntime {
        val providers = listOfNotNull<StorePaymentProvider>(apple, google).associateBy { it.platform }
        val svc = testStoreService(db, providers, catalog, StoreSandboxPolicy.parse("reject", null), true, clock = { now })
        return StorePaymentsRuntime(resolver, svc, InMemoryIdempotencyStore { now }, google = google, apple = apple)
    }

    private fun assn(uuid: String) = pki.sign(
        """{"notificationType":"EXPIRED","subtype":"VOLUNTARY","notificationUUID":"$uuid","version":"2.0","signedDate":${now.toEpochMilli()},""" +
            """"data":{"environment":"Production","bundleId":"$bundle","appAppleId":$appAppleId,"signedTransactionInfo":"${tx(expires = now.minusSeconds(60).toEpochMilli())}","signedRenewalInfo":"${renewal(autoRenew = 0)}"}}""",
    )

    @Test
    fun notificationHistoryRecoversMissedNotificationsPageByPageSkipsForgedOnesAndPersistsItsCursor() = runTest {
        val http = RecordingHttp()
        val p = provider(http)
        val db = Db()
        val forged = StoreTestPki().sign("""{"notificationType":"REFUND","notificationUUID":"evil"}""")
        http.responses += StoreHttpResponse(200, """{"notificationHistory":[{"signedPayload":"${assn("h-1")}"},{"signedPayload":"$forged"}],"hasMore":true,"paginationToken":"p2"}""")
        http.responses += StoreHttpResponse(200, """{"notificationHistory":[{"signedPayload":"${assn("h-2")}"}],"hasMore":false}""")
        http.responses += statuses(2, tx(expires = now.minusSeconds(60).toEpochMilli()), renewal(autoRenew = 0))
        http.responses += statuses(2, tx(expires = now.minusSeconds(60).toEpochMilli()), renewal(autoRenew = 0))
        val logs = mutableListOf<String>()
        assertEquals(2, StorePaymentsJob.pollAppleHistory(runtime(db, apple = p), now) { logs += it })
        val history = http.calls.filter { it.url.contains("/notifications/history") }
        assertEquals(listOf("https://api.storekit.apple.com/inApps/v1/notifications/history", "https://api.storekit.apple.com/inApps/v1/notifications/history?paginationToken=p2"), history.map { it.url })
        val body = Json.parseToJsonElement(history.first().body!!).jsonObject
        assertEquals(now.toEpochMilli() - 3 * 86_400_000L - 3_600_000L, body["startDate"]!!.jsonPrimitive.content.toLong())  // primera vez: 3 dias + solape
        assertEquals(now.toEpochMilli(), body["endDate"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("assn:h-1", "assn:h-2"), db.calls("app_store_inbox_put").map { it["p_evento_id"]!!.jsonPrimitive.content })
        assertTrue(logs.any { it.contains("1 notification(s) failed verification") }, logs.toString())
        assertEquals(now.toEpochMilli(), db.cursor!!.first)
        // el cursor y la hora vienen de la base: dentro de 6 horas no se vuelve a pedir (tambien tras un reinicio u otra replica)
        val before = http.calls.size
        assertEquals(0, StorePaymentsJob.pollAppleHistory(runtime(db, apple = p), now.plusSeconds(60)))
        assertEquals(before, http.calls.size)
    }

    private fun testNotification(uuid: String) = pki.sign(
        """{"notificationType":"TEST","notificationUUID":"$uuid","version":"2.0","signedDate":${now.toEpochMilli()},""" +
            """"data":{"environment":"Production","bundleId":"$bundle","appAppleId":$appAppleId}}""",
    )

    /**
     * N4-3 + N5-1 (re-auditoria Control rondas 4 y 5): mas de 100 paginas desde el cursor. Se GUARDA el `paginationToken` con su ventana (el cursor no
     * se mueve: no depende de que History venga en orden), el siguiente paso del trabajo sigue desde ese token en la MISMA ventana (sin esperar
     * 6 h) y, al acabar, el cursor llega al fin de la ventana. Gauge `store_inbox_history_incomplete` = 1 mientras queda token, 0 al terminar.
     */
    @Test
    fun moreThanAHundredPagesAreReadAcrossPollsFromThePersistedTokenAndNothingIsSkipped() = runTest {
        val http = RecordingHttp()
        http.responses += StoreHttpResponse(200, """{"notificationHistory":[{"signedPayload":"${testNotification("t-1")}"}],"hasMore":true,"paginationToken":"p2"}""")
        repeat(99) { i -> http.responses += StoreHttpResponse(200, """{"notificationHistory":[],"hasMore":true,"paginationToken":"p${i + 3}"}""") }
        val db = Db()
        val twoDaysAgo = now.minusSeconds(2 * 86_400L)
        db.cursor = twoDaysAgo.toEpochMilli() to now.minusSeconds(7 * 3_600L).toString()
        val logs = mutableListOf<String>()
        StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(http)), now) { logs += it }
        assertEquals(100, http.calls.count { it.url.contains("/notifications/history") })
        assertEquals(twoDaysAgo.toEpochMilli(), db.cursor!!.first, "the cursor stays where the unread pages start")
        assertEquals("p101", db.token)
        val window = twoDaysAgo.toEpochMilli() - 3_600_000L to now.toEpochMilli()
        assertEquals(window, db.window)
        assertEquals(1L, StoreInboxMetrics.historyIncomplete())
        assertTrue(logs.any { it.startsWith("ALERT store apple history") }, logs.toString())
        // pagina 101: siguiente vuelta del trabajo (5 min despues, sin esperar 6 h), desde el token guardado y con la MISMA ventana
        http.calls.clear()
        http.responses += StoreHttpResponse(200, """{"notificationHistory":[{"signedPayload":"${testNotification("t-2")}"}],"hasMore":false}""")
        StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(http)), now.plusSeconds(300))
        val call = http.calls.single()
        assertEquals("https://api.storekit.apple.com/inApps/v1/notifications/history?paginationToken=p101", call.url)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(window.first, body["startDate"]!!.jsonPrimitive.content.toLong())
        assertEquals(window.second, body["endDate"]!!.jsonPrimitive.content.toLong())
        assertEquals(now.toEpochMilli(), db.cursor!!.first, "complete: the cursor reaches the end of the window")
        assertEquals(null, db.token)
        assertEquals(0L, StoreInboxMetrics.historyIncomplete())
        assertEquals(listOf("assn:t-1", "assn:t-2"), db.calls("app_store_inbox_put").map { it["p_evento_id"]!!.jsonPrimitive.content })
    }

    /**
     * N6-1 (re-auditoria Control ronda 6): el gauge sigue al estado de la BASE en cada vuelta, no a la ultima lectura de la replica. Replica A deja
     * una pagina a medias (gauge 1); replica B termina la ventana; A (que seguia exportando 1) en su siguiente vuelta exporta 0 aunque salga sin leer.
     */
    @Test
    fun theHistoryGaugeFollowsTheDatabaseAcrossReplicas() = runTest {
        val db = Db()
        val twoDaysAgo = now.minusSeconds(2 * 86_400L)
        db.cursor = twoDaysAgo.toEpochMilli() to now.minusSeconds(7 * 3_600L).toString()
        val httpA = RecordingHttp().apply { repeat(100) { i -> responses += StoreHttpResponse(200, """{"notificationHistory":[],"hasMore":true,"paginationToken":"a${i + 2}"}""") } }
        StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(httpA)), now)
        assertEquals(1L, StoreInboxMetrics.historyIncomplete(), "replica A left a page pending")
        val httpB = RecordingHttp().apply { responses += StoreHttpResponse(200, """{"notificationHistory":[],"hasMore":false}""") }
        StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(httpB)), now.plusSeconds(60))
        assertEquals(null, db.token)
        StoreInboxMetrics.historyIncomplete(true)   // lo que A seguia exportando (el gauge es por proceso)
        val before = httpA.calls.size
        assertEquals(0, StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(httpA)), now.plusSeconds(120)))
        assertEquals(before, httpA.calls.size, "A leaves early: B polled less than 6 h ago")
        assertEquals(0L, StoreInboxMetrics.historyIncomplete(), "A exports the database state (no page pending), not its last read")
    }

    /** N6-1: una replica reiniciada (gauge a 0) con un token atascado en la base (la API falla con 5xx) exporta 1 desde el estado de la base. */
    @Test
    fun aRestartedReplicaRaisesTheGaugeFromAStuckTokenInTheDatabase() = runTest {
        val db = Db()
        val twoDaysAgo = now.minusSeconds(2 * 86_400L)
        db.cursor = twoDaysAgo.toEpochMilli() to now.minusSeconds(60).toString()
        db.token = "stuck"
        db.window = twoDaysAgo.toEpochMilli() - 3_600_000L to now.minusSeconds(3_600).toEpochMilli()
        StoreInboxMetrics.historyIncomplete(false)   // proceso recien arrancado
        val http = RecordingHttp().apply { responses += StoreHttpResponse(503, "{}") }
        assertFailsWith<BackendDomainException> { StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(http)), now) }
        assertEquals("stuck", db.token, "a 5xx keeps the token")
        assertEquals(1L, StoreInboxMetrics.historyIncomplete())
    }

    /**
     * N6-2: un token que la base no acepta (mas largo que su limite) no puede dejar la alerta muda: el gauge se pone a 1 ANTES de guardar. Con
     * 20261022103300 el limite es 65536 y un token de 5000 caracteres se guarda.
     */
    @Test
    fun aTokenTheDatabaseRefusesStillRaisesTheGaugeAndALongTokenIsStored() = runTest {
        val long = "t".repeat(5_000)
        fun pages() = RecordingHttp().apply { repeat(100) { responses += StoreHttpResponse(200, """{"notificationHistory":[],"hasMore":true,"paginationToken":"$long"}""") } }
        val old = Db().apply { maxToken = 4_096; cursor = now.minusSeconds(86_400L).toEpochMilli() to now.minusSeconds(7 * 3_600L).toString() }
        StoreInboxMetrics.historyIncomplete(false)
        assertFailsWith<BackendDomainException> { StorePaymentsJob.pollAppleHistory(runtime(old, apple = provider(pages())), now) }
        assertEquals(1L, StoreInboxMetrics.historyIncomplete(), "the refused save does not silence the alert")
        val db = Db().apply { cursor = now.minusSeconds(86_400L).toEpochMilli() to now.minusSeconds(7 * 3_600L).toString() }
        StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(pages())), now)
        assertEquals(long, db.token)
    }

    /** N5-1: Apple rechaza un token guardado (4xx): se descarta y la ventana se relee desde el cursor (nada se salta); el error sube. */
    @Test
    fun aRejectedStoredTokenRestartsTheWindowFromTheCursor() = runTest {
        val http = RecordingHttp().apply { responses += StoreHttpResponse(400, """{"errorCode":4000006,"errorMessage":"Invalid pagination token"}""") }
        val db = Db()
        val twoDaysAgo = now.minusSeconds(2 * 86_400L)
        db.cursor = twoDaysAgo.toEpochMilli() to now.minusSeconds(60).toString()
        db.token = "stale"
        db.window = twoDaysAgo.toEpochMilli() - 3_600_000L to now.minusSeconds(3_600).toEpochMilli()
        assertFailsWith<Exception> { StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(http)), now) }
        assertEquals(null, db.token)
        assertEquals(twoDaysAgo.toEpochMilli(), db.cursor!!.first)
    }

    /** N4-4: el mismo aviso por el webhook y por Notification History da la MISMA huella (sha256 del signedPayload en los dos caminos). */
    @Test
    fun theSameNotificationHasTheSameFingerprintFromTheWebhookAndFromHistory() = runTest {
        val signed = assn("same-1")
        val http = RecordingHttp()
        val db = Db()
        val rt = runtime(db, apple = provider(http))
        rt.service.webhook(StorePlatform.APP_STORE, { null }, """{"signedPayload":"$signed"}""".toByteArray())
        http.responses.clear()   // el proceso del aviso (relectura de la suscripcion) fallo y queda pendiente: no importa aqui
        http.responses += StoreHttpResponse(200, """{"notificationHistory":[{"signedPayload":"$signed"}],"hasMore":false}""")
        StorePaymentsJob.pollAppleHistory(rt, now)
        val prints = db.calls("app_store_inbox_put").map { it["p_evento_id"]!!.jsonPrimitive.content to it["p_huella"]!!.jsonPrimitive.content }
        assertEquals(2, prints.size, prints.toString())
        assertEquals(setOf("assn:same-1"), prints.map { it.first }.toSet())
        assertEquals(prints[0].second, prints[1].second, "webhook and history fingerprints differ: $prints")
        assertEquals(sha256Hex(signed.toByteArray(Charsets.UTF_8)), prints[0].second)
    }

    @Test
    fun aFailedHistoryReadDoesNotMoveTheCursor() = runTest {
        val http = RecordingHttp().apply { responses += StoreHttpResponse(503, "{}") }
        val db = Db()
        assertFailsWith<BackendDomainException> { StorePaymentsJob.pollAppleHistory(runtime(db, apple = provider(http)), now) }
        assertEquals(null, db.cursor)
        assertTrue(db.calls("app_store_poll_advance").isEmpty())
    }

    @Test
    fun theVoidedPurchasesWindowComesFromThePersistedCursorNotFromMemory() = runTest {
        val api = StoreGooglePlayProviderTest.FakeApi()
        val starts = CopyOnWriteArrayList<Long>()
        val recording = object : GooglePlayApi by api {
            override fun voided(startMillis: Long, pageToken: String?): JsonObject {
                starts += startMillis
                return Json.parseToJsonElement("""{"voidedPurchases":[{"purchaseToken":"gp-v","orderId":"GPA.9"}]}""").jsonObject
            }
        }
        val google = GooglePlayProvider(GooglePlayConfig("p", "f", "a", "s", "projects/p/subscriptions/rtdn"), recording, GoogleOidcVerifier("a", "s", GoogleTestKeys().source), clock = { now })
        val db = Db()
        val twoDaysAgo = now.minusSeconds(2 * 86_400L)
        db.cursor = twoDaysAgo.toEpochMilli() to twoDaysAgo.toString()
        assertEquals(1, StorePaymentsJob.pollVoided(runtime(db, google = google), now))
        assertEquals(twoDaysAgo.toEpochMilli() - 86_400_000L, starts.single())   // cursor - 1 dia de solape
        assertEquals("void:GPA.9", db.calls("app_store_inbox_put").single()["p_evento_id"]!!.jsonPrimitive.content)
        assertEquals(now.toEpochMilli(), db.cursor!!.first)
        // ya sondeado hoy segun la base: no se vuelve a pedir
        assertEquals(0, StorePaymentsJob.pollVoided(runtime(db, google = google), now.plusSeconds(3_600)))
        assertEquals(1, starts.size)
        // un cursor de hace 90 dias nunca pide mas alla de la ventana de 30 dias de Google
        db.cursor = now.minusSeconds(90 * 86_400L).toEpochMilli() to now.minusSeconds(90 * 86_400L).toString()
        StorePaymentsJob.pollVoided(runtime(db, google = google), now)
        assertTrue(starts.last() >= now.toEpochMilli() - 30 * 86_400_000L)
    }

    @Test
    fun dueSubscriptionsAreReReadFromTheStoreAndApplied() = runTest {
        val api = StoreGooglePlayProviderTest.FakeApi().apply {
            subscription = Json.parseToJsonElement(
                """{"subscriptionState":"SUBSCRIPTION_STATE_EXPIRED","acknowledgementState":"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED","startTime":"2026-10-01T00:00:00Z",""" +
                    """"lineItems":[{"productId":"premium","expiryTime":"2026-10-15T00:00:00Z","offerDetails":{"basePlanId":"monthly"}}]}""",
            ).jsonObject
        }
        val google = GooglePlayProvider(GooglePlayConfig("p", "f", "a", "s", "projects/p/subscriptions/rtdn"), api, GoogleOidcVerifier("a", "s", GoogleTestKeys().source), clock = { now })
        val appleNoApi = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), onlineChecks = false), listOf(pki.rootDer), clock = { now })
        val db = Db().apply {
            due = """[{"proveedor":"google_play","identificador_externo":"gp-sub","tienda_producto_id":"premium/monthly","entorno":"produccion","estado":"activa","fin_en":null},""" +
                """{"proveedor":"app_store","identificador_externo":"1000000001","tienda_producto_id":"com.oposibot.premium","entorno":"produccion","estado":"activa","fin_en":null}]"""
        }
        assertEquals(1, runtime(db, apple = appleNoApi, google = google).service.syncSubscriptions())
        val upsert = db.calls("app_store_upsert_subscription").single()
        assertEquals("gp-sub", upsert["p_id_externo"]!!.jsonPrimitive.content)
        assertEquals("caducada", upsert["p_estado"]!!.jsonPrimitive.content)
        assertEquals("expiro", upsert["p_fin_motivo"]!!.jsonPrimitive.content)
    }
}
