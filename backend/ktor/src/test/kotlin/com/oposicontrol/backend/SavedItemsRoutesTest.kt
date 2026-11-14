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
    private val signed = CopyOnWriteArrayList<Triple<String, String, Int>>()
    private var limitHit = false

    private fun Application.savedModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing {
            savedItemsRoutes(
                SavedItemsRuntime(
                    resolver, SavedItemsService(upstream, sign = { bucket, path, ttl -> signed += Triple(bucket, path, ttl); "https://storage.test/$bucket/$path?token=t" }, clock = { java.time.Instant.parse("2026-11-14T10:00:00Z") }),
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

    // ---------- FA50: D4-24 tipoExamen, D4-29 detalle, D4-28 recurso ----------

    private val docs = com.oposicontrol.backend.openapi.OpenApiDocs.locate()
    private val checker = com.oposicontrol.backend.openapi.SchemaChecker(docs)

    private fun assertConforms(contractId: String, status: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue(status)
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(com.oposicontrol.backend.openapi.OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId $status does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private val exam = "9e9e9e9e-9e9e-4e9e-8e9e-9e9e9e9e9e9e"
    private val optA = "0a0a0a0a-0000-4000-8000-00000000000a"
    private val optB = "0a0a0a0a-0000-4000-8000-00000000000b"
    private val resource = "4f4f4f4f-4f4f-4f4f-8f4f-4f4f4f4f4f4f"

    private fun detail(anulada: Boolean = false, tipoPregunta: String = "inmersiva", tipoExamen: String = "inmersivo") =
        """{"outcome":"OK","pregunta_id":"$question","examen_id":"$exam","examen_titulo":"Inmersivo 2025","tipo_examen":"$tipoExamen","posicion":7,"anulada":$anulada,""" +
            """"contenido":{"v":1,"codigo":"P-7","tipo":"$tipoPregunta","titulo":"Enunciado","opciones":[{"id":"$optB","letra":"B","texto":"No","posicion":2},{"id":"$optA","letra":"A","texto":"Si","posicion":1}],""" +
            """"recursos":[{"id":"$resource","tipo":"audio","titulo":"Caso","posicion":1,"bucket":"recursos-pregunta","ruta":"preguntas/$question/$resource.mp3","mime":"audio/mpeg","bytes":1234,"durationMs":5000,"envelope":null}],""" +
            """"etiquetas":[{"id":"7e7e7e7e-7e7e-4e7e-8e7e-7e7e7e7e7e7e","nombre":"Tema 1"}]},""" +
            """"solucion":{"opcion_correcta_id":"$optA","razonamiento":"Porque si"}}"""

    @Test
    fun theSavedQuestionDetailIsAReviewItemOfTheChosenExamWithoutQuota() = testApplication {
        application { savedModule() }
        upstream.handler = { fn, _ -> if (fn == "app_saved_question_detail") SupabaseRawResponse(200, detail()) else SupabaseRawResponse(500, "{}") }
        val r = createClient { }.getAs("/api/v1/app/opositeca/guardados/pregunta/${question.uppercase()}")
        val body = r.bodyAsText()
        assertEquals(HttpStatusCode.OK, r.status, body)
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        assertConforms("D4-29", "200", body)
        val item = obj(body)
        assertEquals(question, item["preguntaId"]!!.jsonPrimitive.content)
        assertEquals(7, item["posicion"]!!.jsonPrimitive.content.toInt())
        assertEquals("Enunciado", item["enunciado"]!!.jsonPrimitive.content)
        assertEquals("inmersiva", item["tipoPregunta"]!!.jsonPrimitive.content)
        assertEquals(listOf("A", "B"), item["opciones"]!!.jsonArray.map { it.jsonObject["letra"]!!.jsonPrimitive.content }, "options by position")
        assertEquals("UNANSWERED", item["status"]!!.jsonPrimitive.content)
        assertEquals(optA, item["correctAnswer"]!!.jsonObject["opcionId"]!!.jsonPrimitive.content)
        assertEquals("A", item["correctAnswer"]!!.jsonObject["letra"]!!.jsonPrimitive.content)
        assertEquals(exam, item["examenId"]!!.jsonPrimitive.content)
        assertEquals("Inmersivo 2025", item["examenTitulo"]!!.jsonPrimitive.content)
        assertEquals("inmersivo", item["tipoExamen"]!!.jsonPrimitive.content)
        assertEquals("false", item["anulada"]!!.jsonPrimitive.content)
        val res = item["resources"]!!.jsonArray.single().jsonObject
        assertEquals(resource, res["id"]!!.jsonPrimitive.content)
        assertEquals("audio", res["tipo"]!!.jsonPrimitive.content)
        assertTrue("ruta" !in res && "bucket" !in res, "the internal Storage path is never served: $res")
        assertEquals(listOf("Tema 1"), item["etiquetas"]!!.jsonArray.map { it.jsonObject["nombre"]!!.jsonPrimitive.content })
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_saved_question_detail", fn, "one call; the daily limiter (app_reveal_attempt_questions) is never touched")
        assertEquals(mapOf("p_usuario" to TEST_UID, "p_pregunta" to question), obj(args).mapValues { it.value.jsonPrimitive.content })
        assertEquals(listOf(SavedOperation.READ), limited.toList())
    }

    @Test
    fun theExplanationFollowsTheReviewPolicyAndAnAnnulledQuestionHidesTheSolution() = testApplication {
        application { savedModule() }
        val client = createClient { }
        val previous = ExplanationPolicy.visible
        try {
            ExplanationPolicy.visible = true
            upstream.handler = { _, _ -> SupabaseRawResponse(200, detail(tipoPregunta = "razonada", tipoExamen = "razonado")) }
            val shown = obj(client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question").bodyAsText())
            assertEquals("Porque si", shown["explanation"]!!.jsonObject["razonamiento"]!!.jsonPrimitive.content)
            assertEquals("razonado", shown["tipoExamen"]!!.jsonPrimitive.content)
            upstream.handler = { _, _ -> SupabaseRawResponse(200, detail(anulada = true)) }
            val annulled = client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question").bodyAsText()
            assertConforms("D4-29", "200", annulled)
            val a = obj(annulled)
            assertEquals("ANNULLED", a["status"]!!.jsonPrimitive.content)
            assertEquals("true", a["anulada"]!!.jsonPrimitive.content)
            assertNull(a["correctAnswer"], "annulled: no correct answer, as in the review")
            assertNull(a["explanation"])
            ExplanationPolicy.visible = false
            upstream.handler = { _, _ -> SupabaseRawResponse(200, detail()) }
            assertNull(obj(client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question").bodyAsText())["explanation"], "EXPLANATIONS_VISIBLE off: no explanation")
        } finally {
            ExplanationPolicy.visible = previous
        }
    }

    @Test
    fun notSavedOrNoAccessIs404AndABadIdIs400BeforeTheDatabase() = testApplication {
        application { savedModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"outcome":"NOT_FOUND"}""") }
        val missing = client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("NOT_FOUND", code(missing.bodyAsText()))
        assertEquals(HttpStatusCode.NotFound, client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question/recursos/$resource").status)
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/pregunta/no-es-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question?x=1").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question/recursos/nope").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question", token = null).status)
        assertTrue(upstream.rpcs.isEmpty())
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"outcome":"OK"}""") }
        assertEquals(HttpStatusCode.BadGateway, client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question").status, "a partial row is a contract error")
    }

    @Test
    fun aResourceOfTheSavedQuestionIsSignedLikeTheAttemptOnes() = testApplication {
        application { savedModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, detail()) }
        val r = client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question/recursos/${resource.uppercase()}")
        val body = r.bodyAsText()
        assertEquals(HttpStatusCode.OK, r.status, body)
        assertConforms("D4-28", "200", body)
        assertEquals("https://storage.test/recursos-pregunta/preguntas/$question/$resource.mp3?token=t", obj(body)["url"]!!.jsonPrimitive.content)
        assertEquals("2026-11-14T10:05:00Z", obj(body)["expiresAt"]!!.jsonPrimitive.content)
        assertEquals(listOf(Triple("recursos-pregunta", "preguntas/$question/$resource.mp3", 300)), signed.toList())
        val other = "5e5e5e5e-5e5e-4e5e-8e5e-5e5e5e5e5e5e"
        val notThere = client.getAs("/api/v1/app/opositeca/guardados/pregunta/$question/recursos/$other")
        assertEquals(HttpStatusCode.NotFound, notThere.status)
        assertEquals("RESOURCE_NOT_FOUND", code(notThere.bodyAsText()))
        assertEquals(1, signed.size, "nothing outside the question is signed")
    }

    @Test
    fun theListCarriesTheExamType() = testApplication {
        application { savedModule() }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(
                200,
                """[{"id":"8d8d8d8d-8d8d-4d8d-8d8d-000000000001","tipo":"pregunta","target_id":"$question","guardado_en":"2026-10-16T10:00:00.000000+00:00","accesible":true,""" +
                    """"titulo":"T","examen_id":"$exam","examen_titulo":"E","tipo_examen":"rafaga"}]""",
            )
        }
        val body = createClient { }.getAs("/api/v1/app/opositeca").bodyAsText()
        assertConforms("D4-24", "200", body)
        assertEquals("rafaga", obj(body)["items"]!!.jsonArray.single().jsonObject["pregunta"]!!.jsonObject["tipoExamen"]!!.jsonPrimitive.content)
    }
}
