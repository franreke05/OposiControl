package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CERT-STREAKS-QUESTIONS-2026-10-21 (P2): the legacy `GET /api/v1/app/reto-diario` (D2-05, deprecated) and `/home.retoDiario` read the
 * caller's row of TODAY through `app_legacy_daily_challenge` (server zone + segment, migration 20261022103500). Before, the gateway read
 * `retos_diarios` with the JVM `LocalDate.now()` and no segment, so it could serve another specialty's or community's row.
 */
class AppV1LegacyRetoDiarioTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val retoId = "5e7a0000-0000-4000-8000-000000000001"
    private val row = """[{"id":"$retoId","fecha":"2026-10-25","titulo":"Reto diario","descripcion":null,"objetivo_preguntas":3,"recompensa_puntos":0}]"""

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) },
    )

    private fun Application.module(sent: MutableList<HttpRequest>, responder: (String) -> V1StubResponse) {
        install(ContentNegotiation) { json(json) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AppV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = json,
            client = V1StubHttpClient({ sent += it }, responder),
        )
        routing { appV1Routes(gateway, SupabaseJwtVerifier("https://stub.supabase.test", json), allowDevUser = false, identityResolver = resolver) }
    }

    @Test
    fun theLegacyRouteAsksTheDatabaseForTheCallersRowOfTodayAndNeverReadsTheTableWithTheJvmDate() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { module(sent) { uri -> if (uri.endsWith("/rest/v1/rpc/app_legacy_daily_challenge")) V1StubResponse(200, row) else V1StubResponse(200, "[]") } }
        val response = client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(retoId, data["id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-25", data["fecha"]!!.jsonPrimitive.content)
        assertEquals(3, data["objetivoPreguntas"]!!.jsonPrimitive.content.toInt())
        val rpc = sent.single { it.uri().path.endsWith("/rpc/app_legacy_daily_challenge") }
        assertEquals("POST", rpc.method())
        assertEquals(mapOf("p_usuario" to TEST_UID), Json.parseToJsonElement(readRequestBody(rpc)).jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertTrue(sent.none { it.uri().path.endsWith("/rest/v1/retos_diarios") }, "no direct table read: ${sent.map { it.uri() }}")
    }

    @Test
    fun noRowTodayIsDataNullNotAnotherSegmentsRow() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { module(sent) { V1StubResponse(200, "[]") } }
        val response = client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]
        assertTrue(data == null || data == JsonNull, response.bodyAsText())
    }

    @Test
    fun homeCarriesTheSameRowOfTheCaller() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application { module(sent) { uri -> if (uri.endsWith("/rest/v1/rpc/app_legacy_daily_challenge")) V1StubResponse(200, row) else V1StubResponse(200, "[]") } }
        val response = client.get("/api/v1/app/home") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(retoId, Json.parseToJsonElement(response.bodyAsText()).jsonObject["retoDiario"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val rpc = sent.single { it.uri().path.endsWith("/rpc/app_legacy_daily_challenge") }
        assertEquals(TEST_UID, Json.parseToJsonElement(readRequestBody(rpc)).jsonObject["p_usuario"]!!.jsonPrimitive.content)
        assertTrue(sent.none { it.uri().path.endsWith("/rest/v1/retos_diarios") })
    }
}
