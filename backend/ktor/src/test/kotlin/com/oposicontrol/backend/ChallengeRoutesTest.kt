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
    private val ipChecks = java.util.concurrent.atomic.AtomicInteger()

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
                    ipLimit = { ipChecks.incrementAndGet() },
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

    /** F20 / FA46: a reto of 1..3 questions (one per exam type that has questions). Ktor serves the database count and pass rule verbatim. */
    @Test
    fun aOneQuestionChallengeIsServedAsTheDatabaseBuiltIt() = testApplication {
        application { challengeModule() }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(
                200,
                """{"estado":"DISPONIBLE","retoId":"$reto","necesarios":1,"aciertos":0,"preguntas":[{"posicion":1,"tipoSlot":"RAFAGA","preguntaId":"$pregunta","titulo":"T","opciones":[{"id":"$opcion","letra":"A","texto":"Si"}],"_recursos":[]}],"racha":{"actual":0,"maxima":0}}""",
            )
        }
        val r = createClient { }.getAs("/api/v1/app/retos/hoy")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = obj(r.bodyAsText())
        assertEquals(1, body["necesarios"]!!.jsonPrimitive.content.toInt())
        val q = body["preguntas"]!!.jsonArray.single().jsonObject
        assertEquals("RAFAGA", q["tipoSlot"]!!.jsonPrimitive.content)
        assertEquals(1, q["posicion"]!!.jsonPrimitive.content.toInt())
        assertFalse(q.containsKey("_recursos") || q.containsKey("recursos"), q.toString())
    }

    /** F20b / FA55: the reto is filled to 3 from the eligible questions, so a tipoSlot can repeat. Ktor keeps every question and its order. */
    @Test
    fun aFilledChallengeWithARepeatedSlotKeepsEveryQuestion() = testApplication {
        application { challengeModule() }
        val ids = listOf("f3f3f3f3-f3f3-4f3f-8f3f-f3f3f3f3f301", "f3f3f3f3-f3f3-4f3f-8f3f-f3f3f3f3f302", "f3f3f3f3-f3f3-4f3f-8f3f-f3f3f3f3f303")
        val qs = ids.mapIndexed { i, id -> """{"posicion":${i + 1},"tipoSlot":"RAFAGA","preguntaId":"$id","titulo":"T$i","opciones":[],"_recursos":[]}""" }.joinToString(",")
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"estado":"DISPONIBLE","retoId":"$reto","necesarios":2,"aciertos":0,"preguntas":[$qs],"racha":{"actual":0,"maxima":0}}""") }
        val r = createClient { }.getAs("/api/v1/app/retos/hoy")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val served = obj(r.bodyAsText())["preguntas"]!!.jsonArray.map { it.jsonObject }
        assertEquals(ids, served.map { it["preguntaId"]!!.jsonPrimitive.content })
        assertEquals(listOf(1, 2, 3), served.map { it["posicion"]!!.jsonPrimitive.content.toInt() })
        assertTrue(served.all { it["tipoSlot"]!!.jsonPrimitive.content == "RAFAGA" })
    }

    @Test
    fun theRetryDecisionTravelsOnlyWhenTheClientMadeOneAndTheHeldAnswerIsServedVerbatim() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        val held = """{"esCorrecta":false,"estado":"EN_CURSO","aciertos":0,"respondidas":0,"oportunidad":{"disponible":true,"restantes":1}}"""
        upstream.handler = { _, _ -> SupabaseRawResponse(200, held) }
        val first = client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","usarOportunidad":null,"admiteOportunidad":true}""")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(setOf("p_usuario", "p_reto", "p_pregunta", "p_opcion", "p_admite_oportunidad", "p_admite_reintento_pago"), obj(upstream.rpcs.single().second).keys, "a client that supports it and made no decision: no p_usar_oportunidad (the offer may come)")
        assertEquals("true", obj(upstream.rpcs.single().second)["p_admite_oportunidad"]!!.jsonPrimitive.content)
        assertEquals("1", obj(first.bodyAsText())["oportunidad"]!!.jsonObject["restantes"]!!.jsonPrimitive.content)
        assertTrue("opcionCorrectaId" !in obj(first.bodyAsText()), "no correction while held")
        for (choice in listOf(true, false)) {
            upstream.rpcs.clear()
            client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","usarOportunidad":$choice,"admiteOportunidad":true}""")
            assertEquals(choice.toString(), obj(upstream.rpcs.single().second)["p_usar_oportunidad"]!!.jsonPrimitive.content)
        }
        // C3: a client that does not declare admiteOportunidad never gets a held answer: Ktor always sends false (its usarOportunidad is ignored)
        for (body in listOf(
            """{"opcionSeleccionadaId":"$opcion"}""",
            """{"opcionSeleccionadaId":"$opcion","admiteOportunidad":false}""",
            """{"opcionSeleccionadaId":"$opcion","admiteOportunidad":null,"usarOportunidad":true}""",
        )) {
            upstream.rpcs.clear()
            assertEquals(HttpStatusCode.OK, client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", body).status, body)
            val sent = obj(upstream.rpcs.single().second)
            assertEquals("false", sent["p_admite_oportunidad"]!!.jsonPrimitive.content, body)
            assertTrue("p_usar_oportunidad" !in sent, "its usarOportunidad is ignored: $body")
        }
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","admiteOportunidad":"si"}""").status)
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","usarOportunidad":"si"}""").status)
        assertTrue(upstream.rpcs.isEmpty())
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
        assertEquals(setOf("p_usuario", "p_reto", "p_pregunta", "p_opcion", "p_admite_oportunidad", "p_admite_reintento_pago"), obj(args).keys, "an old client (no admiteOportunidad): never held")
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

    /** FA43 (Home top bar): liga and otHoy are served exactly as the database builds them, explicit nulls included (no field dropped by explicitNulls = false). */
    @Test
    fun streakServesTheLeagueTierAndTodayFreeOtVerbatim() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        upstream.handler = { _, _ ->
            SupabaseRawResponse(200, """{"liga":{"orden":2,"codigo":"hl-2","nombre":"Liga Plata"},"otHoy":{"tope":100,"ganados":38},"actual":3,"maxima":5,"ultimoDiaSuperado":"2026-11-08"}""")
        }
        val r = client.getAs("/api/v1/app/racha")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val body = obj(r.bodyAsText())
        assertEquals("Liga Plata", body["liga"]!!.jsonObject["nombre"]!!.jsonPrimitive.content)
        assertEquals(2, body["liga"]!!.jsonObject["orden"]!!.jsonPrimitive.content.toInt())
        assertEquals(38, body["otHoy"]!!.jsonObject["ganados"]!!.jsonPrimitive.content.toInt())
        assertEquals(100, body["otHoy"]!!.jsonObject["tope"]!!.jsonPrimitive.content.toInt())
        // no league yet and no daily cap: both nulls reach the client as explicit JSON nulls
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"liga":null,"otHoy":{"tope":null,"ganados":0},"actual":0,"maxima":0,"ultimoDiaSuperado":null}""") }
        val empty = client.getAs("/api/v1/app/racha")
        assertEquals(HttpStatusCode.OK, empty.status, empty.bodyAsText())
        val e = obj(empty.bodyAsText())
        assertTrue(e.containsKey("liga") && e["liga"] is kotlinx.serialization.json.JsonNull, empty.bodyAsText())
        assertTrue(e["otHoy"]!!.jsonObject["tope"] is kotlinx.serialization.json.JsonNull, empty.bodyAsText())
        // an older database (without 20261108100100) still answers the FA29 shape: the route does not require the new fields
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"actual":1,"maxima":1,"ultimoDiaSuperado":null}""") }
        val old = client.getAs("/api/v1/app/racha")
        assertEquals(HttpStatusCode.OK, old.status, old.bodyAsText())
        assertFalse(obj(old.bodyAsText()).containsKey("otHoy"))
    }

    /** D3-26 (FA56, F31): month calendar of the streak panel. The database builds the body; Ktor validates mes, limits per user and never caches. */
    @Test
    fun streakCalendarPassesTheMonthAndServesTheDatabaseBody() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        val payload = """{"mes":"2026-11","hoy":"2026-11-15","rachaActual":2,"rachaMaxima":7,"rachaMes":3,"dias":[{"fecha":"2026-11-01","estado":"SUPERADO"},{"fecha":"2026-11-02","estado":"PROTEGIDO"}]}"""
        upstream.handler = { _, _ -> SupabaseRawResponse(200, payload) }
        val r = client.getAs("/api/v1/app/racha/calendario?mes=2026-11")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(listOf("private, no-store"), r.headers.getAll(HttpHeaders.CacheControl))
        assertEquals(json.parseToJsonElement(payload), json.parseToJsonElement(r.bodyAsText()))
        val docs = com.oposicontrol.backend.openapi.OpenApiDocs.locate()
        val op = docs.operations().first { it.contractId == "D3-26" }
        val ok = docs.responses(op).getValue("200")
        val schema = ((ok.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        com.oposicontrol.backend.openapi.SchemaChecker(docs).validate(com.oposicontrol.backend.openapi.OpenApiDocs.Node(schema, ok.file), json.parseToJsonElement(r.bodyAsText()), "$", problems)
        assertTrue(problems.isEmpty(), "D3-26 does not conform: $problems")
        val (fn, body) = upstream.rpcs.single()
        assertEquals("app_racha_calendario", fn)
        assertEquals(TEST_UID, obj(body)["p_usuario"]!!.jsonPrimitive.content, "identity = the JWT sub only")
        assertEquals("2026-11-01", obj(body)["p_mes"]!!.jsonPrimitive.content)
        assertEquals(listOf(ChallengeOperation.READ), limited.toList(), "per-user CATALOG_READ limit")
        // without mes: the database picks the current Madrid month (no p_mes sent)
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/racha/calendario").status)
        assertFalse(obj(upstream.rpcs.single().second).containsKey("p_mes"))
    }

    @Test
    fun streakCalendarRejectsABadMonthBeforeTheDatabaseAndMapsTheRange() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, "{}") }
        for (bad in listOf("2026-13", "2026-1", "26-11", "2026-11-01", "noviembre", "")) {
            val r = client.getAs("/api/v1/app/racha/calendario?mes=$bad")
            assertEquals(HttpStatusCode.BadRequest, r.status, "mes=$bad -> ${r.bodyAsText()}")
            assertTrue(r.bodyAsText().contains("VALIDATION") && r.bodyAsText().contains("mes"), r.bodyAsText())
        }
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/racha/calendario?mes=2026-11&mes=2026-10").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/racha/calendario?desde=2026-11").status, "unknown query parameter")
        assertTrue(upstream.rpcs.isEmpty(), "nothing reaches the database")
        // out of the allowed range (before the first reto month or in the future): the database says so -> 400 VALIDATION mes OUT_OF_RANGE
        upstream.handler = { _, _ -> rpcError("RACHA_MES_INVALID") }
        val range = client.getAs("/api/v1/app/racha/calendario?mes=2020-01")
        assertEquals(HttpStatusCode.BadRequest, range.status, range.bodyAsText())
        assertTrue(range.bodyAsText().contains("OUT_OF_RANGE"), range.bodyAsText())
        upstream.handler = { _, _ -> rpcError("WALLET_INVALID_USER") }
        assertEquals(HttpStatusCode.Forbidden, client.getAs("/api/v1/app/racha/calendario").status, "WALLET_INVALID_USER -> PROFILE_MISSING")
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/racha/calendario", token = null).status)
    }

    /** FA57 (F31b): the paid retry decision travels only for a client that declares admiteReintentoPago; INSUFFICIENT_BALANCE = 409 with its details. */
    @Test
    fun thePaidRetryDecisionTravelsOnlyWhenTheClientSupportsItAndInsufficientBalanceIs409() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        val held = """{"esCorrecta":false,"estado":"EN_CURSO","aciertos":1,"respondidas":1,"reintentoPago":{"disponible":true,"precioTickets":30,"saldoTickets":12}}"""
        upstream.handler = { _, _ -> SupabaseRawResponse(200, held) }
        val first = client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","admiteOportunidad":true,"admiteReintentoPago":true}""")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val sent = obj(upstream.rpcs.single().second)
        assertEquals("true", sent["p_admite_reintento_pago"]!!.jsonPrimitive.content)
        assertTrue("p_pagar_reintento" !in sent, "no decision yet: the offer may come")
        assertEquals("30", obj(first.bodyAsText())["reintentoPago"]!!.jsonObject["precioTickets"]!!.jsonPrimitive.content, "served verbatim")
        for (choice in listOf(true, false)) {
            upstream.rpcs.clear()
            client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","admiteReintentoPago":true,"pagarReintento":$choice}""")
            assertEquals(choice.toString(), obj(upstream.rpcs.single().second)["p_pagar_reintento"]!!.jsonPrimitive.content)
        }
        // an old client (no admiteReintentoPago): Ktor sends false and drops pagarReintento
        upstream.rpcs.clear()
        client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","pagarReintento":true}""")
        val old = obj(upstream.rpcs.single().second)
        assertEquals("false", old["p_admite_reintento_pago"]!!.jsonPrimitive.content)
        assertTrue("p_pagar_reintento" !in old)
        // not enough OT: 409 INSUFFICIENT_BALANCE with balance and price
        upstream.handler = { _, _ -> SupabaseRawResponse(400, """{"code":"P0001","message":"INSUFFICIENT_BALANCE","details":"{\"balance\":12,\"price\":30}","hint":null}""") }
        val poor = client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","admiteReintentoPago":true,"pagarReintento":true}""")
        assertEquals(HttpStatusCode.Conflict, poor.status, poor.bodyAsText())
        assertTrue(poor.bodyAsText().contains("INSUFFICIENT_BALANCE") && poor.bodyAsText().contains("30"), poor.bodyAsText())
        upstream.rpcs.clear()
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/app/retos/$reto/respuestas/$pregunta", """{"opcionSeleccionadaId":"$opcion","pagarReintento":"si"}""").status)
        assertTrue(upstream.rpcs.isEmpty())
    }

    /** D2-69 (FA58, F31c): content version tokens with ETag / If-None-Match -> 304. */
    @Test
    fun contentVersionServesTheTokensWithAnETagAnd304WhenUnchanged() = testApplication {
        application { challengeModule() }
        val client = createClient { }
        var payload = """{"examenes":"0123456789abcdef","servicios":"1111111111111111","noticias":"2222222222222222","paquetes":"3333333333333333"}"""
        upstream.handler = { _, _ -> SupabaseRawResponse(200, payload) }
        val r = client.getAs("/api/v1/app/contenido/version")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(json.parseToJsonElement(payload), json.parseToJsonElement(r.bodyAsText()))
        assertEquals(listOf("private, no-cache"), r.headers.getAll(HttpHeaders.CacheControl))
        val etag = r.headers[HttpHeaders.ETag]!!
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag)
        val (fn, body) = upstream.rpcs.single()
        assertEquals("app_contenido_version", fn)
        assertEquals(TEST_UID, obj(body)["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(ChallengeOperation.CONTENT_VERSION), limited.toList(), "its own cheap bucket, not CATALOG_READ")
        assertEquals(1, ipChecks.get(), "the IP limit runs first")
        // unchanged: 304 without body, same ETag
        val same = client.get("/api/v1/app/contenido/version") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, same.status)
        assertEquals("", same.bodyAsText())
        assertEquals(etag, same.headers[HttpHeaders.ETag])
        // one area changed: 200 with a new ETag
        payload = payload.replace("2222222222222222", "4444444444444444")
        val changed = client.get("/api/v1/app/contenido/version") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertFalse(etag == changed.headers[HttpHeaders.ETag])
        assertEquals("4444444444444444", obj(changed.bodyAsText())["noticias"]!!.jsonPrimitive.content)
        // strict query, auth, contract drift
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/contenido/version?x=1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/contenido/version", token = null).status)
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"examenes":"a"}""") }
        assertEquals(HttpStatusCode.BadGateway.value / 100, client.getAs("/api/v1/app/contenido/version").status.value / 100, "a body without the 4 tokens is an upstream contract error (5xx)")
    }
}
