package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guardar + OposiTeca (D4-20, D4-21, D4-24, D4-26): lo que es de Ktor (identidad, forma, traduccion de errores, limites, cursor).
 * La semantica (acceso, tope, idempotencia, aislamiento por usuario) la demuestra la base (`assertions.sql`).
 */
class SavedItemsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val question = "5a5a5a5a-5a5a-4a5a-8a5a-5a5a5a5a5a5a"
    private var profileStatus: String? = "activa"

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> profileStatus?.let { CanonicalUserIdentity(id, AccountStatus.fromWire(it), true) } },
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
    private val limited = CopyOnWriteArrayList<SavedOperation>()
    private var limitHit = false

    private fun Application.savedModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing {
            savedItemsRoutes(
                SavedItemsRuntime(
                    resolver, SavedItemsService(upstream),
                    limit = { op, _ -> limited += op; if (limitHit) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 7) },
                ),
            )
        }
    }

    private fun rpcError(message: String) = SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":null,"hint":null}""")

    private suspend fun HttpClient.putAs(path: String, token: String? = TEST_TOKEN): HttpResponse = put(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.deleteAs(path: String, token: String? = TEST_TOKEN): HttpResponse = delete(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    @Test
    fun saveSendsOnlyTheJwtSubjectTheTipoAndTheQuestionAndAnswers200WithTheServerInstant() = testApplication {
        application { savedModule() }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """[{"guardado_en":"2026-10-16T10:00:00.123456+00:00"}]""") }
        val r = createClient { }.putAs("/api/v1/app/opositeca/guardados/pregunta/${question.uppercase()}")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val body = obj(r.bodyAsText())
        assertEquals("pregunta", body["tipo"]!!.jsonPrimitive.content)
        assertEquals(question, body["targetId"]!!.jsonPrimitive.content)
        assertEquals("2026-10-16T10:00:00.123456+00:00", body["guardadoEn"]!!.jsonPrimitive.content)
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_save_item", fn)
        val a = obj(args)
        assertEquals(setOf("p_usuario", "p_tipo", "p_target"), a.keys)
        assertEquals(TEST_UID, a["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(SavedOperation.WRITE), limited.toList())
    }

    @Test
    fun badShapeIs400BeforeAnyDatabaseCallAndTheIdentityIsRequired() = testApplication {
        application { savedModule() }
        val client = createClient { }
        assertEquals(HttpStatusCode.BadRequest, client.putAs("/api/v1/app/opositeca/guardados/recurso/$question").status)
        assertEquals(HttpStatusCode.BadRequest, client.putAs("/api/v1/app/opositeca/guardados/pregunta/no-es-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.deleteAs("/api/v1/app/opositeca/guardados/pregunta/no-es-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.putAs("/api/v1/app/opositeca/guardados/pregunta/$question?usuarioId=x").status, "un parametro desconocido no se ignora")
        assertEquals(HttpStatusCode.Unauthorized, client.putAs("/api/v1/app/opositeca/guardados/pregunta/$question", token = null).status)
        profileStatus = null
        assertEquals(HttpStatusCode.Forbidden, client.putAs("/api/v1/app/opositeca/guardados/pregunta/$question").status)
        assertTrue(upstream.rpcs.isEmpty(), "nada llega a la base")
    }

    @Test
    fun databaseKeysBecomeCanonicalErrors() = testApplication {
        application { savedModule() }
        val client = createClient { }
        for ((key, status, expected) in listOf(
            Triple("NOT_FOUND", HttpStatusCode.NotFound, "NOT_FOUND"),
            Triple("SAVED_LIMIT_REACHED", HttpStatusCode.UnprocessableEntity, "SAVED_LIMIT_REACHED"),
            Triple("WALLET_INVALID_USER", HttpStatusCode.Forbidden, "PROFILE_MISSING"),
        )) {
            upstream.handler = { _, _ -> rpcError(key) }
            val r = client.putAs("/api/v1/app/opositeca/guardados/pregunta/$question")
            assertEquals(status, r.status, "$key -> ${r.bodyAsText()}")
            assertEquals(expected, code(r.bodyAsText()))
        }
    }

    @Test
    fun unsaveIs204EvenWhenNothingWasSaved() = testApplication {
        application { savedModule() }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, "0") }
        val r = createClient { }.deleteAs("/api/v1/app/opositeca/guardados/pregunta/$question")
        assertEquals(HttpStatusCode.NoContent, r.status)
        assertEquals("app_unsave_item", upstream.rpcs.single().first)
    }

    @Test
    fun theRateLimitIs429WithRetryAfterAndWritesNothing() = testApplication {
        application { savedModule() }
        limitHit = true
        val r = createClient { }.putAs("/api/v1/app/opositeca/guardados/pregunta/$question")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertEquals("7", r.headers[HttpHeaders.RetryAfter])
        assertTrue(upstream.rpcs.isEmpty())
    }

    @Test
    fun stateValidatesTheIdListAndReturnsOnlyTheSavedOnes() = testApplication {
        application { savedModule() }
        val client = createClient { }
        val other = "6b6b6b6b-6b6b-4b6b-8b6b-6b6b6b6b6b6b"
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """[{"pregunta_id":"$question"}]""") }
        val r = client.getAs("/api/v1/app/opositeca/guardados/pregunta?ids=$question,$other,$question")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(listOf(question), obj(r.bodyAsText())["guardados"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(SavedOperation.READ in limited, "INFO: the state read goes through the per-user read limiter (CATALOG_READ class)")
        val sent = obj(upstream.rpcs.single().second)["p_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(question, other), sent, "se normaliza y sin repetidos")
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/pregunta").status, "ids es obligatorio")
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/pregunta?ids=nope").status)
        val fiftyOne = (1..51).joinToString(",") { "7c7c7c7c-7c7c-4c7c-8c7c-%012d".format(it) }
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/pregunta?ids=$fiftyOne").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/recurso?ids=$question").status)
        assertTrue(upstream.rpcs.isEmpty())
    }

    private fun row(i: Int, accesible: Boolean = true) =
        """{"id":"8d8d8d8d-8d8d-4d8d-8d8d-%012d","tipo":"pregunta","target_id":"$question","guardado_en":"2026-10-16T10:00:%02d.000000+00:00","accesible":$accesible,""".format(i, 60 - i) +
            (if (accesible) """"titulo":"T$i","examen_id":"9e9e9e9e-9e9e-4e9e-8e9e-9e9e9e9e9e9e","examen_titulo":"Examen"}""" else """"titulo":null,"examen_id":null,"examen_titulo":null}""")

    @Test
    fun theListIsKeysetPagedHidesTheContentOfInaccessibleItemsAndAsksForOneRowMore() = testApplication {
        application { savedModule() }
        val client = createClient { }
        // limit 2 -> the server asks for 3 rows; the third proves there is a next page
        upstream.handler = { _, _ -> SupabaseRawResponse(200, "[" + listOf(row(1), row(2, accesible = false), row(3)).joinToString(",") + "]") }
        val first = client.getAs("/api/v1/app/opositeca?limit=2")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val page = obj(first.bodyAsText())
        val items = page["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, items.size)
        assertTrue(SavedOperation.READ in limited, "INFO: the list goes through the per-user read limiter (CATALOG_READ class)")
        assertEquals("T1", items[0]["pregunta"]!!.jsonObject["titulo"]!!.jsonPrimitive.content)
        assertEquals(false, items[1]["accesible"]!!.jsonPrimitive.content.toBoolean())
        assertNull(items[1]["pregunta"], "el contenido de lo que ya no se puede abrir no se sirve")
        val cursor = page["nextCursor"]?.jsonPrimitive?.content
        assertNotNull(cursor)
        val args = obj(upstream.rpcs.single().second)
        assertEquals(3, args["p_limit"]!!.jsonPrimitive.content.toInt())
        assertEquals(TEST_UID, args["p_usuario"]!!.jsonPrimitive.content)
        // page 2: the cursor carries [guardado_en, id] of the last returned row, and the user never travels in it
        upstream.rpcs.clear()
        upstream.handler = { _, _ -> SupabaseRawResponse(200, "[" + row(3) + "]") }
        val second = client.getAs("/api/v1/app/opositeca?limit=2&cursor=$cursor")
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        assertNull(obj(second.bodyAsText())["nextCursor"])
        val args2 = obj(upstream.rpcs.single().second)
        assertEquals("2026-10-16T10:00:58.000000+00:00", args2["p_after_ts"]!!.jsonPrimitive.content)
        assertEquals("8d8d8d8d-8d8d-4d8d-8d8d-000000000002", args2["p_after_id"]!!.jsonPrimitive.content)
        // a cursor of another filter or a corrupt one is 400 INVALID_CURSOR and never reaches the database
        upstream.rpcs.clear()
        assertEquals("INVALID_CURSOR", code(client.getAs("/api/v1/app/opositeca?limit=2&tipo=pregunta&cursor=$cursor").bodyAsText()))
        assertEquals("INVALID_CURSOR", code(client.getAs("/api/v1/app/opositeca?cursor=basura").bodyAsText()))
        assertTrue(upstream.rpcs.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca?tipo=examen").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca?orden=asc").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca?limit=0").status)
    }
}
