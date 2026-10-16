package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D7-31 / D7-32 (cupo de inmersivos del plan): lo que es de Ktor. Las reglas (cupo, puertas, renovacion) las demuestra la base (assertions.sql). */
class PlanQuotaRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val exam = "e5e5e5e5-e5e5-4e5e-8e5e-e5e5e5e5e5e5"

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
    private val limited = CopyOnWriteArrayList<PlanOperation>()

    private fun Application.planModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { planQuotaRoutes(PlanQuotaRuntime(resolver, PlanQuotaService(upstream), limit = { op, _ -> limited += op })) }
    }

    private fun rpcError(message: String, detail: String? = null) =
        SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":${detail?.let { json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"},"hint":null}""")

    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.putAs(path: String, token: String? = TEST_TOKEN): HttpResponse = put(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    @Test
    fun theSummaryReadsTheDatabasePayloadForTheJwtSubjectOnly() = testApplication {
        application { planModule() }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(
                200,
                """{"planCodigo":"inmersivo","nivel":3,"cupoTotal":2,"cupoUsado":1,"cupoLibre":1,"periodoHasta":"2026-11-16T10:00:00+00:00","limitePreguntas24h":null,"reveladas24h":0,"elegidos":[{"examenId":"$exam","titulo":"Inm 1","desde":"2026-10-16T10:00:00+00:00","hasta":"2026-11-16T10:00:00+00:00"}]}""",
            )
        }
        val r = createClient { }.getAs("/api/v1/app/plan/cupo")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val body = obj(r.bodyAsText())
        assertEquals(3, body["nivel"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["elegidos"]!!.jsonArray.size)
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_plan_quota", fn)
        assertEquals(setOf("p_usuario"), obj(args).keys)
        assertEquals(TEST_UID, obj(args)["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(PlanOperation.READ), limited.toList())
    }

    @Test
    fun chooseIsIdempotentByNatureAndSendsOnlyTheSubjectAndTheExam() = testApplication {
        application { planModule() }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """[{"outcome":"CREATED","examen_id":"$exam","titulo":"Inm 1","desde":"2026-10-16T10:00:00+00:00","hasta":"2026-11-16T10:00:00+00:00"}]""") }
        val r = createClient { }.putAs("/api/v1/app/plan/cupo/${exam.uppercase()}")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(exam, obj(r.bodyAsText())["examenId"]!!.jsonPrimitive.content)
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_choose_plan_exam", fn)
        assertEquals(setOf("p_usuario", "p_examen"), obj(args).keys)
        assertEquals(listOf(PlanOperation.WRITE), limited.toList())
    }

    @Test
    fun databaseKeysBecomeCanonicalErrorsAndBadInputNeverReachesTheDatabase() = testApplication {
        application { planModule() }
        val client = createClient { }
        for ((key, status, expected) in listOf(
            Triple("EXAM_NOT_FOUND", HttpStatusCode.NotFound, "EXAM_NOT_FOUND"),
            Triple("EXAM_NOT_IMMERSIVE", HttpStatusCode.UnprocessableEntity, "EXAM_NOT_IMMERSIVE"),
            Triple("PLAN_QUOTA_NONE", HttpStatusCode.UnprocessableEntity, "PLAN_QUOTA_NONE"),
            Triple("PLAN_QUOTA_FULL", HttpStatusCode.UnprocessableEntity, "PLAN_QUOTA_FULL"),
            Triple("ALREADY_ACCESSIBLE", HttpStatusCode.Conflict, "ALREADY_ACCESSIBLE"),
        )) {
            upstream.handler = { _, _ -> rpcError(key, """{"state":"GRANTED"}""") }
            val r = client.putAs("/api/v1/app/plan/cupo/$exam")
            assertEquals(status, r.status, "$key -> ${r.bodyAsText()}")
            assertEquals(expected, code(r.bodyAsText()))
        }
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.putAs("/api/v1/app/plan/cupo/no-es-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.putAs("/api/v1/app/plan/cupo/$exam?usuarioId=x").status)
        assertEquals(HttpStatusCode.Unauthorized, client.putAs("/api/v1/app/plan/cupo/$exam", token = null).status)
        assertTrue(upstream.rpcs.isEmpty())
    }
}
