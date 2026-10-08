package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 3: D2-03, D2-07, D2-08, D2-09, D2-10, D2-11, D2-12 y D2-65 de punta a punta (ruta -> servicio -> pasarela ->
 * transporte real) contra un upstream de pruebas que graba cada peticion. El cable se valida contra el OpenAPI CONGELADO
 * con [SchemaChecker]; la idempotencia usa el plugin real con el almacen en memoria.
 */
class AttemptRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val esp = "11111111-1111-4111-8111-111111111111"
    private val exam = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
    private val attempt = "bbbbbbbb-bbbb-4bbb-8bbb-000000000001"
    private val other = "bbbbbbbb-bbbb-4bbb-8bbb-0000000000ff"
    private val tag = "dddddddd-dddd-4ddd-8ddd-000000000001"
    private val key1 = "0a0a0a0a-0000-4000-8000-000000000001"
    private val key2 = "0a0a0a0a-0000-4000-8000-000000000002"

    private fun q(n: Int) = "cccccccc-cccc-4ccc-8ccc-00000000000$n"
    private fun opt(n: Int, letter: Int) = "eeeeeeee-eeee-4eee-800$n-00000000000$letter"

    private val json = Json { ignoreUnknownKeys = true }
    private var status: String? = "activa"
    private val limited = CopyOnWriteArrayList<AttemptOperation>()
    private var rateLimit: AttemptOperation? = null

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire(status), true) },
    )

    /** Upstream de pruebas: respuestas por fragmento de ruta (ya decodificada) y registro de TODO lo que sale. */
    private inner class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val urls: List<String> get() = sent.map { URLDecoder.decode(it.uri().toString(), Charsets.UTF_8) }
        fun rpcBodies(function: String): List<JsonObject> =
            sent.filter { it.uri().toString().endsWith("/rest/v1/rpc/$function") }.map { json.parseToJsonElement(readRequestBody(it)).jsonObject }

        var examById = "[]"
        var grantFacts = "[]"
        var capabilities = "[]"
        var premium = "[]"
        var startOutcome = """{"outcome":"CREATED","intento_id":"$attempt"}"""
        var answerOutcome = """{"outcome":"OK","estado":"ANSWERED","revision":1,"es_primera_respuesta":true,"respondidas":1,"noop":false}"""
        var finishOutcome = """{"outcome":"OK","already":false}"""
        var abandonOutcome = """{"outcome":"OK","already":false}"""
        var attemptRow: String? = null
        var frozen = "[]"
        var questionPage: (String) -> String = { "[]" }
        var reviewPage: (String) -> String = { "[]" }
        var resultQuestions = "[]"
        var answers = "[]"
        var annulled = "[]"
        var tags = "[]"

        fun respond(rawUri: String): V1StubResponse {
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            val body = when {
                uri.endsWith("/rest/v1/rpc/app_start_exam_attempt") -> startOutcome
                uri.endsWith("/rest/v1/rpc/app_record_attempt_answer") -> answerOutcome
                uri.endsWith("/rest/v1/rpc/app_finish_exam_attempt") -> finishOutcome
                uri.endsWith("/rest/v1/rpc/app_abandon_exam_attempt") -> abandonOutcome
                uri.contains("/rest/v1/estado_premium_usuario") -> premium
                uri.contains("/rest/v1/capacidades_especialidad") -> capabilities
                uri.contains("/rest/v1/contenido_desbloqueado_usuario?select=examen_id,motivo") -> grantFacts
                uri.contains("/rest/v1/examenes?select=id,codigo_examen") && uri.contains("&id=eq.$exam") -> examById
                uri.contains("/rest/v1/examenes?select=id,codigo_examen") -> "[]"
                // El intento solo existe para SU dueno y SU id: cualquier otro filtro devuelve vacio, como PostgREST.
                uri.contains("/rest/v1/intentos_examen?select=id,examen_id,estado,motivo_fin") ->
                    attemptRow?.takeIf { uri.contains("&id=eq.$attempt&usuario_id=eq.$TEST_UID&origen_intento=eq.examen") }?.let { "[$it]" } ?: "[]"
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final&") -> frozen
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(soluciones_pregunta") -> resultQuestions
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(") && uri.contains("soluciones_pregunta") -> reviewPage(uri)
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(") -> questionPage(uri)
                uri.contains("/rest/v1/respuestas_intento") -> answers
                uri.contains("/rest/v1/preguntas_examen?select=pregunta_id&examen_id=eq.$exam&anulada=eq.true") -> annulled
                uri.contains("/rest/v1/resumen_intento_etiqueta") -> tags
                else -> error("peticion inesperada al upstream: $uri")
            }
            return V1StubResponse(200, body)
        }
    }

    private val upstream = Upstream()

    private fun Application.attemptModule(runtimePresent: Boolean = true) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        val clock = { now }
        val catalog = CatalogService(CatalogGateway(TransportCatalogUpstream(transport), json), clock)
        val runtime = AttemptRuntime(
            resolver = resolver,
            attempts = AttemptService(AttemptGateway(TransportAttemptUpstream(transport), json), catalog, clock),
            idempotency = InMemoryIdempotencyStore(),
            limit = { operation, _ ->
                limited += operation
                if (operation == rateLimit) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 7)
            },
        )
        routing { attemptRoutes(runtime.takeIf { runtimePresent }) }
    }

    // ---------- filas del upstream ----------

    private fun examRow(acceso: String = "gratuito", demo: Boolean = false) =
        """[{"id":"$exam","codigo_examen":"EX-1","titulo":"Examen","descripcion":null,"tipo_examen":"rafaga","tipo_acceso":"$acceso",""" +
            """"especialidad_id":"$esp","total_preguntas":4,"duracion_minutos":60,"anio":2024,"portada_storage_path":null,""" +
            """"publicado_en":"2026-09-30T10:00:00+00:00","demo_disponible":$demo,"comunidades_autonomas":null}]"""

    private fun attemptRow(
        estado: String = "en_progreso",
        respondidas: Int = 2,
        correctas: Int = 0,
        falladas: Int = 0,
        sinResponder: Int = 0,
        motivoFin: String? = null,
        tipo: String = "rafaga",
    ): String {
        val finished = estado == "finalizado"
        return """{"id":"$attempt","examen_id":"$exam","estado":"$estado","motivo_fin":${motivoFin?.let { "\"$it\"" } ?: "null"},""" +
            """"iniciado_en":"2026-10-08T11:00:00.123456+00:00","finalizado_en":${if (finished) "\"2026-10-08T11:20:00+00:00\"" else "null"},""" +
            """"duracion_segundos":${if (finished) "1200" else "null"},"total_preguntas":4,"total_respondidas":$respondidas,""" +
            """"total_correctas":$correctas,"total_falladas":$falladas,"total_sin_responder":$sinResponder,"tipo_examen_snapshot":"$tipo",""" +
            """"especialidad_snapshot_id":"$esp","ultima_pregunta_posicion":2}"""
    }

    private fun finishedRow() = attemptRow("finalizado", respondidas = 3, correctas = 2, falladas = 1, sinResponder = 0, motivoFin = "USER")

    private fun frozenRow(n: Int, status: String? = null) =
        """{"posicion":$n,"pregunta_id":"${q(n)}","estado_final":${status?.let { "\"$it\"" } ?: "null"}}"""

    private fun options(n: Int) =
        """[{"id":"${opt(n, 2)}","letra":"B","texto_opcion":"dos","posicion":2},{"id":"${opt(n, 1)}","letra":"A","texto_opcion":"uno","posicion":1}]"""

    private fun questionRow(n: Int, status: String? = null, review: Boolean = false): String {
        val extra = if (review) {
            ""","soluciones_pregunta":{"opcion_correcta_id":"${opt(n, 1)}"},"etiquetas_pregunta":[{"etiquetas":{"id":"$tag","nombre":"Tema 1"}}]"""
        } else {
            ""
        }
        return """{"posicion":$n,"pregunta_id":"${q(n)}","estado_final":${status?.let { "\"$it\"" } ?: "null"},""" +
            """"preguntas":{"codigo_pregunta":"P-$n","tipo_pregunta":"rafaga","titulo":"Enunciado $n","opciones_pregunta":${options(n)}$extra}}"""
    }

    /** Respuesta abierta (columnas sin correccion) o corregida (intento finalizado). */
    private fun answerRow(n: Int, letter: Int?, graded: Boolean = false, first: Int? = letter, revision: Int = 1): String {
        val open = """"pregunta_id":"${q(n)}","opcion_seleccionada_id":${letter?.let { "\"${opt(n, it)}\"" } ?: "null"},"revision":$revision,""" +
            """"respondida_en":"2026-10-08T11:05:00+00:00","creada_en":"2026-10-08T11:05:00+00:00""""
        if (!graded) return "{$open}"
        return "{$open," +
            """"es_correcta":${letter?.let { it == 1 } ?: "null"},"actualizada_en":"2026-10-08T11:06:00+00:00","tiempo_respuesta_ms":4200,""" +
            """"tiempo_respuesta_segundos":4,"primera_opcion_id":${first?.let { "\"${opt(n, it)}\"" } ?: "null"},""" +
            """"primera_es_correcta":${first?.let { it == 1 } ?: "null"},"cambio_respuesta":${first != letter},"es_primer_intento_pregunta":true}"""
    }

    // ---------- cliente ----------

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        key: String? = null,
        token: String? = TEST_TOKEN,
    ): HttpResponse = request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        key?.let { header(IdempotencyHeaders.KEY, it) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    private fun assertConforms(contractId: String, status: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue(status)
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId $status does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private fun assertNoSolutionWasRead() {
        val leaked = upstream.urls.filter { url -> listOf("soluciones_pregunta", "razonamiento", "es_correcta", "opcion_correcta", "recursos_pregunta").any { it in url } }
        assertTrue(leaked.isEmpty(), "SEC-004 / DEC-015: lectura de soluciones o correccion con el intento sin finalizar: $leaked")
    }

    private val start = "/api/v1/app/examenes/aaaaaaaa-aaaa-4aaa-8aaa-000000000001/intentos"
    private val base = "/api/v1/app/intentos/bbbbbbbb-bbbb-4bbb-8bbb-000000000001"

    /** (metodo, ruta, cuerpo, clave) de las ocho operaciones, validas para el intento [id]. */
    private fun everyAttemptRoute(id: String = attempt): List<List<String?>> {
        val root = "/api/v1/app/intentos/$id"
        return listOf(
            listOf("GET", root, null, null),
            listOf("GET", "$root/preguntas", null, null),
            listOf("PUT", "$root/respuestas/${q(1)}", """{"opcionSeleccionadaId":"${opt(1, 1)}","tiempoRespuestaMs":10,"baseRevision":0}""", key1),
            listOf("POST", "$root/finalizar", null, null),
            listOf("POST", "$root/abandonar", """{"posicionPregunta":1,"motivo":"salida_usuario"}""", null),
            listOf("GET", "$root/resultado", null, null),
            listOf("GET", "$root/revision", null, null),
        )
    }

    private suspend fun HttpClient.callRoute(route: List<String?>, token: String? = TEST_TOKEN): HttpResponse =
        call(HttpMethod.parse(route[0]!!), route[1]!!, route[2], route[3], token)

    // =================================================================== D2-03

    @Test
    fun startCreatesTheAttemptThroughThePolicyAndTheRpcAndConformsToTheFrozenWire() = testApplication {
        application { attemptModule() }
        upstream.examById = examRow()
        upstream.attemptRow = attemptRow(respondidas = 0)
        val response = client.call(HttpMethod.Post, start, key = key1)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Created, response.status, body)
        assertEquals("/api/v1/app/intentos/$attempt", response.headers[HttpHeaders.Location])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertNull(response.headers[IdempotencyHeaders.REPLAYED])
        assertConforms("D2-03", "201", body)
        val intento = obj(body)["intento"]!!.jsonObject
        assertEquals("false", obj(body)["reanudado"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(attempt, "EXAM", exam, esp, "rafaga", "en_progreso", "2026-10-08T11:00:00.123456Z", now.toString(), "4", "0", "4"),
            listOf("id", "origen", "examenId", "especialidadId", "tipoExamen", "estado", "iniciadoEn", "serverNow", "totalPreguntas", "respondidas", "sinResponder")
                .map { intento[it]!!.jsonPrimitive.content },
        )
        // Decisiones abiertas: sin limite de tiempo, sin motivo de fin y sin correccion con el intento abierto.
        listOf("deadline", "motivoFin", "correctas", "falladas").forEach { assertNull(intento[it], it) }
        // El actor del RPC es el `sub` del JWT, y el RPC solo se llama despues de la politica de acceso.
        assertEquals(listOf(mapOf("p_usuario_id" to TEST_UID, "p_examen_id" to exam)), upstream.rpcBodies("app_start_exam_attempt").map { b -> b.mapValues { it.value.jsonPrimitive.content } })
        assertTrue(upstream.urls.indexOfFirst { "estado_premium_usuario" in it } < upstream.urls.indexOfFirst { "rpc/app_start_exam_attempt" in it }, "D7-25 antes del comando")
        assertEquals(listOf(AttemptOperation.START), limited)

        // Reintento con la MISMA clave: respuesta guardada, cabeceras incluidas, y el comando NO se ejecuta otra vez.
        val replay = client.call(HttpMethod.Post, start, key = key1)
        assertEquals(HttpStatusCode.Created, replay.status)
        assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
        assertEquals("/api/v1/app/intentos/$attempt", replay.headers[HttpHeaders.Location])
        assertEquals("no-store", replay.headers[HttpHeaders.CacheControl])
        assertEquals(obj(body), obj(replay.bodyAsText()))
        assertEquals(1, upstream.rpcBodies("app_start_exam_attempt").size)

        // Misma clave, otra peticion: 422. Otra clave: converge en el intento abierto (200, reanudado).
        val reused = client.call(HttpMethod.Post, start, body = """{"modo":"DEMO"}""", key = key1)
        assertEquals(HttpStatusCode.UnprocessableEntity, reused.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(reused.bodyAsText()))
        upstream.startOutcome = """{"outcome":"RESUMED","intento_id":"$attempt"}"""
        val resumed = client.call(HttpMethod.Post, start, body = """{"modo":"NORMAL"}""", key = key2)
        val resumedBody = resumed.bodyAsText()
        assertEquals(HttpStatusCode.OK, resumed.status, resumedBody)
        assertNull(resumed.headers[HttpHeaders.Location])
        assertEquals("no-store", resumed.headers[HttpHeaders.CacheControl])
        assertConforms("D2-03", "200", resumedBody)
        assertEquals("true", obj(resumedBody)["reanudado"]!!.jsonPrimitive.content)
    }

    @Test
    fun startIsDeniedByTheEntitlementPolicyBeforeAnyCommand() = testApplication {
        application { attemptModule() }
        // premium sin derecho -> EXAM_LOCKED con accessState; solo demo -> DEMO (tampoco con modo DEMO: semantica abierta).
        upstream.examById = examRow(acceso = "premium")
        val locked = client.call(HttpMethod.Post, start, key = key1)
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertEquals("EXAM_LOCKED", code(locked.bodyAsText()))
        assertEquals("LOCKED", obj(locked.bodyAsText())["details"]!!.jsonObject["accessState"]!!.jsonPrimitive.content)
        upstream.examById = examRow(acceso = "premium", demo = true)
        val demo = client.call(HttpMethod.Post, start, body = """{"modo":"DEMO"}""", key = key2)
        assertEquals("EXAM_LOCKED", code(demo.bodyAsText()))
        assertEquals("DEMO", obj(demo.bodyAsText())["details"]!!.jsonObject["accessState"]!!.jsonPrimitive.content)
        // la especialidad del examen no admite ese tipo -> SPECIALTY_TYPE_NOT_ALLOWED aunque sea gratuito
        upstream.examById = examRow()
        upstream.capabilities = """[{"especialidad_id":"$esp","permite_rafagas":false}]"""
        val capability = client.call(HttpMethod.Post, start, key = "0a0a0a0a-0000-4000-8000-000000000003")
        assertEquals(HttpStatusCode.Forbidden, capability.status)
        assertEquals("SPECIALTY_TYPE_NOT_ALLOWED", code(capability.bodyAsText()))
        // borrador / archivado / inexistente -> el mismo 404
        upstream.examById = "[]"
        val missing = client.call(HttpMethod.Post, start, key = "0a0a0a0a-0000-4000-8000-000000000004")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("EXAM_NOT_FOUND", code(missing.bodyAsText()))
        assertTrue(upstream.rpcBodies("app_start_exam_attempt").isEmpty(), "ningun intento se abre sin pasar la politica")

        // con acceso (premium vigente) el comando corre; sus rechazos salen con su codigo y NO se guardan como repeticion
        upstream.capabilities = "[]"
        upstream.examById = examRow(acceso = "premium")
        upstream.premium = """[{"es_premium":true,"estado_suscripcion":"activa","fin_en":"2099-01-01T00:00:00+00:00"}]"""
        upstream.startOutcome = """{"outcome":"EXAM_EMPTY"}"""
        val key = "0a0a0a0a-0000-4000-8000-000000000005"
        val empty = client.call(HttpMethod.Post, start, key = key)
        assertEquals(HttpStatusCode.Conflict, empty.status)
        assertEquals("EXAM_EMPTY", code(empty.bodyAsText()))
        upstream.startOutcome = """{"outcome":"EXAM_NOT_FOUND"}"""
        assertEquals("EXAM_NOT_FOUND", code(client.call(HttpMethod.Post, start, key = key).bodyAsText()), "un error libera la clave: el reintento re-ejecuta")
        assertEquals(2, upstream.rpcBodies("app_start_exam_attempt").size)
    }

    @Test
    fun startValidatesIdentityThenPathThenKeyThenBody() = testApplication {
        application { attemptModule() }
        upstream.examById = examRow()
        upstream.attemptRow = attemptRow()
        // sin token: 401 aunque falte la clave (nunca un 400 de idempotencia a un anonimo)
        val anonymous = client.call(HttpMethod.Post, start, token = null)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("UNAUTHENTICATED", code(anonymous.bodyAsText()))
        val badPath = client.call(HttpMethod.Post, "/api/v1/app/examenes/1-1-1-1-1/intentos")
        assertEquals(HttpStatusCode.BadRequest, badPath.status)
        assertEquals("VALIDATION", code(badPath.bodyAsText()))
        assertEquals("examId", obj(badPath.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray[0].jsonObject["field"]!!.jsonPrimitive.content)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(client.call(HttpMethod.Post, start).bodyAsText()))
        assertEquals("IDEMPOTENCY_KEY_INVALID", code(client.call(HttpMethod.Post, start, key = "not-a-uuid").bodyAsText()))
        // el cuerpo no puede llevar nada que decida el servidor, ni un modo fuera del contrato
        val forged = client.call(HttpMethod.Post, start, body = """{"usuarioId":"$TEST_UID"}""", key = key1)
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertEquals("UNKNOWN_FIELD", obj(forged.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray[0].jsonObject["issue"]!!.jsonPrimitive.content)
        assertEquals("VALIDATION", code(client.call(HttpMethod.Post, start, body = """{"modo":"PRACTICA"}""", key = key2).bodyAsText()))
        assertEquals("MALFORMED_BODY", code(client.call(HttpMethod.Post, start, body = "{", key = key2).bodyAsText()))
        assertTrue(upstream.sent.isEmpty(), "nada invalido llega al upstream: ${upstream.urls}")
    }

    // =================================================================== D2-07

    @Test
    fun getReturnsTheOpenAttemptWithItsGridAndNeverRevealsCorrectness() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = attemptRow(respondidas = 1)
        upstream.frozen = "[${(1..4).joinToString(",") { frozenRow(it) }}]"
        upstream.answers = "[${answerRow(1, 2)},${answerRow(2, null)}]" // 1 respondida, 2 saltada
        upstream.annulled = """[{"pregunta_id":"${q(3)}"}]"""
        val response = client.call(HttpMethod.Get, base)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-07", "200", body)
        val grid = obj(body)["grid"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("respondida", "sin_responder", "anulada", "sin_responder"), grid.map { it["estado"]!!.jsonPrimitive.content })
        assertEquals(listOf(1, 1, 0, 0), grid.map { it["revision"]!!.jsonPrimitive.int })
        val intento = obj(body)["intento"]!!.jsonObject
        assertEquals(2, intento["sinResponder"]!!.jsonPrimitive.int, "saltada + sin tocar (la anulada no cuenta)")
        listOf("correctas", "falladas", "motivoFin", "deadline").forEach { assertNull(intento[it], it) }
        assertNoSolutionWasRead()
        assertTrue(upstream.urls.all { "usuario_id=eq.$TEST_UID" in it || "intentos_examen" !in it }, "el intento se lee SIEMPRE filtrado por su dueno")
        assertTrue(limited.isEmpty(), "D2-07 no declara 429")
    }

    @Test
    fun getOfAFinishedAttemptShowsTheFrozenGridAndTotals() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        upstream.frozen = "[${frozenRow(1, "CORRECT")},${frozenRow(2, "WRONG")},${frozenRow(3, "ANNULLED")},${frozenRow(4, "CORRECT")}]"
        upstream.answers = "[${answerRow(1, 1, graded = true)},${answerRow(2, 2, graded = true)},${answerRow(4, 1, graded = true)}]"
        val body = client.call(HttpMethod.Get, base).bodyAsText()
        assertConforms("D2-07", "200", body)
        assertEquals(listOf("correcta", "incorrecta", "anulada", "correcta"), obj(body)["grid"]!!.jsonArray.map { it.jsonObject["estado"]!!.jsonPrimitive.content })
        val intento = obj(body)["intento"]!!.jsonObject
        assertEquals(listOf("finalizado", "USER", "2", "1", "0"), listOf("estado", "motivoFin", "correctas", "falladas", "sinResponder").map { intento[it]!!.jsonPrimitive.content })
        assertFalse(upstream.urls.any { "preguntas_examen" in it }, "finalizado: manda el estado congelado, no la anulacion vigente")
    }

    // =================================================================== D2-08

    @Test
    fun questionsArePagedByPositionWithoutSolutionsAndConformToTheFrozenWire() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = attemptRow()
        upstream.questionPage = { uri ->
            val after = Regex("posicion=gt\\.(\\d+)").find(uri)!!.groupValues[1].toInt()
            val limit = Regex("limit=(\\d+)").find(uri)!!.groupValues[1].toInt()
            "[" + (1..4).filter { it > after }.take(limit).joinToString(",") { questionRow(it) } + "]"
        }
        upstream.answers = "[${answerRow(1, 2)},${answerRow(2, null)}]"
        upstream.annulled = """[{"pregunta_id":"${q(2)}"}]"""
        val first = client.call(HttpMethod.Get, "$base/preguntas?limit=2")
        val body = first.bodyAsText()
        assertEquals(HttpStatusCode.OK, first.status, body)
        assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
        assertConforms("D2-08", "200", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2), items.map { it["posicion"]!!.jsonPrimitive.int })
        assertEquals(listOf("A", "B"), items[0]["opciones"]!!.jsonArray.map { it.jsonObject["letra"]!!.jsonPrimitive.content }, "opciones por posicion")
        assertEquals(setOf("id", "letra", "texto"), items[0]["opciones"]!!.jsonArray[0].jsonObject.keys, "una opcion no lleva marca de correccion")
        assertEquals(opt(1, 2), items[0]["respuesta"]!!.jsonObject["opcionId"]!!.jsonPrimitive.content)
        assertEquals(setOf("revision", "respondidaEn"), items[1]["respuesta"]!!.jsonObject.keys, "saltada: respuesta sin opcion")
        assertEquals(listOf("false", "true"), items.map { it["anulada"]!!.jsonPrimitive.content })
        assertFalse(body.contains("orrect"), "ni `esCorrecta` ni `opcionCorrecta` en D2-08: $body")
        assertNoSolutionWasRead()
        assertTrue(upstream.urls.any { "pregunta_id=in.(${q(1)},${q(2)})" in it }, "solo las respuestas de la pagina")

        val cursor = obj(body)["nextCursor"]!!.jsonPrimitive.content
        val second = client.call(HttpMethod.Get, "$base/preguntas?limit=2&cursor=$cursor").bodyAsText()
        assertConforms("D2-08", "200", second)
        assertEquals(listOf(3, 4), obj(second)["items"]!!.jsonArray.map { it.jsonObject["posicion"]!!.jsonPrimitive.int })
        assertNull(obj(second)["nextCursor"], "ultima pagina")

        // limite: por defecto 10, tope 20 (se recorta), invalido 400; cursor de otra lista o corrupto 400 INVALID_CURSOR
        client.call(HttpMethod.Get, "$base/preguntas")
        assertTrue(upstream.urls.last { "preguntas_intento" in it }.endsWith("limit=11"))
        client.call(HttpMethod.Get, "$base/preguntas?limit=999")
        assertTrue(upstream.urls.last { "preguntas_intento" in it }.endsWith("limit=21"))
        val before = upstream.sent.size
        assertEquals("VALIDATION", code(client.call(HttpMethod.Get, "$base/preguntas?limit=0").bodyAsText()))
        assertEquals("INVALID_CURSOR", code(client.call(HttpMethod.Get, "$base/preguntas?cursor=AAAA").bodyAsText()))
        assertEquals(before, upstream.sent.size, "un parametro invalido no llega al upstream")
    }

    @Test
    fun questionsOfAFinishedAttemptStillCarryNoCorrection() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        upstream.questionPage = { "[${questionRow(1, "CORRECT")},${questionRow(2, "ANNULLED")}]" }
        upstream.answers = "[${answerRow(1, 1)}]"
        val body = client.call(HttpMethod.Get, "$base/preguntas").bodyAsText()
        assertConforms("D2-08", "200", body)
        assertEquals(listOf("false", "true"), obj(body)["items"]!!.jsonArray.map { it.jsonObject["anulada"]!!.jsonPrimitive.content })
        assertNoSolutionWasRead()
    }

    // =================================================================== D2-09

    @Test
    fun answerIsRecordedThroughTheRpcWithoutRevealingAndReplaysByKey() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        val request = """{"opcionSeleccionadaId":"${opt(1, 2)}","tiempoRespuestaMs":4200,"nivelConfianza":2,"baseRevision":0}"""
        val response = client.call(HttpMethod.Put, path, request, key1)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-09", "200", body)
        assertEquals(mapOf("revision" to "1", "estado" to "ANSWERED", "esPrimeraRespuesta" to "true", "respondidas" to "1"), obj(body).mapValues { it.value.jsonPrimitive.content })
        assertFalse(body.contains("esCorrecta") || body.contains("opcionCorrectaId"), "DEC-015 abierta: nada se revela al responder")
        val rpc = upstream.rpcBodies("app_record_attempt_answer").single()
        assertEquals(
            mapOf(
                "p_usuario_id" to TEST_UID, "p_intento_id" to attempt, "p_pregunta_id" to q(1), "p_opcion_id" to opt(1, 2),
                "p_tiempo_respuesta_ms" to "4200", "p_base_revision" to "0", "p_nivel_confianza" to "2",
            ),
            rpc.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(1, upstream.sent.size, "UNA llamada: la validacion de dueno, estado y pertenencia vive en la RPC")
        assertEquals(listOf(AttemptOperation.ANSWER), limited)

        val replay = client.call(HttpMethod.Put, path, request, key1)
        assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(obj(body), obj(replay.bodyAsText()))
        assertEquals(1, upstream.sent.size, "la repeticion no vuelve a ejecutar el comando")
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(client.call(HttpMethod.Put, path, request.replace("4200", "1"), key1).bodyAsText()))

        // saltar: opcion ausente o `null` explicito (el contrato la declara nullable)
        upstream.answerOutcome = """{"outcome":"OK","estado":"SKIPPED","revision":1,"es_primera_respuesta":false,"respondidas":1,"noop":false}"""
        val skip = client.call(HttpMethod.Put, "$base/respuestas/${q(2)}", """{"opcionSeleccionadaId":null,"tiempoRespuestaMs":0,"baseRevision":0}""", key2)
        assertEquals(HttpStatusCode.OK, skip.status, skip.bodyAsText())
        assertConforms("D2-09", "200", skip.bodyAsText())
        assertEquals("SKIPPED", obj(skip.bodyAsText())["estado"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, upstream.rpcBodies("app_record_attempt_answer").last()["p_opcion_id"])
        upstream.answerOutcome = """{"outcome":"OK","estado":"ANNULLED","revision":0,"es_primera_respuesta":false,"respondidas":1,"noop":true}"""
        val annulled = client.call(HttpMethod.Put, "$base/respuestas/${q(3)}", """{"tiempoRespuestaMs":0,"baseRevision":0}""", "0a0a0a0a-0000-4000-8000-000000000003")
        assertConforms("D2-09", "200", annulled.bodyAsText())
        assertEquals("ANNULLED", obj(annulled.bodyAsText())["estado"]!!.jsonPrimitive.content)
    }

    @Test
    fun answerMapsEveryCommandOutcomeToItsFrozenErrorAndNeverStoresAnErrorAsAReplay() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        val request = """{"opcionSeleccionadaId":"${opt(1, 2)}","tiempoRespuestaMs":10,"baseRevision":0}"""
        val expected = listOf(
            """{"outcome":"ATTEMPT_NOT_FOUND"}""" to (HttpStatusCode.NotFound to "ATTEMPT_NOT_FOUND"),
            """{"outcome":"ATTEMPT_NOT_IN_PROGRESS","estado":"finalizado"}""" to (HttpStatusCode.Conflict to "ATTEMPT_NOT_IN_PROGRESS"),
            """{"outcome":"ANSWER_LOCKED","current_revision":1}""" to (HttpStatusCode.Conflict to "ANSWER_LOCKED"),
            """{"outcome":"REVISION_CONFLICT","current_revision":3}""" to (HttpStatusCode.Conflict to "REVISION_CONFLICT"),
            """{"outcome":"QUESTION_NOT_IN_ATTEMPT"}""" to (HttpStatusCode.UnprocessableEntity to "QUESTION_NOT_IN_ATTEMPT"),
            """{"outcome":"OPTION_NOT_IN_QUESTION"}""" to (HttpStatusCode.UnprocessableEntity to "OPTION_NOT_IN_QUESTION"),
            """{"outcome":"SOMETHING_NEW"}""" to (HttpStatusCode.BadGateway to "UPSTREAM_CONTRACT_ERROR"),
        )
        expected.forEachIndexed { index, (outcome, error) ->
            upstream.answerOutcome = outcome
            // la MISMA clave en todos: un error nunca se guarda, asi que cada intento re-ejecuta el comando
            val response = client.call(HttpMethod.Put, path, request, key1)
            val body = response.bodyAsText()
            assertEquals(error.first, response.status, "$outcome: $body")
            assertEquals(error.second, code(body), outcome)
            assertNull(response.headers[IdempotencyHeaders.REPLAYED])
            assertEquals(index + 1, upstream.rpcBodies("app_record_attempt_answer").size)
            if (error.second == "REVISION_CONFLICT") assertEquals(3, obj(body)["details"]!!.jsonObject["currentRevision"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun answerRejectsInvalidInputBeforeTheCommand() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        val valid = """{"opcionSeleccionadaId":"${opt(1, 2)}","tiempoRespuestaMs":10,"baseRevision":0}"""
        suspend fun issue(body: String, at: String = path, key: String? = key1): Pair<String, String?> {
            val text = client.call(HttpMethod.Put, at, body, key).bodyAsText()
            val first = obj(text)["details"]?.jsonObject?.get("issues")?.jsonArray?.firstOrNull()?.jsonObject
            return code(text) to first?.let { "${it["field"]!!.jsonPrimitive.content}:${it["issue"]!!.jsonPrimitive.content}" }
        }
        assertEquals("IDEMPOTENCY_KEY_REQUIRED" to null, issue(valid, key = null))
        assertEquals("VALIDATION" to "preguntaId:INVALID_FORMAT", issue(valid, at = "$base/respuestas/nope"))
        assertEquals("VALIDATION" to "attemptId:INVALID_FORMAT", issue(valid, at = "/api/v1/app/intentos/nope/respuestas/${q(1)}"))
        assertEquals("VALIDATION" to "baseRevision:REQUIRED", issue("""{"tiempoRespuestaMs":10}"""))
        assertEquals("VALIDATION" to "tiempoRespuestaMs:OUT_OF_RANGE", issue("""{"tiempoRespuestaMs":-1,"baseRevision":0}"""))
        assertEquals("VALIDATION" to "baseRevision:OUT_OF_RANGE", issue("""{"tiempoRespuestaMs":1,"baseRevision":-1}"""))
        assertEquals("VALIDATION" to "opcionSeleccionadaId:INVALID_FORMAT", issue("""{"opcionSeleccionadaId":"x","tiempoRespuestaMs":1,"baseRevision":0}"""))
        // nada de lo que decide el servidor puede venir en el cuerpo
        listOf("esCorrecta", "revision", "usuarioId", "respondidaEn", "esPrimeraRespuesta").forEach { field ->
            assertEquals("VALIDATION" to "$field:UNKNOWN_FIELD", issue("""{"tiempoRespuestaMs":1,"baseRevision":0,"$field":1}"""), field)
        }
        assertEquals("VALIDATION" to "baseRevision:INVALID_FORMAT", issue("""{"tiempoRespuestaMs":1,"baseRevision":null}"""), "solo la opcion admite null")
        assertTrue(upstream.sent.isEmpty(), "nada invalido llega al upstream: ${upstream.urls}")
    }

    // =================================================================== D2-10 / D2-12

    private fun finishedFixture() {
        upstream.attemptRow = finishedRow()
        upstream.resultQuestions = "[" + listOf(
            """{"posicion":1,"pregunta_id":"${q(1)}","estado_final":"CORRECT","preguntas":{"soluciones_pregunta":{"opcion_correcta_id":"${opt(1, 1)}"}}}""",
            """{"posicion":2,"pregunta_id":"${q(2)}","estado_final":"WRONG","preguntas":{"soluciones_pregunta":[{"opcion_correcta_id":"${opt(2, 1)}"}]}}""",
            """{"posicion":3,"pregunta_id":"${q(3)}","estado_final":"ANNULLED","preguntas":{"soluciones_pregunta":{"opcion_correcta_id":"${opt(3, 1)}"}}}""",
            """{"posicion":4,"pregunta_id":"${q(4)}","estado_final":"CORRECT","preguntas":{"soluciones_pregunta":{"opcion_correcta_id":"${opt(4, 1)}"}}}""",
        ).joinToString(",") + "]"
        upstream.answers = "[${answerRow(1, 1, graded = true)},${answerRow(2, 2, graded = true)},${answerRow(3, 1, graded = true)},${answerRow(4, 1, graded = true)}]"
        upstream.tags = """[{"etiqueta_id":"$tag","correctas":2,"falladas":1,"sin_responder":0,"tiempo_total_segundos":12,"etiquetas":{"nombre":"Tema 1"}}]"""
    }

    private fun assertResult(body: String) {
        val resumen = obj(body)["resumen"]!!.jsonObject
        // DEC-013 (default): 2 correctas / 3 evaluables * 10 = 6.67 (half-up); la anulada no es evaluable.
        assertEquals(
            listOf(exam, esp, "rafaga", "6.67", "bien", "1200", "2", "1", "0"),
            listOf("examenId", "especialidadId", "modo", "nota", "calificacion", "duracionSegundos", "correctas", "falladas", "sinResponder").map { resumen[it]!!.jsonPrimitive.content },
        )
        val preguntas = obj(body)["preguntas"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(opt(1, 1), opt(2, 2), null, opt(4, 1)), preguntas.map { it["opcionElegida"]?.jsonPrimitive?.content })
        assertEquals(listOf(opt(1, 1), opt(2, 1), null, opt(4, 1)), preguntas.map { it["opcionCorrecta"]?.jsonPrimitive?.content }, "la anulada no ensena solucion")
        assertEquals(listOf(4, 4, 4, 4), preguntas.map { it["tiempoSeg"]!!.jsonPrimitive.int })
        assertEquals(listOf("Tema 1"), obj(body)["porEtiqueta"]!!.jsonArray.map { it.jsonObject["nombre"]!!.jsonPrimitive.content })
        // decisiones abiertas: bloques de solo-valor a null
        listOf("comparativa", "probabilidadPlaza").forEach { assertNull(obj(body)[it], it) }
        preguntas.forEach { assertNull(it["razonamiento"]); assertNull(it["pctAciertoGlobal"]) }
    }

    @Test
    fun finishClosesTheAttemptOnceAndReturnsTheFrozenResult() = testApplication {
        application { attemptModule() }
        finishedFixture()
        val response = client.call(HttpMethod.Post, "$base/finalizar")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-10", "200", body)
        assertResult(body)
        assertEquals(mapOf("p_usuario_id" to TEST_UID, "p_intento_id" to attempt), upstream.rpcBodies("app_finish_exam_attempt").single().mapValues { it.value.jsonPrimitive.content })
        assertEquals(listOf(AttemptOperation.CLOSE), limited)

        // doble finalizar SIN clave: idempotente por estado (la RPC responde `already`), mismo resultado, 200
        upstream.finishOutcome = """{"outcome":"OK","already":true}"""
        val again = client.call(HttpMethod.Post, "$base/finalizar")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(obj(body), obj(again.bodyAsText()))
        // con clave (opcional): la repeticion ni siquiera llega a la RPC; una clave mal formada es 400
        val keyed = client.call(HttpMethod.Post, "$base/finalizar", key = key1)
        val calls = upstream.rpcBodies("app_finish_exam_attempt").size
        val replay = client.call(HttpMethod.Post, "$base/finalizar", key = key1)
        assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(obj(keyed.bodyAsText()), obj(replay.bodyAsText()))
        assertEquals(calls, upstream.rpcBodies("app_finish_exam_attempt").size)
        assertEquals("IDEMPOTENCY_KEY_INVALID", code(client.call(HttpMethod.Post, "$base/finalizar", key = "zzz").bodyAsText()))
        // cuerpo vacio: cualquier campo es 400
        assertEquals("VALIDATION", code(client.call(HttpMethod.Post, "$base/finalizar", body = """{"nota":10}""").bodyAsText()))
    }

    @Test
    fun finishOfAnAbandonedOrForeignAttemptIsRejectedWithoutReadingAnything() = testApplication {
        application { attemptModule() }
        upstream.finishOutcome = """{"outcome":"ATTEMPT_ABANDONED"}"""
        val abandoned = client.call(HttpMethod.Post, "$base/finalizar")
        assertEquals(HttpStatusCode.Conflict, abandoned.status)
        assertEquals("ATTEMPT_ABANDONED", code(abandoned.bodyAsText()))
        upstream.finishOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        val foreign = client.call(HttpMethod.Post, "$base/finalizar")
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("ATTEMPT_NOT_FOUND", code(foreign.bodyAsText()))
        assertEquals(2, upstream.sent.size, "solo las dos RPC")
    }

    @Test
    fun resultIsOnlyForAFinishedAttemptAndConformsToTheFrozenWire() = testApplication {
        application { attemptModule() }
        finishedFixture()
        val response = client.call(HttpMethod.Get, "$base/resultado")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, max-age=60", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-12", "200", body)
        assertResult(body)
        assertFalse(upstream.urls.any { "razonamiento" in it || "recursos_pregunta" in it }, "DEC-025/040: ni se leen")

        // sin finalizar (abierto o abandonado): 409 y NI UNA lectura de soluciones
        upstream.sent.clear()
        listOf("en_progreso", "abandonado").forEach { estado ->
            upstream.attemptRow = attemptRow(estado)
            val pending = client.call(HttpMethod.Get, "$base/resultado")
            assertEquals(HttpStatusCode.Conflict, pending.status, estado)
            assertEquals("ATTEMPT_NOT_FINISHED", code(pending.bodyAsText()))
        }
        assertNoSolutionWasRead()
        assertEquals(2, upstream.sent.size, "solo la lectura del intento")
    }

    @Test
    fun theGradeDefaultIsCorrectOverEvaluableTimesTenHalfUpAndItsBand() {
        assertEquals("6.67", AttemptService.gradeOf(2, 3).toPlainString())
        assertEquals("0.00", AttemptService.gradeOf(0, 0).toPlainString(), "sin evaluables (todo anulado) no se divide por cero")
        assertEquals("10.00", AttemptService.gradeOf(4, 4).toPlainString())
        assertEquals("0.63", AttemptService.gradeOf(1, 16).toPlainString(), "0.625 redondea hacia arriba")
        assertEquals(
            listOf("no_suficiente", "suficiente", "suficiente", "bien", "bien", "notable", "notable", "sobresaliente", "sobresaliente"),
            listOf("4.99", "5.00", "5.99", "6.00", "6.99", "7.00", "8.99", "9.00", "10.00").map { AttemptService.bandOf(it.toBigDecimal()) },
        )
    }

    // =================================================================== D2-11

    @Test
    fun abandonIsA204IdempotentByStateWithServerComputedElapsedTime() = testApplication {
        application { attemptModule() }
        val request = """{"posicionPregunta":2,"motivo":"salida_usuario"}"""
        val response = client.call(HttpMethod.Post, "$base/abandonar", request)
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
        assertEquals(
            mapOf("p_usuario_id" to TEST_UID, "p_intento_id" to attempt, "p_posicion" to "2", "p_motivo" to "salida_usuario"),
            upstream.rpcBodies("app_abandon_exam_attempt").single().mapValues { it.value.jsonPrimitive.content },
            "ni el usuario ni el tiempo transcurrido viajan desde el cliente",
        )
        upstream.abandonOutcome = """{"outcome":"OK","already":true}"""
        assertEquals(HttpStatusCode.NoContent, client.call(HttpMethod.Post, "$base/abandonar", request).status, "repetir es 204")
        assertEquals(listOf(AttemptOperation.CLOSE, AttemptOperation.CLOSE), limited)

        upstream.abandonOutcome = """{"outcome":"ATTEMPT_ALREADY_FINISHED"}"""
        val finished = client.call(HttpMethod.Post, "$base/abandonar", request)
        assertEquals(HttpStatusCode.Conflict, finished.status)
        assertEquals("ATTEMPT_ALREADY_FINISHED", code(finished.bodyAsText()))
        upstream.abandonOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        assertEquals("ATTEMPT_NOT_FOUND", code(client.call(HttpMethod.Post, "$base/abandonar", request).bodyAsText()))
        upstream.abandonOutcome = """{"outcome":"INVALID_POSITION","total":4}"""
        val beyond = client.call(HttpMethod.Post, "$base/abandonar", """{"posicionPregunta":99,"motivo":"otro"}""")
        assertEquals(HttpStatusCode.BadRequest, beyond.status)
        assertEquals("VALIDATION", code(beyond.bodyAsText()))

        // validacion ANTES de la RPC: motivo de servidor, posicion < 1, campos ausentes o de servidor
        val before = upstream.sent.size
        listOf(
            """{"posicionPregunta":2,"motivo":"inactividad"}""",
            """{"posicionPregunta":0,"motivo":"otro"}""",
            """{"motivo":"otro"}""",
            "",
            """{"posicionPregunta":2,"motivo":"otro","tiempoTranscurridoSegundos":5}""",
        ).forEach { body ->
            val rejected = client.call(HttpMethod.Post, "$base/abandonar", body.ifEmpty { null })
            assertEquals(HttpStatusCode.BadRequest, rejected.status, body)
            assertEquals("VALIDATION", code(rejected.bodyAsText()), body)
        }
        assertEquals(before, upstream.sent.size)
    }

    // =================================================================== D2-65

    @Test
    fun reviewOfAFinishedAttemptConformsAndKeepsPolicyContentHidden() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        val statuses = listOf("CORRECT", "WRONG", "ANNULLED", "UNANSWERED", "SKIPPED")
        upstream.reviewPage = { uri ->
            val after = Regex("posicion=gt\\.(\\d+)").find(uri)!!.groupValues[1].toInt()
            val limit = Regex("limit=(\\d+)").find(uri)!!.groupValues[1].toInt()
            "[" + (1..5).filter { it > after }.take(limit).joinToString(",") { questionRow(it, statuses[it - 1], review = true) } + "]"
        }
        // 1: acertada a la primera; 2: saltada y luego fallada (primera = salto); 3: respondida y despues anulada; 5: saltada
        upstream.answers = "[${answerRow(1, 1, graded = true)},${answerRow(2, 2, graded = true, first = null, revision = 2)},${answerRow(3, 1, graded = true)},${answerRow(5, null, graded = true)}]"
        val response = client.call(HttpMethod.Get, "$base/revision?limit=3")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-65", "200", body)
        val reviewed = obj(body)["attempt"]!!.jsonObject
        assertEquals(listOf("finalizado", "USER", "1200", esp), listOf("estado", "motivoFin", "duracionSegundos", "especialidadId").map { reviewed[it]!!.jsonPrimitive.content })
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("CORRECT", "WRONG", "ANNULLED"), items.map { it["status"]!!.jsonPrimitive.content })
        assertEquals(mapOf("opcionId" to opt(1, 1), "letra" to "A"), items[0]["correctAnswer"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(opt(1, 1), items[0]["firstAnswer"]!!.jsonObject["opcionId"]!!.jsonPrimitive.content)
        assertNull(items[1]["firstAnswer"]!!.jsonObject["opcionId"], "la primera entrega fue un salto")
        assertEquals(listOf(opt(2, 2), "false", "2"), listOf("opcionId", "esCorrecta", "revision").map { items[1]["finalAnswer"]!!.jsonObject[it]!!.jsonPrimitive.content })
        assertEquals("true", items[1]["cambioRespuesta"]!!.jsonPrimitive.content)
        // anulada: sin respuestas ni solucion
        listOf("firstAnswer", "finalAnswer", "correctAnswer").forEach { assertNull(items[2][it], it) }
        // DEC-025 / DEC-040 abiertas: explicacion oculta y recursos vacios, y ni siquiera se leen de la base
        items.forEach { assertNull(it["explanation"]); assertTrue(it["resources"]!!.jsonArray.isEmpty()) }
        assertFalse(upstream.urls.any { "razonamiento" in it || "recursos_pregunta" in it })
        assertEquals(listOf("Tema 1"), items[0]["etiquetas"]!!.jsonArray.map { it.jsonObject["nombre"]!!.jsonPrimitive.content })
        assertEquals(4200, items[0]["timings"]!!.jsonObject["tiempoRespuestaMs"]!!.jsonPrimitive.int)
        assertEquals(listOf(AttemptOperation.REVIEW), limited)

        val next = client.call(HttpMethod.Get, "$base/revision?limit=3&cursor=${obj(body)["nextCursor"]!!.jsonPrimitive.content}").bodyAsText()
        assertConforms("D2-65", "200", next)
        assertEquals(listOf("UNANSWERED", "SKIPPED"), obj(next)["items"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content })
        assertNull(obj(next)["items"]!!.jsonArray[0].jsonObject["finalAnswer"], "sin responder: sin respuestas")
        assertNotNull(obj(next)["items"]!!.jsonArray[1].jsonObject["finalAnswer"], "saltada: respuesta sin opcion")
        assertNull(obj(next)["nextCursor"])
        // limite por defecto 20 y tope 50; un cursor de D2-08 no vale para la revision
        client.call(HttpMethod.Get, "$base/revision?limit=500")
        assertTrue(upstream.urls.last { "preguntas_intento" in it }.endsWith("limit=51"))
        val foreignCursor = PageCursorCodec.encode(listOf(kotlinx.serialization.json.JsonPrimitive(2)), CursorScope(listOf("posicion asc"), mapOf("lista" to "preguntas")).fingerprint())
        assertEquals("INVALID_CURSOR", code(client.call(HttpMethod.Get, "$base/revision?cursor=$foreignCursor").bodyAsText()))
    }

    @Test
    fun reviewBeforeFinishIsA409AndReadsNoSolution() = testApplication {
        application { attemptModule() }
        listOf("en_progreso", "abandonado").forEach { estado ->
            upstream.attemptRow = attemptRow(estado)
            val response = client.call(HttpMethod.Get, "$base/revision")
            assertEquals(HttpStatusCode.Conflict, response.status, estado)
            assertEquals("ATTEMPT_NOT_FINISHED", code(response.bodyAsText()))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
        assertNoSolutionWasRead()
        assertEquals(2, upstream.sent.size, "solo la lectura del intento")
    }

    @Test
    fun theUserRateLimitAppliesBeforeTheProfileReadWithRetryAfter() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        rateLimit = AttemptOperation.REVIEW
        val response = client.call(HttpMethod.Get, "$base/revision")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("RATE_LIMITED", code(response.bodyAsText()))
        assertEquals("7", response.headers[HttpHeaders.RetryAfter])
        assertTrue(upstream.sent.isEmpty())
    }

    // =================================================================== transversales

    @Test
    fun aForeignOrUnknownAttemptIsTheSame404OnEveryAttemptRoute() = testApplication {
        application { attemptModule() }
        // El intento [other] existe pero es de OTRO usuario: la lectura filtrada por dueno no devuelve fila y la RPC responde
        // ATTEMPT_NOT_FOUND, exactamente igual que para un id que no existe.
        upstream.attemptRow = finishedRow()
        upstream.answerOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        upstream.finishOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        upstream.abandonOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        everyAttemptRoute(other).forEach { route ->
            val response = client.callRoute(route)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, response.status, "${route[0]} ${route[1]}: $body")
            assertEquals("ATTEMPT_NOT_FOUND", code(body), route[1])
            assertNull(response.headers[HttpHeaders.WWWAuthenticate], "${route[1]}: no es un fallo de sesion (MASTER-API-010)")
            assertFalse(body.contains(other), "el 404 no repite el id")
        }
        assertFalse(upstream.urls.any { "preguntas_intento" in it || "respuestas_intento" in it || "soluciones" in it }, "nada del intento ajeno se lee")
    }

    @Test
    fun withoutAValidTokenEveryRouteIs401AndTheUpstreamIsNeverTouched() = testApplication {
        application { attemptModule() }
        val routes = everyAttemptRoute() + listOf(listOf("POST", start, null, key1))
        routes.forEach { route ->
            listOf(null, "aaa.bbb.zzz", "no-es-un-jwt").forEach { token ->
                val response = client.callRoute(route, token)
                assertEquals(HttpStatusCode.Unauthorized, response.status, "${route[0]} ${route[1]} $token")
                assertEquals("UNAUTHENTICATED", code(response.bodyAsText()))
            }
        }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun aBlockedSuspendedOrPendingDeletionAccountFailsClosedOnEveryRoute() = testApplication {
        application { attemptModule() }
        upstream.examById = examRow()
        upstream.attemptRow = finishedRow()
        val expected = mapOf("bloqueada" to "ACCOUNT_BLOCKED", "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING", "suspendida" to "FORBIDDEN", "" to "FORBIDDEN")
        val routes = everyAttemptRoute() + listOf(listOf("POST", start, null, key1))
        expected.forEach { (state, errorCode) ->
            status = state
            routes.forEach { route ->
                val response = client.callRoute(route)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$state ${route[0]} ${route[1]}")
                assertEquals(errorCode, code(response.bodyAsText()), "$state ${route[1]}")
            }
        }
        assertTrue(upstream.sent.isEmpty(), "una cuenta no activa no lee ni escribe nada: ${upstream.urls}")
    }

    @Test
    fun invalidAttemptIdsAre400BeforeAnyUpstreamCall() = testApplication {
        application { attemptModule() }
        everyAttemptRoute("1-1-1-1-1").forEach { route ->
            val response = client.callRoute(route)
            assertEquals(HttpStatusCode.BadRequest, response.status, "${route[0]} ${route[1]}")
            assertEquals("VALIDATION", code(response.bodyAsText()))
        }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun withoutARuntimeTheRoutesExistButFailClosed() = testApplication {
        application { attemptModule(runtimePresent = false) }
        (everyAttemptRoute() + listOf(listOf("POST", start, null, key1))).forEach { route ->
            val response = client.callRoute(route)
            assertEquals(HttpStatusCode.InternalServerError, response.status, "${route[0]} ${route[1]}")
        }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun upstreamRowsThatBreakTheContractFailClosedInsteadOfInventingValues() = testApplication {
        application { attemptModule() }
        // mas de 200 preguntas congeladas: nunca se trunca en silencio
        upstream.attemptRow = attemptRow()
        upstream.frozen = "[" + (1..201).joinToString(",") { """{"posicion":$it,"pregunta_id":"cccccccc-cccc-4ccc-8ccc-${it.toString().padStart(12, '0')}","estado_final":null}""" } + "]"
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(client.call(HttpMethod.Get, base).bodyAsText()))
        // tipo de examen desconocido: no se inventa uno
        upstream.frozen = "[]"
        upstream.attemptRow = attemptRow(tipo = "misterio")
        val unknown = client.call(HttpMethod.Get, base)
        assertEquals(HttpStatusCode.BadGateway, unknown.status)
        // sinonimo legacy aceptado SOLO al leer y normalizado
        upstream.attemptRow = attemptRow(tipo = "guiado")
        assertEquals("razonado", obj(client.call(HttpMethod.Get, base).bodyAsText())["intento"]!!.jsonObject["tipoExamen"]!!.jsonPrimitive.content)
    }
}
