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

        /** `intentos_examen.creado_por_comando` de la fila: false = intento que NO creo `app_start_exam_attempt` (legacy). */
        var attemptHasCommandMarker = true
        var frozen = "[]"
        var questionPage: (String) -> String = { "[]" }
        var reviewPage: (String) -> String = { "[]" }
        var resultQuestions = "[]"
        var questionRevision: ((String) -> String)? = null
        var reviewRevision: ((String) -> String)? = null
        var resultRevision: String? = null
        var resourceLookup: (String) -> String = { "[]" }
        var signBody = """{"signedURL":"/object/sign/recursos-pregunta/x?token=t0k"}"""
        var signStatus = 200
        var answers = "[]"

        /** Estado HTTP de la lectura de `respuestas_intento` (por defecto 200): otro valor simula el upstream caido. */
        var answersStatus = 200
        var annulled = "[]"
        var tags = "[]"

        fun respond(rawUri: String): V1StubResponse {
            val uri = legacyFallbackAsPage(URLDecoder.decode(rawUri, Charsets.UTF_8))
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
                    attemptRow?.takeIf {
                        uri.contains("&id=eq.$attempt&usuario_id=eq.$TEST_UID&origen_intento=eq.examen") &&
                            // Como PostgREST: el filtro del marcador solo deja pasar la fila que lo tiene.
                            (attemptHasCommandMarker || !uri.contains("&creado_por_comando=not.is.null"))
                    }?.let { "[$it]" } ?: "[]"
                // 20261016100000: lecturas por revision. Sin revision configurada se devuelven las filas legacy (sin
                // `revisiones_pregunta`), y la pasarela completa con la lectura viva de siempre.
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,revision_pregunta_id,revisiones_pregunta(contenido)&") ->
                    questionRevision?.invoke(uri) ?: questionPage(uri)
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,revision_pregunta_id,revisiones_pregunta(contenido,solucion") ->
                    reviewRevision?.invoke(uri) ?: reviewPage(uri)
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,revision_pregunta_id,revisiones_pregunta(solucion") ->
                    resultRevision ?: resultQuestions
                uri.contains("/rest/v1/preguntas_intento?select=revisiones_pregunta!inner(recursos:contenido->recursos)") -> resourceLookup(uri)
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final&") -> frozen
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(soluciones_pregunta") -> resultQuestions
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(") && uri.contains("soluciones_pregunta") -> reviewPage(uri)
                uri.contains("/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(") -> questionPage(uri)
                uri.contains("/rest/v1/respuestas_intento") -> answers
                uri.contains("/rest/v1/preguntas_examen?select=pregunta_id&examen_id=eq.$exam&anulada=eq.true") -> annulled
                uri.contains("/rest/v1/resumen_intento_etiqueta") -> tags
                uri.contains("/storage/v1/object/sign/") -> signBody
                else -> error("peticion inesperada al upstream: $uri")
            }
            return V1StubResponse(
                when {
                    uri.contains("/rest/v1/respuestas_intento") -> answersStatus
                    uri.contains("/storage/v1/object/sign/") -> signStatus
                    else -> 200
                },
                body,
            )
        }
    }

    /**
     * La lectura viva de respaldo (filas sin revision) pide `posicion=in.(a,b,..)`; los manejadores de pagina del stub
     * entienden `posicion=gt.N&limit=M`. Las posiciones de una pagina son consecutivas: se traduce a esa forma.
     */
    private fun legacyFallbackAsPage(uri: String): String {
        val match = Regex("""&posicion=in\.\(([0-9,]+)\)""").find(uri) ?: return uri
        val positions = match.groupValues[1].split(',').map(String::toInt)
        return uri.replace(match.value, "&posicion=gt.${positions.min() - 1}")
            .replace(Regex("""&limit=\d+"""), "&limit=${positions.size}")
    }

    private val upstream = Upstream()

    /** EX13: registro propio de esta prueba (las metricas del examen se comprueban sin mezclar otras pruebas). */
    private val examMetrics = io.micrometer.core.instrument.simple.SimpleMeterRegistry()

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
            metrics = examMetrics,
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

    /**
     * Respuesta abierta (la fila propia con SU correccion, DEC-015) o corregida (intento finalizado). La opcion 1 es siempre la
     * correcta; un salto (`letter` = null) no lleva ni `es_correcta` ni `opcion_correcta_id`, como lo escribe la RPC.
     */
    private fun answerRow(
        n: Int,
        letter: Int?,
        graded: Boolean = false,
        first: Int? = letter,
        revision: Int = 1,
        correct: Boolean? = letter?.let { it == 1 },
        correctId: String? = letter?.let { opt(n, 1) },
    ): String {
        val open = """"pregunta_id":"${q(n)}","opcion_seleccionada_id":${letter?.let { "\"${opt(n, it)}\"" } ?: "null"},"es_correcta":${correct ?: "null"},""" +
            """"revision":$revision,"respondida_en":"2026-10-08T11:05:00+00:00","creada_en":"2026-10-08T11:05:00+00:00""""
        if (!graded) return """{$open,"opcion_correcta_id":${correctId?.let { "\"$it\"" } ?: "null"}}"""
        return "{$open," +
            """"actualizada_en":"2026-10-08T11:06:00+00:00","tiempo_respuesta_ms":4200,""" +
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

    /**
     * SEC-004: ni la tabla de soluciones, ni el razonamiento, ni los recursos. La correccion que DEC-015 revela (`es_correcta`,
     * `opcion_correcta_id`) sale de la fila propia del usuario en `respuestas_intento`, que no es ninguna de las tres.
     */
    private fun assertNoSolutionWasRead() {
        val leaked = upstream.urls.filter { url -> listOf("soluciones_pregunta", "razonamiento", "recursos_pregunta").any { it in url } }
        assertTrue(leaked.isEmpty(), "SEC-004: lectura de soluciones, razonamiento o recursos con el intento sin finalizar: $leaked")
    }

    /**
     * La UNICA lectura que revela: la fila propia de esa pregunta, con las columnas abiertas y filtrada tambien por el
     * dueno (`usuario_id`, defensa en profundidad: no depende solo de que la RPC haya validado el intento).
     */
    private fun ownAnswerRead(n: Int) =
        "/rest/v1/respuestas_intento?select=pregunta_id,opcion_seleccionada_id,es_correcta,opcion_correcta_id,revision,respondida_en,creada_en" +
            "&intento_id=eq.$attempt&usuario_id=eq.$TEST_UID&pregunta_id=in.(${q(n)})&"

    private fun answerBody(n: Int, letter: Int?, baseRevision: Int = 0) =
        """{"opcionSeleccionadaId":${letter?.let { "\"${opt(n, it)}\"" } ?: "null"},"tiempoRespuestaMs":4200,"baseRevision":$baseRevision}"""

    private fun answeredOutcome(revision: Int = 1, first: Boolean = true, noop: Boolean = false) =
        """{"outcome":"OK","estado":"ANSWERED","revision":$revision,"es_primera_respuesta":$first,"respondidas":1,"noop":$noop}"""

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
            listOf("GET", "$root/recursos/5e5e5e5e-0000-4000-8000-000000000001", null, null),
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
        // GATE SLICE 3 (F-09a): mas de 200 preguntas servibles es un examen no empezable (409 EXAM_EMPTY, el codigo canonico
        // mas cercano del contrato congelado), no un 502 del servicio de datos.
        upstream.startOutcome = """{"outcome":"EXAM_TOO_LARGE","total":201}"""
        val tooLarge = client.call(HttpMethod.Post, start, key = key)
        assertEquals(HttpStatusCode.Conflict, tooLarge.status, tooLarge.bodyAsText())
        assertEquals("EXAM_EMPTY", code(tooLarge.bodyAsText()))
        assertEquals(AttemptService.EXAM_TOO_LARGE_MESSAGE, obj(tooLarge.bodyAsText())["message"]!!.jsonPrimitive.content)
        assertEquals(3, upstream.rpcBodies("app_start_exam_attempt").size)
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
    fun getReturnsTheOpenAttemptWithTheGridCorrectedFromTheOwnAnswersAndNoTotals() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = attemptRow(respondidas = 3)
        upstream.frozen = "[${(1..6).joinToString(",") { frozenRow(it) }}]"
        // 1 fallada, 2 saltada, 3 acertada y despues anulada, 4 sin tocar, 5 acertada, 6 respondida con la correccion ausente
        upstream.answers = "[${answerRow(1, 2)},${answerRow(2, null)},${answerRow(3, 1)},${answerRow(5, 1)},${answerRow(6, 2, correct = null, correctId = null)}]"
        upstream.annulled = """[{"pregunta_id":"${q(3)}"}]"""
        val response = client.call(HttpMethod.Get, base)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-07", "200", body)
        val grid = obj(body)["grid"]!!.jsonArray.map { it.jsonObject }
        // DEC-015: lo respondido se ve corregido al releer el intento; la anulada gana; saltada y sin tocar, sin_responder.
        assertEquals(
            listOf("incorrecta", "sin_responder", "anulada", "sin_responder", "correcta", "respondida"),
            grid.map { it["estado"]!!.jsonPrimitive.content },
        )
        assertEquals(listOf(1, 1, 1, 0, 1, 1), grid.map { it["revision"]!!.jsonPrimitive.int })
        grid.forEach { assertEquals(setOf("posicion", "preguntaId", "revision", "estado"), it.keys, "el grid no lleva la opcion correcta") }
        val intento = obj(body)["intento"]!!.jsonObject
        assertEquals(2, intento["sinResponder"]!!.jsonPrimitive.int, "saltada + sin tocar (la anulada no cuenta)")
        // Los totales de correccion siguen siendo del intento finalizado.
        listOf("correctas", "falladas", "motivoFin", "deadline").forEach { assertNull(intento[it], it) }
        assertNoSolutionWasRead()
        assertTrue(
            upstream.urls.single { "respuestas_intento" in it }.contains("select=pregunta_id,opcion_seleccionada_id,es_correcta,opcion_correcta_id,revision,respondida_en,creada_en&"),
            "la correccion sale de la fila propia, sin la primera respuesta ni los tiempos: ${upstream.urls}",
        )
        assertTrue(upstream.urls.all { "usuario_id=eq.$TEST_UID" in it || "intentos_examen" !in it }, "el intento se lee SIEMPRE filtrado por su dueno")
        assertEquals(listOf(AttemptOperation.READ), limited, "E2-S01: D2-07 lleva el limite de lectura por usuario (enmienda 2026-10-14)")
    }

    @Test
    fun theOpenGridRevealsForTheThreeExamTypesAndTheRuleIsASingleFunction() = testApplication {
        application { attemptModule() }
        upstream.frozen = "[${frozenRow(1)},${frozenRow(2)}]"
        upstream.answers = "[${answerRow(1, 1)},${answerRow(2, 2)}]"
        listOf("rafaga", "razonado", "inmersivo").forEach { tipo ->
            upstream.attemptRow = attemptRow(tipo = tipo)
            val body = client.call(HttpMethod.Get, base).bodyAsText()
            assertConforms("D2-07", "200", body)
            assertEquals(listOf("correcta", "incorrecta"), obj(body)["grid"]!!.jsonArray.map { it.jsonObject["estado"]!!.jsonPrimitive.content }, tipo)
        }
        // DEC-015: hoy los tres tipos revelan; una regla por tipo se cambia SOLO en esta funcion.
        ExamKind.entries.forEach { assertTrue(AttemptService.revealsOnAnswer(it), it.wire) }
        assertNoSolutionWasRead()
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
    fun questionsArePagedByPositionWithTheCorrectionOfTheOwnAnswerAndNoSolutions() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = attemptRow()
        upstream.questionPage = { uri ->
            val after = Regex("posicion=gt\\.(\\d+)").find(uri)!!.groupValues[1].toInt()
            val limit = Regex("limit=(\\d+)").find(uri)!!.groupValues[1].toInt()
            "[" + (1..4).filter { it > after }.take(limit).joinToString(",") { questionRow(it) } + "]"
        }
        // 1 fallada, 2 saltada y anulada, 3 acertada y despues anulada, 4 acertada con la opcion correcta ya borrada (SET NULL)
        upstream.answers = "[${answerRow(1, 2)},${answerRow(2, null)},${answerRow(3, 1)},${answerRow(4, 1, correctId = null)}]"
        upstream.annulled = """[{"pregunta_id":"${q(2)}"},{"pregunta_id":"${q(3)}"}]"""
        val first = client.call(HttpMethod.Get, "$base/preguntas?limit=2")
        val body = first.bodyAsText()
        assertEquals(HttpStatusCode.OK, first.status, body)
        assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
        assertConforms("D2-08", "200", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2), items.map { it["posicion"]!!.jsonPrimitive.int })
        assertEquals(listOf("A", "B"), items[0]["opciones"]!!.jsonArray.map { it.jsonObject["letra"]!!.jsonPrimitive.content }, "opciones por posicion")
        assertEquals(setOf("id", "letra", "texto"), items[0]["opciones"]!!.jsonArray[0].jsonObject.keys, "una opcion no lleva marca de correccion")
        // DEC-015: la respuesta propia lleva SU correccion (aqui fallada, con la opcion correcta) al releer el intento.
        assertEquals(
            obj("""{"opcionId":"${opt(1, 2)}","revision":1,"respondidaEn":"2026-10-08T11:05:00Z","esCorrecta":false,"opcionCorrectaId":"${opt(1, 1)}"}"""),
            items[0]["respuesta"]!!.jsonObject,
        )
        assertEquals(setOf("revision", "respondidaEn"), items[1]["respuesta"]!!.jsonObject.keys, "saltada: respuesta sin opcion y sin correccion")
        assertEquals(listOf("false", "true"), items.map { it["anulada"]!!.jsonPrimitive.content })
        val questionKeys = setOf("posicion", "preguntaId", "codigoPregunta", "tipoPregunta", "titulo", "anulada", "opciones", "respuesta")
        items.forEach { item ->
            assertTrue(questionKeys.containsAll(item.keys), "D2-08 no lleva solucion, explicacion ni recursos: ${item.keys}")
            item["opciones"]!!.jsonArray.forEach { assertEquals(setOf("id", "letra", "texto"), it.jsonObject.keys, "una opcion no lleva marca de correccion") }
        }
        assertNoSolutionWasRead()
        assertTrue(upstream.urls.any { "pregunta_id=in.(${q(1)},${q(2)})" in it }, "solo las respuestas de la pagina")

        val cursor = obj(body)["nextCursor"]!!.jsonPrimitive.content
        val second = client.call(HttpMethod.Get, "$base/preguntas?limit=2&cursor=$cursor").bodyAsText()
        assertConforms("D2-08", "200", second)
        val rest = obj(second)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(3, 4), rest.map { it["posicion"]!!.jsonPrimitive.int })
        assertEquals(listOf("true", "false"), rest.map { it["anulada"]!!.jsonPrimitive.content })
        assertEquals(setOf("opcionId", "revision", "respondidaEn"), rest[0]["respuesta"]!!.jsonObject.keys, "anulada: la respuesta no ensena correccion")
        assertEquals(
            obj("""{"opcionId":"${opt(4, 1)}","revision":1,"respondidaEn":"2026-10-08T11:05:00Z","esCorrecta":true}"""),
            rest[1]["respuesta"]!!.jsonObject,
            "`opcion_correcta_id` nulo (FK ON DELETE SET NULL) se tolera: `esCorrecta` sin `opcionCorrectaId`",
        )
        assertNull(obj(second)["nextCursor"], "ultima pagina")
        assertNoSolutionWasRead()

        // limite: por defecto 10, tope 20 (se recorta), invalido 400; cursor de otra lista o corrupto 400 INVALID_CURSOR
        client.call(HttpMethod.Get, "$base/preguntas")
        assertTrue(upstream.urls.last { "revisiones_pregunta(" in it }.endsWith("limit=11"))
        client.call(HttpMethod.Get, "$base/preguntas?limit=999")
        assertTrue(upstream.urls.last { "revisiones_pregunta(" in it }.endsWith("limit=21"))
        val before = upstream.sent.size
        assertEquals("VALIDATION", code(client.call(HttpMethod.Get, "$base/preguntas?limit=0").bodyAsText()))
        assertEquals("INVALID_CURSOR", code(client.call(HttpMethod.Get, "$base/preguntas?cursor=AAAA").bodyAsText()))
        assertEquals(before, upstream.sent.size, "un parametro invalido no llega al upstream")
    }

    @Test
    fun questionsOfAFinishedAttemptCarryTheSameCorrectionOfTheOwnAnswerAndNoSolution() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        upstream.questionPage = { "[${questionRow(1, "CORRECT")},${questionRow(2, "ANNULLED")},${questionRow(3, "SKIPPED")}]" }
        upstream.answers = "[${answerRow(1, 1)},${answerRow(2, 2)},${answerRow(3, null)}]"
        val body = client.call(HttpMethod.Get, "$base/preguntas").bodyAsText()
        assertConforms("D2-08", "200", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("false", "true", "false"), items.map { it["anulada"]!!.jsonPrimitive.content })
        // DEC-015: uniforme con el intento abierto. La anulada (estado congelado) y la saltada no ensenan correccion.
        assertEquals(
            obj("""{"opcionId":"${opt(1, 1)}","revision":1,"respondidaEn":"2026-10-08T11:05:00Z","esCorrecta":true,"opcionCorrectaId":"${opt(1, 1)}"}"""),
            items[0]["respuesta"]!!.jsonObject,
        )
        assertEquals(setOf("opcionId", "revision", "respondidaEn"), items[1]["respuesta"]!!.jsonObject.keys)
        assertEquals(setOf("revision", "respondidaEn"), items[2]["respuesta"]!!.jsonObject.keys)
        // Sigue siendo la lectura abierta de la fila propia: ni soluciones, ni primera respuesta, ni tiempos.
        assertNoSolutionWasRead()
        assertFalse(upstream.urls.any { "primera_opcion_id" in it || "tiempo_respuesta" in it }, upstream.urls.toString())
    }

    // =================================================================== D2-09

    @Test
    fun aWrongAnswerIsRecordedThroughTheRpcRevealsTheCorrectOptionAndReplaysByKey() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        val request = """{"opcionSeleccionadaId":"${opt(1, 2)}","tiempoRespuestaMs":4200,"nivelConfianza":2,"baseRevision":0}"""
        // La fila que la RPC deja escrita: la opcion 2 es incorrecta y la correcta (la 1) queda copiada en la propia fila.
        upstream.answers = "[${answerRow(1, 2)}]"
        val response = client.call(HttpMethod.Put, path, request, key1)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-09", "200", body)
        // DEC-015 (decidida 2026-10-13): al responder se revela si acerto y cual era la correcta.
        assertEquals(
            obj("""{"revision":1,"estado":"ANSWERED","esCorrecta":false,"opcionCorrectaId":"${opt(1, 1)}","esPrimeraRespuesta":true,"respondidas":1}"""),
            obj(body),
        )
        val rpc = upstream.rpcBodies("app_record_attempt_answer").single()
        assertEquals(
            mapOf(
                "p_usuario_id" to TEST_UID, "p_intento_id" to attempt, "p_pregunta_id" to q(1), "p_opcion_id" to opt(1, 2),
                "p_tiempo_respuesta_ms" to "4200", "p_base_revision" to "0", "p_nivel_confianza" to "2",
            ),
            rpc.mapValues { it.value.jsonPrimitive.content },
        )
        // DOS llamadas, en este orden: la RPC (dueno, estado y pertenencia viven en ella) y la fila propia de ESA pregunta.
        assertEquals(2, upstream.sent.size, "la RPC y la lectura de la fila propia: ${upstream.urls}")
        assertTrue(upstream.urls[0].endsWith("/rest/v1/rpc/app_record_attempt_answer"), upstream.urls[0])
        assertTrue(ownAnswerRead(1) in upstream.urls[1], upstream.urls[1])
        assertNoSolutionWasRead()
        assertEquals(listOf(AttemptOperation.ANSWER), limited)

        val replay = client.call(HttpMethod.Put, path, request, key1)
        assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(obj(body), obj(replay.bodyAsText()), "la repeticion devuelve la misma correccion")
        assertEquals(2, upstream.sent.size, "la repeticion no vuelve a ejecutar el comando ni a leer")
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(client.call(HttpMethod.Put, path, request.replace("4200", "1"), key1).bodyAsText()))
    }

    @Test
    fun aCorrectAnswerRevealsItselfAsTheCorrectOption() = testApplication {
        application { attemptModule() }
        upstream.answers = "[${answerRow(1, 1)}]"
        val response = client.call(HttpMethod.Put, "$base/respuestas/${q(1)}", answerBody(1, 1), key1)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-09", "200", body)
        assertEquals(
            obj("""{"revision":1,"estado":"ANSWERED","esCorrecta":true,"opcionCorrectaId":"${opt(1, 1)}","esPrimeraRespuesta":true,"respondidas":1}"""),
            obj(body),
        )
        assertTrue(ownAnswerRead(1) in upstream.urls.single { "respuestas_intento" in it })
        assertNoSolutionWasRead()
    }

    @Test
    fun aSkipRevealsNothingAndTheLaterAnswerRevealsOnce() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(2)}"
        // saltar: opcion ausente o `null` explicito (el contrato la declara nullable). Aunque el upstream TUVIERA una fila
        // corregida, un salto no la lee: nada se revela.
        upstream.answers = "[${answerRow(2, 1)}]"
        upstream.answerOutcome = """{"outcome":"OK","estado":"SKIPPED","revision":1,"es_primera_respuesta":false,"respondidas":0,"noop":false}"""
        val skip = client.call(HttpMethod.Put, path, """{"opcionSeleccionadaId":null,"tiempoRespuestaMs":0,"baseRevision":0}""", key1)
        val skipBody = skip.bodyAsText()
        assertEquals(HttpStatusCode.OK, skip.status, skipBody)
        assertConforms("D2-09", "200", skipBody)
        assertEquals(obj("""{"revision":1,"estado":"SKIPPED","esPrimeraRespuesta":false,"respondidas":0}"""), obj(skipBody), "un salto no revela nada")
        assertEquals(JsonNull, upstream.rpcBodies("app_record_attempt_answer").last()["p_opcion_id"])
        assertEquals(1, upstream.sent.size, "un salto es SOLO la RPC: ${upstream.urls}")

        // la pregunta saltada se responde despues (una vez, sobre la revision del salto) y entonces si revela
        upstream.answers = "[${answerRow(2, 2, revision = 2)}]"
        upstream.answerOutcome = answeredOutcome(revision = 2)
        val answered = client.call(HttpMethod.Put, path, answerBody(2, 2, baseRevision = 1), key2)
        val answeredBody = answered.bodyAsText()
        assertEquals(HttpStatusCode.OK, answered.status, answeredBody)
        assertConforms("D2-09", "200", answeredBody)
        assertEquals(
            obj("""{"revision":2,"estado":"ANSWERED","esCorrecta":false,"opcionCorrectaId":"${opt(2, 1)}","esPrimeraRespuesta":true,"respondidas":1}"""),
            obj(answeredBody),
        )
        assertEquals("1", upstream.rpcBodies("app_record_attempt_answer").last()["p_base_revision"]!!.jsonPrimitive.content)
        assertNoSolutionWasRead()
    }

    @Test
    fun anAnnulledQuestionRevealsNothingAndReadsNothing() = testApplication {
        application { attemptModule() }
        // La RPC responde ANNULLED (no-op) aunque se envie una opcion; y aunque hubiera una fila corregida, no se lee.
        upstream.answers = "[${answerRow(3, 1)}]"
        upstream.answerOutcome = """{"outcome":"OK","estado":"ANNULLED","revision":0,"es_primera_respuesta":false,"respondidas":1,"noop":true}"""
        listOf(answerBody(3, 1) to key1, """{"tiempoRespuestaMs":0,"baseRevision":0}""" to key2).forEach { (request, key) ->
            val annulled = client.call(HttpMethod.Put, "$base/respuestas/${q(3)}", request, key)
            val body = annulled.bodyAsText()
            assertEquals(HttpStatusCode.OK, annulled.status, body)
            assertConforms("D2-09", "200", body)
            assertEquals(obj("""{"revision":0,"estado":"ANNULLED","esPrimeraRespuesta":false,"respondidas":1}"""), obj(body), "una anulada no revela nada")
        }
        assertEquals(2, upstream.sent.size, "solo las dos RPC: ${upstream.urls}")
    }

    @Test
    fun theSameOptionSentAgainWithANewKeyIsANoOpThatStillReveals() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        upstream.answers = "[${answerRow(1, 2)}]"
        val first = client.call(HttpMethod.Put, path, answerBody(1, 2), key1).bodyAsText()
        // Otra clave (la app perdio la respuesta y no reutilizo la clave): la RPC responde el mismo estado con `noop`.
        upstream.answerOutcome = answeredOutcome(noop = true)
        val again = client.call(HttpMethod.Put, path, answerBody(1, 2), key2)
        val body = again.bodyAsText()
        assertEquals(HttpStatusCode.OK, again.status, body)
        assertNull(again.headers[IdempotencyHeaders.REPLAYED], "no es una repeticion guardada: el comando corre y es un no-op")
        assertConforms("D2-09", "200", body)
        assertEquals(obj(first), obj(body), "el no-op revela lo mismo que la primera vez")
        assertEquals(listOf("false", opt(1, 1)), listOf("esCorrecta", "opcionCorrectaId").map { obj(body)[it]!!.jsonPrimitive.content })
        assertEquals(2, upstream.rpcBodies("app_record_attempt_answer").size)
        assertEquals(4, upstream.sent.size, "cada ejecucion: RPC + fila propia")
    }

    @Test
    fun aRevealedAnswerStaysLockedAndTheRejectionRevealsNothing() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        upstream.answers = "[${answerRow(1, 2)}]"
        val first = client.call(HttpMethod.Put, path, answerBody(1, 2), key1)
        assertEquals("false", obj(first.bodyAsText())["esCorrecta"]!!.jsonPrimitive.content)
        val before = upstream.sent.size
        // Ya sabe cual era la correcta e intenta cambiar a ella: la respuesta dada no se cambia.
        upstream.answerOutcome = """{"outcome":"ANSWER_LOCKED","current_revision":1}"""
        val change = client.call(HttpMethod.Put, path, answerBody(1, 1, baseRevision = 1), key2)
        val body = change.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, change.status, body)
        assertEquals("ANSWER_LOCKED", code(body))
        assertFalse(body.contains("orrect"), "el rechazo no lleva correccion: $body")
        assertEquals(before + 1, upstream.sent.size, "solo la RPC: un rechazo no lee la fila")
        assertEquals(opt(1, 1), upstream.rpcBodies("app_record_attempt_answer").last()["p_opcion_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailedRevealReadFailsTheCallIsNotStoredAsAReplayAndTheRetryReveals() = testApplication {
        application { attemptModule() }
        val path = "$base/respuestas/${q(1)}"
        // La RPC ya grabo la respuesta, pero la lectura de la fila falla: la llamada falla con el error canonico de upstream.
        upstream.answers = "[${answerRow(1, 2)}]"
        upstream.answersStatus = 503
        val failed = client.call(HttpMethod.Put, path, answerBody(1, 2), key1)
        val failedBody = failed.bodyAsText()
        assertEquals(HttpStatusCode.BadGateway, failed.status, failedBody)
        assertEquals("UPSTREAM_ERROR", code(failedBody))
        assertNull(failed.headers[IdempotencyHeaders.REPLAYED])
        assertFalse(failedBody.contains("orrect"), failedBody)
        // La fila que no esta (o sin correccion) tras un ANSWERED contradice a la RPC: 502, tampoco se guarda.
        upstream.answersStatus = 200
        upstream.answers = "[]"
        upstream.answerOutcome = answeredOutcome(noop = true)
        val missing = client.call(HttpMethod.Put, path, answerBody(1, 2), key1)
        assertEquals(HttpStatusCode.BadGateway, missing.status)
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(missing.bodyAsText()))
        upstream.answers = "[${answerRow(1, 2, correct = null, correctId = null)}]"
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(client.call(HttpMethod.Put, path, answerBody(1, 2), key1).bodyAsText()))

        // Reintento con la MISMA clave: ningun error quedo guardado, el comando corre (no-op en la RPC) y ahora si revela.
        upstream.answers = "[${answerRow(1, 2)}]"
        val retry = client.call(HttpMethod.Put, path, answerBody(1, 2), key1)
        val body = retry.bodyAsText()
        assertEquals(HttpStatusCode.OK, retry.status, body)
        assertNull(retry.headers[IdempotencyHeaders.REPLAYED], "el reintento re-ejecuta: no habia nada guardado")
        assertConforms("D2-09", "200", body)
        assertEquals(
            obj("""{"revision":1,"estado":"ANSWERED","esCorrecta":false,"opcionCorrectaId":"${opt(1, 1)}","esPrimeraRespuesta":true,"respondidas":1}"""),
            obj(body),
        )
        assertEquals(4, upstream.rpcBodies("app_record_attempt_answer").size)
        // Y a partir de aqui la respuesta buena SI queda guardada para esa clave.
        val replay = client.call(HttpMethod.Put, path, answerBody(1, 2), key1)
        assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(obj(body), obj(replay.bodyAsText()))
        assertEquals(4, upstream.rpcBodies("app_record_attempt_answer").size)
    }

    @Test
    fun aMissingCorrectOptionOnTheOwnRowIsToleratedWhenAnswering() = testApplication {
        application { attemptModule() }
        // `respuestas_intento.opcion_correcta_id` es ON DELETE SET NULL: `es_correcta` puede seguir ahi sin la opcion.
        upstream.answers = "[${answerRow(1, 2, correctId = null)}]"
        val response = client.call(HttpMethod.Put, "$base/respuestas/${q(1)}", answerBody(1, 2), key1)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-09", "200", body)
        assertEquals(obj("""{"revision":1,"estado":"ANSWERED","esCorrecta":false,"esPrimeraRespuesta":true,"respondidas":1}"""), obj(body))
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
        assertTrue(upstream.urls.last { "revisiones_pregunta(" in it }.endsWith("limit=51"))
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

    @Test
    fun theReadLimitAppliesToGetQuestionsAndResultBeforeTheProfileRead() = testApplication {
        // E2-S01: D2-07, D2-08 y D2-12 tambien llevan limite por usuario (antes no tenian ninguno).
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        rateLimit = AttemptOperation.READ
        listOf(base, "$base/preguntas", "$base/resultado").forEach { path ->
            val response = client.call(HttpMethod.Get, path)
            assertEquals(HttpStatusCode.TooManyRequests, response.status, path)
            assertEquals("RATE_LIMITED", code(response.bodyAsText()))
            assertEquals("7", response.headers[HttpHeaders.RetryAfter])
        }
        assertTrue(upstream.sent.isEmpty(), "rechazado antes de tocar el upstream")
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

    /**
     * GATE SLICE 3 (F-01): un intento del propio usuario que NO creo `app_start_exam_attempt` (lo abrio el `iniciar` legacy,
     * sin EntitlementPolicy) no existe para ninguna ruta: las lecturas lo filtran por `creado_por_comando` y las RPC
     * endurecidas (20261008100200) responden ATTEMPT_NOT_FOUND. Control negativo: sin el filtro en AttemptGateway.attempt el
     * upstream de pruebas devuelve la fila y D2-07 / D2-08 / D2-12 / D2-65 responden 200 con el contenido.
     */
    @Test
    fun anOwnAttemptThatTheStartCommandDidNotCreateIsA404OnEveryAttemptRoute() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = finishedRow()
        upstream.attemptHasCommandMarker = false
        upstream.answerOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        upstream.finishOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        upstream.abandonOutcome = """{"outcome":"ATTEMPT_NOT_FOUND"}"""
        everyAttemptRoute().forEach { route ->
            val response = client.callRoute(route)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, response.status, "${route[0]} ${route[1]}: $body")
            assertEquals("ATTEMPT_NOT_FOUND", code(body), route[1])
        }
        assertTrue(
            upstream.urls.filter { "/rest/v1/intentos_examen" in it }.let { reads -> reads.isNotEmpty() && reads.all { "&creado_por_comando=not.is.null" in it } },
            "toda lectura del intento exige el marcador del comando",
        )
        assertFalse(upstream.urls.any { "preguntas_intento" in it || "respuestas_intento" in it || "soluciones" in it }, "nada del intento sin marcador se lee")
        // Control positivo: el mismo intento CON el marcador si se sirve (el 404 de arriba no es vacuo).
        upstream.attemptHasCommandMarker = true
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Get, "/api/v1/app/intentos/$attempt").status)
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

    // =================================================================== EX14 / EX15: revision inmutable y recursos V1

    private val resImage = "5e5e5e5e-0000-4000-8000-000000000001"
    private val resAudio = "5e5e5e5e-0000-4000-8000-000000000002"
    private val resOther = "5e5e5e5e-0000-4000-8000-0000000000ff"

    private fun rev(n: Int) = "7e7e7e7e-0000-4000-8000-00000000000$n"

    private fun resourceJson(id: String, tipo: String, ext: String, mime: String, n: Int = 1, envelope: String = "null", bucket: String = "recursos-pregunta") =
        """{"id":"$id","tipo":"$tipo","titulo":"T-$tipo","posicion":1,"bucket":"$bucket","ruta":"preguntas/${q(n)}/$id.$ext",""" +
            """"mime":"$mime","bytes":1200,"durationMs":${if (tipo == "audio") "4100" else "null"},"envelope":$envelope}"""

    /** Fila tal como la devuelve PostgREST para una pregunta LIGADA a su revision (`solucion` ya proyectada por la flecha). */
    private fun revisionRow(n: Int, status: String? = null, solution: Boolean = false, resources: String = "[]") =
        """{"posicion":$n,"pregunta_id":"${q(n)}","estado_final":${status?.let { "\"$it\"" } ?: "null"},"revision_pregunta_id":"${rev(n)}",""" +
            """"revisiones_pregunta":{"contenido":{"v":1,"codigo":"P-$n","tipo":"rafaga","titulo":"Enunciado X $n",""" +
            """"opciones":[{"id":"${opt(n, 2)}","letra":"B","texto":"dos X","posicion":2},{"id":"${opt(n, 1)}","letra":"A","texto":"uno X","posicion":1}],""" +
            """"recursos":$resources,"etiquetas":[{"id":"$tag","nombre":"Tema 1"}]}""" +
            (if (solution) ""","solucion":"${opt(n, 1)}"""" else "") + "}}"

    private val envelopeJson = """{"stepMs":50,"samples":"AH//gA=="}"""

    @Test
    fun questionsOfABoundAttemptComeFromItsRevisionWithResourceMetadataButNoStoragePath() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = attemptRow()
        upstream.questionRevision = {
            "[" + revisionRow(1, resources = "[${resourceJson(resImage, "imagen", "png", "image/png")},${resourceJson(resAudio, "audio", "mp3", "audio/mpeg", envelope = envelopeJson)}]") +
                "," + revisionRow(2) + "]"
        }
        upstream.answers = "[]"
        val response = client.call(HttpMethod.Get, "$base/preguntas?limit=2")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-08", "200", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Enunciado X 1", "Enunciado X 2"), items.map { it["titulo"]!!.jsonPrimitive.content }, "contenido de la REVISION, no el vivo")
        assertEquals(listOf("uno X", "dos X"), items[0]["opciones"]!!.jsonArray.map { it.jsonObject["texto"]!!.jsonPrimitive.content })
        val recursos = items[0]["recursos"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(resImage, resAudio), recursos.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("id", "tipo", "titulo", "mimeType", "version", "sizeBytes"), recursos[0].keys)
        assertEquals(setOf("id", "tipo", "titulo", "mimeType", "version", "sizeBytes", "durationMs", "envelope"), recursos[1].keys)
        assertEquals(obj(envelopeJson), recursos[1]["envelope"]!!.jsonObject)
        assertEquals(16, recursos[0]["version"]!!.jsonPrimitive.content.length)
        assertNull(items[1]["recursos"], "sin recursos: el campo aditivo no viaja")
        listOf("preguntas/", "recursos-pregunta", "ruta", "bucket", "solucion", "razonamiento").forEach {
            assertFalse(it in body, "nunca la ruta interna, el bucket ni la solucion: $it")
        }
        assertFalse(upstream.urls.any { "preguntas(" in it }, "intento ligado: ninguna lectura del contenido vivo")
        assertNoSolutionWasRead()
        assertFalse(upstream.urls.any { "solucion" in it }, "D2-08 no pide la solucion de la revision")
    }

    @Test
    fun reviewAndResultOfABoundAttemptUseTheRevisionSolutionNotTheLiveOne() = testApplication {
        application { attemptModule() }
        finishedFixture()
        upstream.reviewRevision = { "[" + (1..4).joinToString(",") { revisionRow(it, if (it == 1) "CORRECT" else "WRONG", solution = true) } + "]" }
        upstream.resultRevision = "[" + (1..4).joinToString(",") { revisionRow(it, if (it == 1) "CORRECT" else "WRONG", solution = true) } + "]"
        val review = client.call(HttpMethod.Get, "$base/revision")
        val body = review.bodyAsText()
        assertEquals(HttpStatusCode.OK, review.status, body)
        assertConforms("D2-65", "200", body)
        val first = obj(body)["items"]!!.jsonArray[0].jsonObject
        assertEquals("Enunciado X 1", first["enunciado"]!!.jsonPrimitive.content)
        assertEquals(opt(1, 1), first["correctAnswer"]!!.jsonObject["opcionId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, first["explanation"] ?: JsonNull, "DEC-025: la explicacion sigue sin servirse (politica unica)")
        assertFalse(upstream.urls.any { "razonamiento" in it || "soluciones_pregunta" in it || "preguntas(" in it }, "solo la revision")
        val result = client.call(HttpMethod.Get, "$base/resultado").bodyAsText()
        assertConforms("D2-12", "200", result)
        assertEquals(opt(2, 1), obj(result)["preguntas"]!!.jsonArray[1].jsonObject["opcionCorrecta"]!!.jsonPrimitive.content)
    }

    @Test
    fun answerRevealsTheCorrectionOfTheCommandInOneRoundTrip() = testApplication {
        application { attemptModule() }
        upstream.answerOutcome =
            """{"outcome":"OK","estado":"ANSWERED","revision":1,"es_primera_respuesta":true,"respondidas":1,"es_correcta":false,"opcion_correcta_id":"${opt(1, 1)}","noop":false}"""
        val response = client.call(HttpMethod.Put, "$base/respuestas/${q(1)}", answerBody(1, 2), key1)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-09", "200", body)
        assertEquals(false, obj(body)["esCorrecta"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(opt(1, 1), obj(body)["opcionCorrectaId"]!!.jsonPrimitive.content)
        assertEquals(1, upstream.sent.size, "una sola llamada: la RPC (sin releer respuestas_intento)")
        val dbCalls = examMetrics.find("exam_db_calls").tag("op", ExamOp.ANSWER).summary()
        assertNotNull(dbCalls)
        assertEquals(1.0, dbCalls.max(), "DB_CALLS_PER_ANSWER (sin identidad ni idempotencia remotas en la prueba)")
        // Reintento con la misma clave: replay sin ejecutar, contado.
        client.call(HttpMethod.Put, "$base/respuestas/${q(1)}", answerBody(1, 2), key1)
        assertEquals(1, upstream.sent.size)
        assertEquals(1.0, examMetrics.find("exam_idempotency_replay_total").tag("op", ExamOp.ANSWER).counter()!!.count())
    }

    @Test
    fun resourceAccessIsOwnerAndAttemptBoundAndSignsOnlyTheContractPath() = testApplication {
        application { attemptModule() }
        upstream.attemptRow = attemptRow()
        val image = resourceJson(resImage, "imagen", "png", "image/png")
        upstream.resourceLookup = { uri ->
            if (resImage in uri) """[{"revisiones_pregunta":{"recursos":[$image]}}]""" else "[]"
        }
        val ok = client.call(HttpMethod.Get, "$base/recursos/$resImage")
        val body = ok.bodyAsText()
        assertEquals(HttpStatusCode.OK, ok.status, body)
        assertEquals("no-store", ok.headers[HttpHeaders.CacheControl])
        assertEquals(setOf("url", "expiresAt", "mimeType", "version"), obj(body).keys)
        assertTrue(obj(body)["url"]!!.jsonPrimitive.content.contains("token="))
        assertEquals(now.plusSeconds(AttemptService.RESOURCE_URL_TTL_SECONDS.toLong()).toString(), obj(body)["expiresAt"]!!.jsonPrimitive.content)
        assertTrue(upstream.urls.any { it.contains("/storage/v1/object/sign/recursos-pregunta/preguntas/${q(1)}/$resImage.png") })
        assertTrue(upstream.urls.any { it.contains("&intento_id=eq.$attempt&revisiones_pregunta.contenido=cs.") }, "el recurso se busca SOLO en las revisiones de ESTE intento")
        assertEquals(AttemptOperation.RESOURCE, limited.last())

        // Un recurso que no esta en el intento (aunque exista en otro): 404 RESOURCE_NOT_FOUND, sin firmar nada.
        val signs = upstream.urls.count { "/storage/v1/" in it }
        val missing = client.call(HttpMethod.Get, "$base/recursos/$resOther")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("RESOURCE_NOT_FOUND", code(missing.bodyAsText()))
        // Intento ajeno: el mismo 404 de siempre, sin buscar el recurso.
        val foreign = client.call(HttpMethod.Get, "/api/v1/app/intentos/$other/recursos/$resImage")
        assertEquals("ATTEMPT_NOT_FOUND", code(foreign.bodyAsText()))
        // Ubicacion fuera del contrato Q3 (bucket ajeno o ruta que no es la del recurso): nunca se firma.
        upstream.resourceLookup = { """[{"revisiones_pregunta":{"recursos":[${resourceJson(resImage, "imagen", "png", "image/png", bucket = "news-assets")}]}}]""" }
        assertEquals("RESOURCE_NOT_FOUND", code(client.call(HttpMethod.Get, "$base/recursos/$resImage").bodyAsText()))
        upstream.resourceLookup = { """[{"revisiones_pregunta":{"recursos":[${resourceJson(resImage, "imagen", "png", "image/png").replace("$resImage.png", "$resOther.png")}]}}]""" }
        assertEquals("RESOURCE_NOT_FOUND", code(client.call(HttpMethod.Get, "$base/recursos/$resImage").bodyAsText()))
        upstream.resourceLookup = { """[{"revisiones_pregunta":{"recursos":[${resourceJson(resImage, "pdf", "pdf", "application/pdf")}]}}]""" }
        assertEquals("RESOURCE_NOT_FOUND", code(client.call(HttpMethod.Get, "$base/recursos/$resImage").bodyAsText()), "PDF fuera de V1")
        assertEquals(signs, upstream.urls.count { "/storage/v1/" in it }, "ningun rechazo firma nada")
        // Storage caido o lento: 503 reintentable, nunca la ruta en el error.
        upstream.resourceLookup = { """[{"revisiones_pregunta":{"recursos":[$image]}}]""" }
        upstream.signStatus = 500
        val down = client.call(HttpMethod.Get, "$base/recursos/$resImage")
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertFalse("preguntas/" in down.bodyAsText())
        // Id mal formado: 400 antes de tocar nada.
        assertEquals(HttpStatusCode.BadRequest, client.call(HttpMethod.Get, "$base/recursos/no-es-uuid").status)
        // Sin token: 401.
        assertEquals(HttpStatusCode.Unauthorized, client.call(HttpMethod.Get, "$base/recursos/$resImage", token = null).status)
        // Metricas sin PII: ninguna etiqueta lleva un id, una ruta o un texto.
        val errors = examMetrics.find("exam_resource_access_error_total").counters().sumOf { it.count() }
        assertTrue(errors >= 5.0, "errores de acceso contados: $errors")
        examMetrics.meters.filter { it.id.name.startsWith("exam_") }.flatMap { it.id.tags }.forEach { t ->
            assertTrue(t.value.matches(Regex("^([a-z_0-9]+|[A-Z_0-9]+)$")), "etiqueta sin PII: ${t.key}=${t.value}")
        }
    }

    @Test
    fun aRevisionConflictIsDeterministicAndCounted() = testApplication {
        application { attemptModule() }
        upstream.answerOutcome = """{"outcome":"REVISION_CONFLICT","current_revision":1}"""
        val response = client.call(HttpMethod.Put, "$base/respuestas/${q(1)}", answerBody(1, 1), key1)
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("REVISION_CONFLICT", code(response.bodyAsText()))
        assertEquals(1L, obj(response.bodyAsText())["details"]!!.jsonObject["currentRevision"]!!.jsonPrimitive.content.toLong())
        assertEquals(1.0, examMetrics.find("exam_revision_conflict_total").tag("op", ExamOp.ANSWER).counter()!!.count())
        assertEquals(1.0, examMetrics.find("exam_4xx_total").tag("code", "REVISION_CONFLICT").counter()!!.count())
    }
}
