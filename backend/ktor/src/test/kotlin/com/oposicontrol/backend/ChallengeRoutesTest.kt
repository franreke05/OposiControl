package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** D3-04 / D3-16..D3-19 (reto diario, racha, reclamar, recursos): lo que es de Ktor. Las reglas (segmento, reloj, tope, diferidos, racha) las demuestra la base (assertions.sql). */
class ChallengeRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val reto = "e1e1e1e1-e1e1-4e1e-8e1e-e1e1e1e1e1e1"
    private val pregunta = "f2f2f2f2-f2f2-4f2f-8f2f-f2f2f2f2f2f2"
    private val opcion = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
    private val recurso = "7e7e7e7e-7e7e-4e7e-8e7e-7e7e7e7e7e7e"

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
    private val limited = CopyOnWriteArrayList<ChallengeOperation>()
    private val signed = CopyOnWriteArrayList<Triple<String, String, Int>>()

    private fun Application.challengeModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing {
            challengeRoutes(
                ChallengeRuntime(
                    resolver,
                    ChallengeService(upstream),
                    signer = { b, p, t -> signed += Triple(b, p, t); "https://signed.example/$p" },
                    limit = { op, _ -> limited += op },
                ),
            )
        }
    }

    private fun rpcError(message: String) = SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":null,"hint":null}""")
    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.postAs(path: String, token: String? = TEST_TOKEN): HttpResponse = post(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse = put(path) {
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    @Test
    fun todayServesMediaMetadataWithoutBucketOrPathAndSendsOnlyTheSubject() = testApplication {
        application { challengeModule() }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(
                200,
                """{"estado":"DISPONIBLE","retoId":"$reto","necesarios":2,"aciertos":0,"preguntas":[{"posicion":1,"tipoSlot":"INMERSIVA","preguntaId":"$pregunta","titulo":"T","opciones":[],"_recursos":[{"id":"$recurso","tipo":"imagen","titulo":"Img","posicion":1,"bucket":"recursos-pregunta","ruta":"preguntas/$pregunta/$recurso.png","mime":"image/png","bytes":1234}]}],"racha":{"actual":0,"maxima":0}}""",
            )
        }
        val r = createClient { }.getAs("/api/v1/app/retos/hoy")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val text = r.bodyAsText()
        assertFalse(text.contains("bucket") || text.contains("ruta") || text.contains("_recursos"), text)
        val q = obj(text)["preguntas"]!!.jsonArray.single().jsonObject
        val res = q["recursos"]!!.jsonArray.single().jsonObject
        assertEquals(recurso, res["id"]!!.jsonPrimitive.content)
        assertEquals("image/png", res["mimeType"]!!.jsonPrimitive.content)
        assertEquals(1234L, res["sizeBytes"]!!.jsonPrimitive.content.toLong())
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_get_daily_challenge", fn)
        assertEquals(setOf("p_usuario"), obj(args).keys)
        assertEquals(listOf(ChallengeOperation.READ), limited.toList())
    }

    @Test
    fun answerIsFinalAndTheBodyIsStrict() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"esCorrecta":true,"opcionCorrectaId":"$opcion","estado":"EN_CURSO","aciertos":1,"respondidas":1}""") }
        val ok = client.putJson("/api/v1/app/retos/${reto.uppercase()}/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion"}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_answer_daily_challenge", fn)
        assertEquals(setOf("p_usuario", "p_reto", "p_pregunta", "p_opcion"), obj(args).keys)
        assertEquals(reto, obj(args)["p_reto"]!!.jsonPrimitive.content)
        assertEquals(listOf(ChallengeOperation.WRITE), limited.toList())
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","usuarioId":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/app/retos/no-uuid/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion"}""").status)
        assertTrue(upstream.rpcs.isEmpty())
        for ((key, status, expected) in listOf(
            Triple("ANSWER_LOCKED", HttpStatusCode.Conflict, "ANSWER_LOCKED"),
            Triple("RETO_OUT_OF_WINDOW", HttpStatusCode.Conflict, "RETO_OUT_OF_WINDOW"),
            Triple("OPTION_NOT_IN_QUESTION", HttpStatusCode.UnprocessableEntity, "OPTION_NOT_IN_QUESTION"),
            Triple("NOT_FOUND", HttpStatusCode.NotFound, "NOT_FOUND"),
        )) {
            upstream.handler = { _, _ -> rpcError(key) }
            val r = client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion"}""")
            assertEquals(status, r.status, "$key -> ${r.bodyAsText()}")
            assertEquals(expected, code(r.bodyAsText()))
        }
    }

    @Test
    fun claimServesPendingAsOkAndMapsTheRefusals() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"recompensa":{"base":20,"bonusRacha":20,"total":40,"reclamada":false,"pendiente":true},"saldo":20}""") }
        val r = client.postAs("/api/v1/app/retos/$reto/reclamar")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(obj(r.bodyAsText())["recompensa"]!!.jsonObject["pendiente"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(setOf("p_usuario", "p_reto"), obj(upstream.rpcs.single().second).keys)
        for ((key, status, expected) in listOf(
            Triple("RETO_NOT_PASSED", HttpStatusCode.Conflict, "RETO_NOT_PASSED"),
            Triple("OT_EARN_CAP_REACHED", HttpStatusCode.Conflict, "OT_EARN_CAP_REACHED"),
            Triple("RETO_CLAIM_EXPIRED", HttpStatusCode.UnprocessableEntity, "RETO_CLAIM_EXPIRED"),
            Triple("RETO_CLAIM_NOT_ELIGIBLE", HttpStatusCode.Forbidden, "RETO_CLAIM_NOT_ELIGIBLE"),
            Triple("EMAIL_UNVERIFIED", HttpStatusCode.Forbidden, "EMAIL_UNVERIFIED"),
            Triple("NOT_FOUND", HttpStatusCode.NotFound, "NOT_FOUND"),
        )) {
            upstream.handler = { _, _ -> rpcError(key) }
            val e = client.postAs("/api/v1/app/retos/$reto/reclamar")
            assertEquals(status, e.status, "$key -> ${e.bodyAsText()}")
            assertEquals(expected, code(e.bodyAsText()))
        }
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/app/retos/$reto/reclamar", token = null).status)
    }

    @Test
    fun resourceIsSignedOnlyWhenTheDatabaseConfirmsTheChain() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        val path = "/api/v1/app/retos/$reto/preguntas/$pregunta/recursos/$recurso"
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"id":"$recurso","tipo":"imagen","titulo":"Img","posicion":1,"bucket":"recursos-pregunta","ruta":"preguntas/$pregunta/$recurso.png","mime":"image/png","bytes":1234}""") }
        val r = client.getAs(path)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(obj(r.bodyAsText())["url"]!!.jsonPrimitive.content.startsWith("https://signed.example/"))
        assertEquals(listOf(Triple("recursos-pregunta", "preguntas/$pregunta/$recurso.png", AttemptService.RESOURCE_URL_TTL_SECONDS)), signed.toList())
        assertEquals("app_challenge_resource", upstream.rpcs.single().first)
        // a broken chain is the SAME 404 and nothing is signed
        signed.clear()
        upstream.handler = { _, _ -> rpcError("NOT_FOUND") }
        val nf = client.getAs(path)
        assertEquals(HttpStatusCode.NotFound, nf.status, nf.bodyAsText())
        assertEquals("RESOURCE_NOT_FOUND", code(nf.bodyAsText()))
        assertTrue(signed.isEmpty())
        // a path outside the Q3 contract is never signed even if the database returned it
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"id":"$recurso","tipo":"imagen","bucket":"recursos-pregunta","ruta":"../etc/passwd"}""") }
        assertEquals(HttpStatusCode.NotFound, client.getAs(path).status)
        assertTrue(signed.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/retos/no-uuid/preguntas/$pregunta/recursos/$recurso").status)
    }

    @Test
    fun streakReadsTheDatabasePayload() = testApplication {
        application { challengeModule() }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"actual":3,"maxima":5,"ultimoDiaSuperado":"2026-10-16"}""") }
        val r = createClient { }.getAs("/api/v1/app/racha")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(3, obj(r.bodyAsText())["actual"]!!.jsonPrimitive.content.toInt())
        assertEquals("app_get_streak", upstream.rpcs.single().first)
    }
}
