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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F28 (FA52): D6-39 Market en tres secciones y D6-40 canje de un consumible con OT, de punta a punta (ruta -> servicio -> RPC) contra un
 * upstream que graba cada llamada. La semantica (debito atomico, activacion, repeticion por clave, saldo insuficiente) la prueba la base
 * (`V1ServiciosCanjeMigrationTest`, gate PG17); aqui: identidad, forma, idempotencia del plugin y traduccion de errores.
 */
class MarketServicesRoutesTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "11111111-1111-4111-8111-111111111111"
    private val activation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) },
    )

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        var rpcHandler: (String, String) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(500, "{}") }

        override fun get(path: String): String = "[]"

        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return rpcHandler(function, body)
        }
    }

    private val upstream = Upstream()

    private fun Application.module() {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing {
            economyRoutes(EconomyRuntime(resolver, EconomyService(upstream, PaymentProviders(emptyMap()), clock = { now }), InMemoryIdempotencyStore { now }))
        }
    }

    private val catalog = """
        {"secciones":[
          {"codigo":"OPOSITICKETS","titulo":"OposiTickets","items":[{"codigo":"pack_1300","nombre":"1.300 OT","descripcion":null,"tipo":"pack_tickets","precioTickets":null,
            "precioCentimos":1299,"usos":null,"duracionHoras":null,"multiplicador":null,"disponibles":0,"comprableConTickets":false,"cantidadTickets":1300}]},
          {"codigo":"POTENCIADORES","titulo":"Potenciadores y protecciones","items":[
            {"codigo":"protector_racha","nombre":"Protector de racha","descripcion":"d","tipo":"protector_racha","precioTickets":20,"precioCentimos":null,"usos":1,
             "duracionHoras":null,"multiplicador":null,"disponibles":2,"comprableConTickets":true,"cantidadTickets":null},
            {"codigo":"potenciador_x2","nombre":"Potenciador x2","descripcion":null,"tipo":"potenciador","precioTickets":30,"precioCentimos":null,"usos":1,
             "duracionHoras":24,"multiplicador":2.0,"disponibles":0,"comprableConTickets":true,"cantidadTickets":null}]}
        ]}
    """.trimIndent()

    private fun redeemRow(outcome: String = "CREATED", saldo: Int = 80) =
        """[{"outcome":"$outcome","transaccion_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","saldo":$saldo,"activacion":{"id":"${activation.uppercase()}","servicioCodigo":"potenciador_x2",""" +
            """"tipo":"potenciador","estado":"activa","usosRestantes":1,"activaHasta":"2026-11-15T12:00:00.123456+00:00","multiplicador":2}}]"""

    private fun rpcError(message: String, detail: String? = null) =
        SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":${detail?.let { json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"},"hint":null}""")

    private suspend fun HttpClient.canje(codigo: String = "potenciador_x2", body: String? = null, idem: String? = key): HttpResponse =
        post("/api/v1/app/wallet/servicios/$codigo/canje") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            idem?.let { header("Idempotency-Key", it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    // ---------- D6-39 ----------

    @Test
    fun theCatalogIsTheDatabaseAnswerForTheJwtSubjectWithNoStore() = testApplication {
        application { module() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, catalog) }
        val response = createClient { }.get("/api/v1/app/market/servicios") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("app_market_catalog", upstream.rpcs.single().first)
        assertEquals("""{"p_usuario":"$TEST_UID"}""", upstream.rpcs.single().second, "only the JWT subject, nothing from the client")
        val sections = obj(response.bodyAsText())["secciones"]!!.jsonArray
        assertEquals(listOf("OPOSITICKETS", "POTENCIADORES"), sections.map { it.jsonObject["codigo"]!!.jsonPrimitive.content })
        val protector = sections[1].jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("2", protector["disponibles"]!!.jsonPrimitive.content)
        assertEquals("true", protector["comprableConTickets"]!!.jsonPrimitive.content)
        assertFalse("multiplicador" in protector, "nulls are omitted on the wire (absent = null)")
        assertEquals("1300", sections[0].jsonObject["items"]!!.jsonArray[0].jsonObject["cantidadTickets"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnknownRepeatedOrEmptySectionFailsClosed() = testApplication {
        application { module() }
        val client = createClient { }
        for (bad in listOf(
            """{"secciones":[{"codigo":"OFERTAS","titulo":"x","items":[]}]}""",
            """{"secciones":[{"codigo":"SERVICIOS","titulo":"s","items":[{"codigo":"a","nombre":"a","tipo":"tutoria","disponibles":0,"comprableConTickets":false}]},""" +
                """{"codigo":"POTENCIADORES","titulo":"p","items":[{"codigo":"b","nombre":"b","tipo":"potenciador","disponibles":0,"comprableConTickets":true}]}]}""",
            """{"secciones":[{"codigo":"SERVICIOS","titulo":"s","items":[]}]}""",
            """{"secciones":[{"codigo":"SERVICIOS","titulo":"s","items":[{"codigo":"a","nombre":"a","tipo":"tutoria","disponibles":-1,"comprableConTickets":false}]}]}""",
        )) {
            upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, bad) }
            val response = client.get("/api/v1/app/market/servicios") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.BadGateway, response.status, bad)
            assertEquals("UPSTREAM_CONTRACT_ERROR", code(response.bodyAsText()))
        }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, """{"secciones":[]}""") }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/app/market/servicios") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }.status, "an empty Market is valid")
    }

    @Test
    fun theCatalogNeedsAToken() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.get("/api/v1/app/market/servicios").status)
        assertTrue(upstream.rpcs.isEmpty())
    }

    // ---------- D6-40 ----------

    @Test
    fun aPurchaseIs201SendsOnlySubjectCodeKeyAndGuardAndTheSameKeyReplays() = testApplication {
        application { module() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow()) }
        val client = createClient { }
        val first = client.canje(body = """{"expectedPrice":30}""")
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertNull(first.headers["Idempotent-Replayed"])
        val args = obj(upstream.rpcs.single().second)
        assertEquals("app_redeem_service", upstream.rpcs.single().first)
        assertEquals(setOf("p_usuario", "p_codigo", "p_idem_key", "p_expected_price"), args.keys)
        assertEquals(TEST_UID, args["p_usuario"]!!.jsonPrimitive.content)
        assertEquals("potenciador_x2|$key|30", listOf("p_codigo", "p_idem_key", "p_expected_price").joinToString("|") { args[it]!!.jsonPrimitive.content })
        val body = obj(first.bodyAsText())
        assertEquals("80", body["saldoTickets"]!!.jsonPrimitive.content)
        val act = body["activacion"]!!.jsonObject
        assertEquals(activation, act["id"]!!.jsonPrimitive.content, "lower-cased uuid")
        assertEquals("2026-11-15T12:00:00.123456Z", act["activaHasta"]!!.jsonPrimitive.content, "normalised instant")
        assertEquals("1|potenciador|activa", listOf("usosRestantes", "tipo", "estado").joinToString("|") { act[it]!!.jsonPrimitive.content })

        val again = client.canje(body = """{"expectedPrice":30}""")
        assertEquals(HttpStatusCode.Created, again.status, "the plugin replays the stored status")
        assertEquals("true", again.headers["Idempotent-Replayed"])
        assertEquals(1, upstream.rpcs.size, "the replay never reaches the database again")
    }

    @Test
    fun theBodyIsOptionalAndADatabaseReplayIs200() = testApplication {
        application { module() }
        upstream.rpcHandler = { _, _ -> SupabaseRawResponse(200, redeemRow(outcome = "REPLAY")) }
        val response = createClient { }.canje(codigo = "protector_racha")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("true", response.headers["Idempotent-Replayed"])
        assertEquals(setOf("p_usuario", "p_codigo", "p_idem_key"), obj(upstream.rpcs.single().second).keys, "no guard when the client sent none")
    }

    @Test
    fun insufficientBalanceIs409WithBalanceAndPrice() = testApplication {
        application { module() }
        upstream.rpcHandler = { _, _ -> rpcError("INSUFFICIENT_BALANCE", """{"balance":10,"price":30}""") }
        val response = createClient { }.canje()
        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = obj(response.bodyAsText())
        assertEquals("INSUFFICIENT_BALANCE", body["code"]!!.jsonPrimitive.content)
        assertEquals("10|30", listOf("balance", "price").joinToString("|") { body["details"]!!.jsonObject[it]!!.jsonPrimitive.content })
    }

    @Test
    fun theDomainRefusalsMapToTheirCodes() = testApplication {
        application { module() }
        val client = createClient { }
        for ((err, expected) in listOf(
            Triple("SERVICE_NOT_FOUND", null, HttpStatusCode.NotFound to "NOT_FOUND"),
            Triple("SERVICE_NOT_PURCHASABLE", null, HttpStatusCode.UnprocessableEntity to "NOT_PURCHASABLE"),
            Triple("PRICE_CHANGED", """{"price":40}""", HttpStatusCode.Conflict to "PRICE_CHANGED"),
            Triple("IDEMPOTENCY_KEY_REUSED", null, HttpStatusCode.UnprocessableEntity to "IDEMPOTENCY_KEY_REUSED"),
        ).map { (m, d, e) -> (m to d) to e }) {
            upstream.rpcHandler = { _, _ -> rpcError(err.first, err.second) }
            val response = client.canje(idem = java.util.UUID.randomUUID().toString())
            assertEquals(expected.first, response.status, err.first)
            assertEquals(expected.second, code(response.bodyAsText()), err.first)
            if (err.first == "PRICE_CHANGED") {
                val body = obj(response.bodyAsText())
                assertTrue(body["message"]!!.jsonPrimitive.content.contains("servicio"), "the service wording, not the exam one")
                assertEquals("40", body["details"]!!.jsonObject["price"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun keyCodeAndGuardAreValidatedBeforeTheDatabase() = testApplication {
        application { module() }
        val client = createClient { }
        val noKey = client.canje(idem = null)
        assertEquals(HttpStatusCode.BadRequest, noKey.status)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(noKey.bodyAsText()))
        assertEquals("VALIDATION", code(client.canje(codigo = "a%20b", idem = "22222222-2222-4222-8222-222222222222").bodyAsText()))
        assertEquals("VALIDATION", code(client.canje(body = """{"expectedPrice":"30"}""", idem = "33333333-3333-4333-8333-333333333333").bodyAsText()), "a JSON string is not a number")
        assertEquals("VALIDATION", code(client.canje(body = """{"expectedPrice":0}""", idem = "44444444-4444-4444-8444-444444444444").bodyAsText()))
        assertEquals("VALIDATION", code(client.canje(body = """{"precio":30}""", idem = "55555555-5555-4555-8555-555555555555").bodyAsText()), "unknown field")
        assertTrue(upstream.rpcs.isEmpty(), "nothing reached the database")
    }
}
