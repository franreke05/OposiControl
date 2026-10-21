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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1 economia: canje D6-03, paquetes D6-11, pedidos D6-12..D6-14 y aviso del proveedor, de punta a punta (ruta -> servicio -> RPC)
 * contra un upstream que graba cada llamada. La semantica economica (un debito, un credito, repeticion, reembolso) la demuestra la base
 * (`supabase/baseline/assertions.sql` y el e2e local); aqui se prueba lo que es de Ktor: identidad, forma, traduccion de errores, firma.
 */
class EconomyRoutesTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }
    private val secret = "sandbox-test-secret-0123456789abcdef"
    private val sandbox = SandboxPaymentProvider(secret)
    private val exam = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    private val order = "oooooooo-oooo-4ooo-8ooo-oooooooooooo".replace('o', 'a')
    private val key = "11111111-1111-4111-8111-111111111111"
    private var profileStatus: String? = "activa"

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> profileStatus?.let { CanonicalUserIdentity(id, AccountStatus.fromWire(it), true) } },
    )

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        val gets = CopyOnWriteArrayList<String>()
        var rpcHandler: (String, String) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(500, "{}") }
        var packsBody = "[]"

        override fun get(path: String): String {
            gets += path
            return packsBody
        }

        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return rpcHandler(function, body)
        }
    }

    private val upstream = Upstream()
    private var providers = PaymentProviders(mapOf(sandbox.id to sandbox))

    private fun orderRow(estado: String = "pendiente", outcome: String = "CREATED", saldo: Int = 0) =
        """[{"outcome":"$outcome","orden_id":"$order","estado":"$estado","producto_codigo":"PACK_S","cantidad_tickets":50,"importe_centimos":499,""" +
            """"moneda":"EUR","proveedor":"sandbox","expira_en":"2026-10-15T12:30:00.123456+00:00","pagada_en":null,"transaccion_id":null,"saldo":$saldo}]"""

    private fun redeemRow(outcome: String = "CREATED") =
        """[{"outcome":"$outcome","transaccion_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","examen_id":"$exam","precio_pagado":30,""" +
            """"valido_desde":"2026-10-15T12:00:00+00:00","valido_hasta":"2026-11-14T12:00:00+00:00","comprado_en":"2026-10-15T12:00:00+00:00","saldo":70}]"""

    private fun rpcError(message: String, detail: String? = null) =
        SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":${detail?.let { json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"},"hint":null}""")

    private fun Application.economyModule(limit: suspend (EconomyOperation, CanonicalUserId) -> Unit = { _, _ -> }) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing {
            economyRoutes(EconomyRuntime(resolver, EconomyService(upstream, providers, clock = { now }), InMemoryIdempotencyStore { now }, limit))
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String, idem: String? = key, token: String? = TEST_TOKEN, extra: Map<String, String> = emptyMap()): HttpResponse =
        post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            idem?.let { header("Idempotency-Key", it) }
            extra.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse =
        get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    private fun rpcArgs(index: Int = 0): JsonObject = obj(upstream.rpcs[index].second)

    private fun webhookBody(
        eventId: String = "evt-1",
        type: String = "payment.succeeded",
        amount: Int = 499,
        currency: String = "EUR",
        product: String = "PACK_S",
        orderId: String = order,
    ) = """{"eventId":"$eventId","type":"$type","orderId":"$orderId","transactionId":"tx-1","amountCents":$amount,"currency":"$currency","productCode":"$product"}"""

    private suspend fun HttpClient.webhook(
        body: String,
        provider: String = "sandbox",
        timestamp: Long = now.epochSecond,
        signature: String? = null,
    ): HttpResponse = post("/api/v1/webhooks/payments/$provider") {
        header(SandboxPaymentProvider.TIMESTAMP_HEADER, timestamp.toString())
        header(SandboxPaymentProvider.SIGNATURE_HEADER, signature ?: sandbox.signWebhook(body.toByteArray(), timestamp))
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    // ---------- D6-03 canje ----------

    @Test
    fun redeemReturns201AndSendsOnlyTheJwtSubjectTheExamAndTheKeyToTheDatabase() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow()) }
        val response = createClient { }.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam","expectedPrice":30}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertNull(response.headers["Idempotent-Replayed"])
        val canje = obj(response.bodyAsText())["canje"]!!.jsonObject
        assertEquals("OPOSITICKETS", canje["currency"]!!.jsonPrimitive.content)
        assertEquals("30", canje["pricePaid"]!!.jsonPrimitive.content)
        assertEquals("70", canje["saldo"]!!.jsonPrimitive.content)
        assertEquals("2026-11-14T12:00:00Z", canje["validUntil"]!!.jsonPrimitive.content)
        assertEquals(1, upstream.rpcs.size)
        assertEquals("app_redeem_exam_access", upstream.rpcs[0].first)
        val args = rpcArgs()
        assertEquals(TEST_UID, args["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(exam, args["p_examen"]!!.jsonPrimitive.content)
        assertEquals(key, args["p_idem_key"]!!.jsonPrimitive.content)
        assertEquals("30", args["p_expected_price"]!!.jsonPrimitive.content)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun aDatabaseReplayIsA200WithTheReplayHeaderAndTheSameBody() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow("REPLAY")) }
        val response = createClient { }.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("true", response.headers["Idempotent-Replayed"])
        assertEquals("70", obj(response.bodyAsText())["canje"]!!.jsonObject["saldo"]!!.jsonPrimitive.content)
        assertNull(rpcArgs()["p_expected_price"], "sin expectedPrice no se envia ninguno")
    }

    @Test
    fun theSameKeyAndBodyIsReplayedByTheKtorStoreWithoutTouchingTheDatabaseAgain() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow()) }
        val client = createClient { }
        val first = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        val second = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals("true", second.headers["Idempotent-Replayed"])
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, upstream.rpcs.size, "la repeticion no llega a la base")
    }

    @Test
    fun theSameKeyWithADifferentBodyIs422AndNeverReachesTheDatabase() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow()) }
        val client = createClient { }
        client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        val other = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam","expectedPrice":31}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, other.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(other.bodyAsText()))
        assertEquals(1, upstream.rpcs.size)
    }

    @Test
    fun clientCannotSendUserBalancePriceOrQuantityAndNothingReachesTheDatabase() = testApplication {
        application { economyModule() }
        val client = createClient { }
        for (extra in listOf(""""usuarioId":"$other"""", """"saldo":999""", """"precioPagado":1""", """"cantidad":50""", """"pricePaid":1""")) {
            val response = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam",$extra}""", idem = java.util.UUID.randomUUID().toString())
            assertEquals(HttpStatusCode.BadRequest, response.status, extra)
            assertEquals("VALIDATION", code(response.bodyAsText()), extra)
        }
        assertTrue(upstream.rpcs.isEmpty())
    }

    private val other = "99999999-9999-4999-8999-999999999999"

    @Test
    fun aJsonStringWhereANumberIsExpectedIsRejectedNotCoerced() = testApplication {
        application { economyModule() }
        val response = createClient { }.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam","expectedPrice":"30"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals("VALIDATION", code(response.bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun theOpenOrderCapIs409WithItsOwnCode() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> rpcError("ORDER_PENDING_LIMIT") }
        val response = createClient { }.postJson("/api/v1/app/payments/orders", """{"productCode":"PACK_S","provider":"sandbox"}""")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("ORDER_PENDING_LIMIT", code(response.bodyAsText()))
    }

    @Test
    fun redeemRequiresAuthenticationAKeyAndAWellFormedExam() = testApplication {
        application { economyModule() }
        val client = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""", token = null).status)
        val noKey = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""", idem = null)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(noKey.bodyAsText()))
        val badKey = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""", idem = "no-es-uuid")
        assertEquals("IDEMPOTENCY_KEY_INVALID", code(badKey.bodyAsText()))
        val badExam = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, badExam.status)
        val badPrice = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam","expectedPrice":0}""", idem = java.util.UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.BadRequest, badPrice.status)
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun aBlockedAccountCannotRedeem() = testApplication {
        application { economyModule() }
        profileStatus = "bloqueada"
        val response = createClient { }.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("ACCOUNT_BLOCKED", code(response.bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun theUserRateLimitRunsBeforeAnythingElse() = testApplication {
        application { economyModule(limit = { _, _ -> throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 7) }) }
        val response = createClient { }.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("7", response.headers[HttpHeaders.RetryAfter])
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun databaseRefusalsBecomeCanonicalErrorsWithTheUsersOwnNumbersOnly() = testApplication {
        application { economyModule() }
        val client = createClient { }
        fun case(message: String, detail: String?, status: HttpStatusCode, code: String): Triple<String, String?, Pair<HttpStatusCode, String>> =
            Triple(message, detail, status to code)
        val cases = listOf(
            case("INSUFFICIENT_BALANCE", """{"balance":10,"price":30}""", HttpStatusCode.Conflict, "INSUFFICIENT_BALANCE"),
            case("PRICE_CHANGED", """{"price":40}""", HttpStatusCode.Conflict, "PRICE_CHANGED"),
            case("ALREADY_ACCESSIBLE", """{"state":"SUBSCRIPTION"}""", HttpStatusCode.Conflict, "ALREADY_ACCESSIBLE"),
            case("NOT_PURCHASABLE", null, HttpStatusCode.UnprocessableEntity, "NOT_PURCHASABLE"),
            case("EXAM_NOT_FOUND", null, HttpStatusCode.NotFound, "EXAM_NOT_FOUND"),
            case("IDEMPOTENCY_KEY_REUSED", null, HttpStatusCode.UnprocessableEntity, "IDEMPOTENCY_KEY_REUSED"),
        )
        for ((message, detail, expected) in cases) {
            upstream.rpcHandler = { _, _ -> rpcError(message, detail) }
            val response = client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""", idem = java.util.UUID.randomUUID().toString())
            assertEquals(expected.first, response.status, message)
            val body = obj(response.bodyAsText())
            assertEquals(expected.second, body["code"]!!.jsonPrimitive.content, message)
            val text = response.bodyAsText()
            assertTrue(!text.contains("P0001") && !text.contains("hint"), "no filtra el error crudo de la base: $text")
        }
        upstream.rpcHandler = { _, _ -> rpcError("INSUFFICIENT_BALANCE", """{"balance":10,"price":30}""") }
        val details = obj(client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""", idem = java.util.UUID.randomUUID().toString()).bodyAsText())["details"]!!.jsonObject
        assertEquals("10", details["balance"]!!.jsonPrimitive.content)
        assertEquals("30", details["price"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailedRedeemReleasesTheKeySoTheSameKeyCanBeRetried() = testApplication {
        application { economyModule() }
        val client = createClient { }
        upstream.rpcHandler = { _, _ -> rpcError("INSUFFICIENT_BALANCE", """{"balance":10,"price":30}""") }
        assertEquals(HttpStatusCode.Conflict, client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""").status)
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow()) }
        assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""").status)
        assertEquals(2, upstream.rpcs.size)
    }

    @Test
    fun anUpstreamOutageIs503NotAnInventedSuccess() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(502, "bad gateway") }
        val response = createClient { }.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    // ---------- D6-11 paquetes ----------

    @Test
    fun packsListsOnlyWhatTheDatabaseSellsAndAsksForCompleteProductsOnly() = testApplication {
        application { economyModule() }
        upstream.packsBody = """[{"codigo":"PACK_S","nombre":"Pack S","descripcion":null,"cantidad_tickets":50,"precio_centimos":499,"moneda":"EUR","destacado":true,"etiqueta_promocional":null}]"""
        val response = createClient { }.getAs("/api/v1/app/wallet/paquetes")
        assertEquals(HttpStatusCode.OK, response.status)
        val item = obj(response.bodyAsText())["items"]!!.let { json.parseToJsonElement(it.toString()) }.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }
        assertEquals("PACK_S", item["code"]!!.jsonPrimitive.content)
        assertEquals("499", item["priceCents"]!!.jsonPrimitive.content)
        assertEquals("50", item["tickets"]!!.jsonPrimitive.content)
        val query = upstream.gets.single()
        assertTrue("tipo_producto=eq.pack_tickets" in query && "cantidad_tickets=gt.0" in query && "precio_centimos=gt.0" in query && "visible=eq.true" in query, query)
    }

    @Test
    fun packsRejectsUnknownQueryParametersAndRequiresAuth() = testApplication {
        application { economyModule() }
        val client = createClient { }
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/wallet/paquetes?usuarioId=$other").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/wallet/paquetes", token = null).status)
        assertTrue(upstream.gets.isEmpty())
    }

    // ---------- D6-12 / D6-13 / D6-14 pedidos ----------

    @Test
    fun createOrderFreezesNothingFromTheClientAndReturns201WithLocation() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, orderRow()) }
        val response = createClient { }.postJson("/api/v1/app/payments/orders", """{"productCode":"PACK_S","provider":"Sandbox"}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals("/api/v1/app/payments/orders/$order", response.headers[HttpHeaders.Location])
        val dto = obj(response.bodyAsText())["order"]!!.jsonObject
        assertEquals("PENDING", dto["status"]!!.jsonPrimitive.content)
        assertEquals("499", dto["amountCents"]!!.jsonPrimitive.content)
        assertEquals("2026-10-15T12:30:00.123456Z", dto["expiresAt"]!!.jsonPrimitive.content)
        val args = rpcArgs()
        assertEquals("app_create_payment_order", upstream.rpcs[0].first)
        assertEquals(setOf("p_usuario", "p_producto", "p_proveedor", "p_idem_key"), args.keys)
        assertEquals("sandbox", args["p_proveedor"]!!.jsonPrimitive.content)
        assertEquals(TEST_UID, args["p_usuario"]!!.jsonPrimitive.content)
    }

    @Test
    fun createOrderRejectsClientAmountsUnknownProvidersAndTamperedPackages() = testApplication {
        application { economyModule() }
        val client = createClient { }
        for (extra in listOf(""""importeCentimos":1""", """"amountCents":1""", """"cantidadTickets":9999""", """"tickets":9999""", """"usuarioId":"$other"""")) {
            val response = client.postJson("/api/v1/app/payments/orders", """{"productCode":"PACK_S","provider":"sandbox",$extra}""", idem = java.util.UUID.randomUUID().toString())
            assertEquals(HttpStatusCode.BadRequest, response.status, extra)
        }
        val provider = client.postJson("/api/v1/app/payments/orders", """{"productCode":"PACK_S","provider":"stripe"}""", idem = java.util.UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.UnprocessableEntity, provider.status)
        assertEquals("PAYMENT_PROVIDER_UNAVAILABLE", code(provider.bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty(), "ni un proveedor desconocido ni un campo prohibido llegan a la base")
        upstream.rpcHandler = { _, _ -> rpcError("PRODUCT_NOT_AVAILABLE") }
        val tampered = client.postJson("/api/v1/app/payments/orders", """{"productCode":"PACK_XXL","provider":"sandbox"}""", idem = java.util.UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.UnprocessableEntity, tampered.status)
        assertEquals("PRODUCT_NOT_AVAILABLE", code(tampered.bodyAsText()))
    }

    @Test
    fun getOrderIsOwnerScopedAndAnotherUsersOrderIs404() = testApplication {
        application { economyModule() }
        val client = createClient { }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, orderRow(outcome = "READ")) }
        val ok = client.getAs("/api/v1/app/payments/orders/$order")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(TEST_UID, rpcArgs()["p_usuario"]!!.jsonPrimitive.content)
        upstream.rpcHandler = { _, _ -> rpcError("ORDER_NOT_FOUND") }
        val foreign = client.getAs("/api/v1/app/payments/orders/$order")
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("ORDER_NOT_FOUND", code(foreign.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/payments/orders/no-es-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/payments/orders/$order?usuarioId=$other").status)
    }

    @Test
    fun verifyCreditsOnlyWithAReceiptBoundToThisUserAndOrder() = testApplication {
        application { economyModule() }
        val client = createClient { }
        upstream.rpcHandler = { fn, _ -> SupabaseRawResponse(200, if (fn == "app_get_payment_order") orderRow(outcome = "READ") else orderRow("pagada", "CREDITED", 50)) }
        val good = sandbox.issueReceipt(TEST_UID, order, "evt-9", "tx-9", 499, "EUR", "PACK_S")
        val ok = client.postJson("/api/v1/app/payments/orders/$order/verify", """{"receipt":"$good"}""", idem = null)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("PAID", obj(ok.bodyAsText())["order"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("app_get_payment_order", "app_credit_payment_order"), upstream.rpcs.map { it.first })
        val credit = obj(upstream.rpcs[1].second)
        assertEquals("evt-9", credit["p_evento_id"]!!.jsonPrimitive.content)
        assertEquals("499", credit["p_importe"]!!.jsonPrimitive.content)

        upstream.rpcs.clear()
        val foreign = sandbox.issueReceipt(other, order, "evt-10", "tx-10", 499, "EUR", "PACK_S")
        val wrongUser = client.postJson("/api/v1/app/payments/orders/$order/verify", """{"receipt":"$foreign"}""", idem = null)
        assertEquals(HttpStatusCode.UnprocessableEntity, wrongUser.status)
        assertEquals("PAYMENT_RECEIPT_INVALID", code(wrongUser.bodyAsText()))
        val otherOrder = sandbox.issueReceipt(TEST_UID, "ffffffff-ffff-4fff-8fff-ffffffffffff", "evt-11", "tx-11", 499, "EUR", "PACK_S")
        assertEquals("PAYMENT_RECEIPT_INVALID", code(client.postJson("/api/v1/app/payments/orders/$order/verify", """{"receipt":"$otherOrder"}""", idem = null).bodyAsText()))
        val tampered = good.dropLast(2) + (if (good.endsWith("00")) "11" else "00")
        assertEquals("PAYMENT_RECEIPT_INVALID", code(client.postJson("/api/v1/app/payments/orders/$order/verify", """{"receipt":"$tampered"}""", idem = null).bodyAsText()))
        assertTrue(upstream.rpcs.none { it.first == "app_credit_payment_order" }, "un justificante invalido no acredita")
    }

    // ---------- W-01 aviso del proveedor ----------

    @Test
    fun aSignedPaidEventCreditsWithTheProvidersOwnAmountNotTheClients() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, orderRow("pagada", "CREDITED", 50)) }
        val response = createClient { }.webhook(webhookBody(amount = 499))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("CREDITED", obj(response.bodyAsText())["result"]!!.jsonPrimitive.content)
        assertEquals("app_credit_payment_order", upstream.rpcs.single().first)
        val args = rpcArgs()
        assertEquals("sandbox", args["p_proveedor"]!!.jsonPrimitive.content)
        assertEquals("evt-1", args["p_evento_id"]!!.jsonPrimitive.content)
        assertEquals("499", args["p_importe"]!!.jsonPrimitive.content)
        assertEquals("EUR", args["p_moneda"]!!.jsonPrimitive.content)
        assertEquals("PACK_S", args["p_producto"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWrongAmountIsForwardedUntouchedSoTheDatabaseSendsTheOrderToReview() = testApplication {
        application { economyModule() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, orderRow("en_revision", "EN_REVISION", 0)) }
        val response = createClient { }.webhook(webhookBody(amount = 1))
        assertEquals("EN_REVISION", obj(response.bodyAsText())["result"]!!.jsonPrimitive.content)
        assertEquals("1", rpcArgs()["p_importe"]!!.jsonPrimitive.content)
    }

    @Test
    fun aDuplicateCallbackIsAnsweredFromTheDatabaseAsAReplay() = testApplication {
        application { economyModule() }
        var calls = 0
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, if (calls++ == 0) orderRow("pagada", "CREDITED", 50) else orderRow("pagada", "REPLAY", 50)) }
        val client = createClient { }
        val body = webhookBody()
        assertEquals("CREDITED", obj(client.webhook(body).bodyAsText())["result"]!!.jsonPrimitive.content)
        val second = client.webhook(body)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("REPLAY", obj(second.bodyAsText())["result"]!!.jsonPrimitive.content)
        assertEquals(2, upstream.rpcs.size, "Ktor no deduplica: lo hace la base, tambien si se pierde la respuesta")
        assertEquals(upstream.rpcs[0].second, upstream.rpcs[1].second)
    }

    @Test
    fun aRefundEventCallsTheRefundFunctionAndAnUnknownEventTypeWritesNothing() = testApplication {
        application { economyModule() }
        val client = createClient { }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, orderRow("reembolsada", "REFUNDED", 0)) }
        assertEquals("REFUNDED", obj(client.webhook(webhookBody(eventId = "evt-r", type = "payment.refunded")).bodyAsText())["result"]!!.jsonPrimitive.content)
        assertEquals("app_refund_payment_order", upstream.rpcs.single().first)
        upstream.rpcs.clear()
        assertEquals("IGNORED", obj(client.webhook(webhookBody(eventId = "evt-x", type = "payment.pending")).bodyAsText())["result"]!!.jsonPrimitive.content)
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun anOutOfOrderRefundIsHeldAsInReviewAnsweredWith200AndTheLaterPaymentIsNotCredited() = testApplication {
        // SEC-V1-05 (20261022102400): the database HOLDS a refund that arrives before the payment (order -> en_revision, answer EN_REVISION).
        // Ktor must acknowledge it with 200 (the refund IS recorded, the provider must stop retrying) and must not turn it into an error or a retry.
        application { economyModule() }
        val client = createClient { }
        upstream.rpcHandler = { function, _ ->
            when (function) {
                "app_refund_payment_order" -> SupabaseRawResponse(200, orderRow("en_revision", if (upstream.rpcs.size == 1) "EN_REVISION" else "REPLAY", 0))
                else -> SupabaseRawResponse(200, orderRow("en_revision", "EN_REVISION", 0))
            }
        }
        val refund = client.webhook(webhookBody(eventId = "evt-r1", type = "payment.refunded"))
        assertEquals(HttpStatusCode.OK, refund.status, refund.bodyAsText())
        assertEquals("EN_REVISION", obj(refund.bodyAsText())["result"]!!.jsonPrimitive.content)
        val paid = client.webhook(webhookBody(eventId = "evt-p2"))
        assertEquals(HttpStatusCode.OK, paid.status, paid.bodyAsText())
        assertEquals("EN_REVISION", obj(paid.bodyAsText())["result"]!!.jsonPrimitive.content, "the payment after a held refund is not credited")
        val retry = client.webhook(webhookBody(eventId = "evt-r1", type = "payment.refunded"))
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals("REPLAY", obj(retry.bodyAsText())["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("app_refund_payment_order", "app_credit_payment_order", "app_refund_payment_order"), upstream.rpcs.map { it.first })
        assertEquals("evt-r1", rpcArgs(0)["p_evento_id"]!!.jsonPrimitive.content)
        assertEquals("evt-p2", rpcArgs(1)["p_evento_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun withTheRefundPathOffARefundEventWritesNothingAndIs503SoTheProviderRetries() = testApplication {
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { economyRoutes(EconomyRuntime(resolver, EconomyService(upstream, providers, clock = { now }, refundsEnabled = false), InMemoryIdempotencyStore { now })) }
        }
        val client = createClient { }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, orderRow("reembolsada", "REFUNDED", 0)) }
        val refund = client.webhook(webhookBody(eventId = "evt-off", type = "payment.refunded"))
        assertEquals(HttpStatusCode.ServiceUnavailable, refund.status, refund.bodyAsText())
        assertTrue(upstream.rpcs.isEmpty(), "apagada: ni una llamada a la base")
    }

    @Test
    fun theRefundPathIsOnlyOnByDefaultInLocalAndTestAndAnExplicitValueWins() {
        fun on(vararg kv: Pair<String, String>) = refundsEnabledFrom(mapOf(*kv)::get)
        assertTrue(on("APP_ENV" to "local"))
        assertTrue(on("APP_ENV" to "test"))
        assertEquals(false, on("APP_ENV" to "production"))
        assertEquals(false, on("APP_ENV" to "Local"))
        assertEquals(false, on())
        assertEquals(false, on("APP_ENV" to "local", "PAYMENTS_REFUNDS_ENABLED" to "false"))
        assertTrue(on("APP_ENV" to "production", "PAYMENTS_REFUNDS_ENABLED" to "true"))
    }

    @Test
    fun badSignatureStaleTimestampAndTamperedBodyAre401WithZeroWrites() = testApplication {
        application { economyModule() }
        val client = createClient { }
        val body = webhookBody()
        val forged = client.webhook(body, signature = "0".repeat(64))
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertEquals("WEBHOOK_SIGNATURE_INVALID", code(forged.bodyAsText()))
        val stale = client.webhook(body, timestamp = now.epochSecond - 301)
        assertEquals(HttpStatusCode.Unauthorized, stale.status)
        val future = client.webhook(body, timestamp = now.epochSecond + 301)
        assertEquals(HttpStatusCode.Unauthorized, future.status)
        val signedForAnother = sandbox.signWebhook(webhookBody(amount = 1).toByteArray(), now.epochSecond)
        val tampered = client.webhook(body, signature = signedForAnother)
        assertEquals(HttpStatusCode.Unauthorized, tampered.status)
        val noHeaders = client.post("/api/v1/webhooks/payments/sandbox") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Unauthorized, noHeaders.status)
        assertTrue(upstream.rpcs.isEmpty())
        assertTrue(!forged.bodyAsText().contains(secret))
    }

    @Test
    fun aWebhookForAnUnregisteredProviderIs404AndAnOversizedBodyIs413BeforeAnySignatureWork() = testApplication {
        application { economyModule() }
        val client = createClient { }
        assertEquals(HttpStatusCode.NotFound, client.webhook(webhookBody(), provider = "stripe").status)
        val huge = "x".repeat((WEBHOOK_BODY_MAX_BYTES + 1).toInt())
        val response = client.post("/api/v1/webhooks/payments/sandbox") {
            header(SandboxPaymentProvider.TIMESTAMP_HEADER, now.epochSecond.toString())
            header(SandboxPaymentProvider.SIGNATURE_HEADER, "0".repeat(64))
            contentType(ContentType.Application.Json)
            setBody(huge)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun theWebhookNeedsNoJwtButIsRateLimitedByIp() = testApplication {
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                economyRoutes(
                    EconomyRuntime(
                        resolver, EconomyService(upstream, providers, clock = { now }), InMemoryIdempotencyStore { now },
                        webhookLimit = { throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 3) },
                    ),
                )
            }
        }
        val response = createClient { }.webhook(webhookBody())
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun withoutARuntimeTheEconomyRoutesFailClosed() = testApplication {
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { economyRoutes(null) }
        }
        val client = createClient { }
        assertEquals(HttpStatusCode.InternalServerError, client.postJson("/api/v1/app/wallet/canjes", """{"examId":"$exam"}""").status)
        assertEquals(HttpStatusCode.InternalServerError, client.webhook(webhookBody()).status)
    }

    // ---------- guarda del proveedor sandbox ----------

    @Test
    fun theSandboxProviderIsRefusedOutsideExactlyLocalOrTest() {
        fun env(appEnv: String?, sandboxSecret: String? = secret) = { name: String ->
            when (name) {
                "APP_ENV" -> appEnv
                SANDBOX_SECRET_ENV -> sandboxSecret
                else -> null
            }
        }
        for (bad in listOf("production", "prod", "staging", "development", "dev", "Local", " local", "local ", "test2", "TEST", "", null)) {
            assertNotNull(sandboxGuardViolation(env(bad)), "APP_ENV=${bad ?: "<unset>"} debe rechazarse")
            assertFailsWith<IllegalStateException>("APP_ENV=${bad ?: "<unset>"}") { PaymentProviders.fromEnvironment(env(bad)) }
            var failed: String? = null
            runCatching { enforceSandboxProviderGuard(env(bad)) { message -> failed = message; throw IllegalStateException(message) } }
            assertNotNull(failed, "el arranque debe abortar con APP_ENV=${bad ?: "<unset>"}")
            assertTrue(!failed!!.contains(secret), "el mensaje nunca lleva el secreto")
        }
        for (good in listOf("local", "test")) {
            assertNull(sandboxGuardViolation(env(good)))
            assertEquals(setOf("sandbox"), PaymentProviders.fromEnvironment(env(good)).ids)
        }
        // sin secreto no hay sandbox y nada que guardar, sea cual sea el entorno
        for (any in listOf("production", "local", null)) {
            assertNull(sandboxGuardViolation(env(any, sandboxSecret = null)))
            assertEquals(emptySet(), PaymentProviders.fromEnvironment(env(any, sandboxSecret = null)).ids)
        }
    }

    @Test
    fun theSandboxSecretMustBeLongEnough() {
        assertFailsWith<IllegalArgumentException> { SandboxPaymentProvider("short") }
    }
}
