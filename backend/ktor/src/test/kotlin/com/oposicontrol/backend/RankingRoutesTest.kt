package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** D3-24 / D3-25 (ranking): lo que es de Ktor. Las reglas (posiciones, probabilidad, cohorte minima, top 3 con opt-in) las demuestra la base (assertions.sql). */
class RankingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) },
    )

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        var handler: (String, String) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(500, "{}") }
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return handler(function, body)
        }
    }

    private val upstream = Upstream()
    private val limited = CopyOnWriteArrayList<RankingOperation>()

    private fun Application.rankingModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { rankingRoutes(RankingRuntime(resolver, RankingService(upstream), limit = { op, _ -> limited += op })) }
    }

    private fun rpcError(message: String) = SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":null,"hint":null}""")
    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.putJson(path: String, body: String, token: String? = TEST_TOKEN): HttpResponse = put(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    @Test
    fun viewReadsTheDatabasePayloadForTheJwtSubjectOnly() = testApplication {
        application { rankingModule() }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(200, """{"liga":{"codigo":"liga-50","nombre":"Liga 50"},"posicion":4,"notaMedia":6.0,"top3":[{"posicion":1,"handle":"ana","notaMedia":9.0}],"probabilidadPlaza":{"disponible":false,"motivo":"cohorte_insuficiente"},"visibilidad":{"publico":false}}""")
        }
        val client = createClient { }
        val r = client.getAs("/api/v1/app/ranking")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        assertEquals(4, obj(r.bodyAsText())["posicion"]!!.jsonPrimitive.content.toInt())
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_ranking_me", fn)
        assertEquals(setOf("p_usuario"), obj(args).keys)
        assertEquals(TEST_UID, obj(args)["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(RankingOperation.READ), limited.toList())
        upstream.rpcs.clear()
        for (bad in listOf("usuarioId=x", "limit=3")) assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/ranking?$bad").status, bad)
        assertTrue(upstream.rpcs.isEmpty(), "una peticion invalida no llega a la base")
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/ranking", token = null).status)
    }

    @Test
    fun visibilityIsStrictAndMapsUsernameRequired() = testApplication {
        application { rankingModule() }
        val client = createClient { }
        upstream.handler = { _, body -> SupabaseRawResponse(200, """{"publico":${obj(body)["p_publico"]!!.jsonPrimitive.content}}""") }
        val ok = client.putJson("/api/v1/app/ranking/visibilidad", """{"publico":true}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("true", obj(ok.bodyAsText())["publico"]!!.jsonPrimitive.content)
        val args = obj(upstream.rpcs.single().second)
        assertEquals(setOf("p_usuario", "p_publico"), args.keys)
        assertEquals(TEST_UID, args["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(RankingOperation.WRITE), limited.toList())
        upstream.rpcs.clear()
        for (bad in listOf("""{}""", """{"publico":"yes"}""", """{"publico":true,"usuarioId":"x"}""", """{"publico":null}""", "[]")) {
            assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/app/ranking/visibilidad", bad).status, bad)
        }
        assertTrue(upstream.rpcs.isEmpty(), "un cuerpo invalido no llega a la base")
        upstream.handler = { _, _ -> rpcError("USERNAME_REQUIRED") }
        val need = client.putJson("/api/v1/app/ranking/visibilidad", """{"publico":true}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, need.status, need.bodyAsText())
        assertEquals("USERNAME_REQUIRED", obj(need.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.putJson("/api/v1/app/ranking/visibilidad", """{"publico":true}""", token = null).status)
    }
}
