package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** D3-20..D3-22 (estadisticas): lo que es de Ktor. Las reglas (primera respuesta una sola vez, ventanas, evolucion, dificultad) las demuestra la base (assertions.sql). */
class StatsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val exam = "e1e1e1e1-e1e1-4e1e-8e1e-e1e1e1e1e1e1"
    private val question = "f2f2f2f2-f2f2-4f2f-8f2f-f2f2f2f2f2f2"

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
    private val limited = CopyOnWriteArrayList<StatsOperation>()

    private fun Application.statsModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { statsRoutes(StatsRuntime(resolver, StatsService(upstream), limit = { op, _ -> limited += op })) }
    }

    private fun rpcError(message: String) = SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":null,"hint":null}""")
    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    @Test
    fun summaryReadsTheDatabasePayloadForTheJwtSubjectOnly() = testApplication {
        application { statsModule() }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(200, """{"notaMedia":{"valor":7.5,"ventana":8,"respondidas":8,"provisional":false},"evolucion":{"valor":0.5,"referencia":8.0,"cada":20,"progreso":2},"racha":{"actual":1,"maxima":3},"preguntasValidas":10,"totales":{"aciertos":7,"fallos":3,"noContestadas":2}}""")
        }
        val r = createClient { }.getAs("/api/v1/app/estadisticas/resumen")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        assertEquals(7.5, obj(r.bodyAsText())["notaMedia"]!!.jsonObject["valor"]!!.jsonPrimitive.content.toDouble())
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_stats_summary", fn)
        assertEquals(setOf("p_usuario"), obj(args).keys)
        assertEquals(TEST_UID, obj(args)["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(StatsOperation.READ), limited.toList())
    }

    @Test
    fun weaknessesAcceptsOnlyTheDocumentedQueryAndBoundsTheLimit() = testApplication {
        application { statsModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"alcance":"TOTAL","minimoRespuestas":10,"items":[]}""") }
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/estadisticas/debilidades").status)
        var args = obj(upstream.rpcs.last().second)
        assertEquals("10", args["p_limite"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, args["p_examen"])
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/estadisticas/debilidades?examenId=${exam.uppercase()}&top=3").status)
        args = obj(upstream.rpcs.last().second)
        assertEquals(exam, args["p_examen"]!!.jsonPrimitive.content)
        assertEquals("3", args["p_limite"]!!.jsonPrimitive.content)
        upstream.rpcs.clear()
        for (bad in listOf("top=0", "top=51", "top=abc", "examenId=no-uuid", "usuarioId=x", "top=3&otro=1")) {
            assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/estadisticas/debilidades?$bad").status, bad)
        }
        assertTrue(upstream.rpcs.isEmpty(), "una peticion invalida no llega a la base")
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/estadisticas/debilidades", token = null).status)
    }

    @Test
    fun difficultyIsOnlyForAnAnsweredQuestionAndNeverProbes() = testApplication {
        application { statsModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"preguntaId":"$question","dificultad":3}""") }
        val ok = client.getAs("/api/v1/app/estadisticas/dificultad/${question.uppercase()}")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(setOf("p_usuario", "p_pregunta"), obj(upstream.rpcs.single().second).keys)
        assertEquals(question, obj(upstream.rpcs.single().second)["p_pregunta"]!!.jsonPrimitive.content)
        upstream.handler = { _, _ -> rpcError("NOT_FOUND") }
        val nf = client.getAs("/api/v1/app/estadisticas/dificultad/$question")
        assertEquals(HttpStatusCode.NotFound, nf.status, nf.bodyAsText())
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/estadisticas/dificultad/no-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/estadisticas/dificultad/$question?x=1").status)
        assertTrue(upstream.rpcs.isEmpty())
    }
}
