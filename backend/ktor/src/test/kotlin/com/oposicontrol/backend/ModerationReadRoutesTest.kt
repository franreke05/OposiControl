package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FA12 (D5-20, D5-21, D5-22, D5-25, D5-26, D5-28, D5-29) de punta a punta: ruta -> [AdminV1Gateway] -> transporte real, contra
 * un PostgREST falso con ESTADO. El cable se valida contra `openapi/moderation/moderation.yaml` con [SchemaChecker]. Las siete
 * rutas son las que ya llama el cliente OposiControl: cada prueba de forma comprueba tambien que el cable anterior sigue ahi.
 */
class ModerationReadRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val json = Json { ignoreUnknownKeys = true }

    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val userUid = "22222222-bbbb-4bbb-8bbb-000000000002"
    private val adminToken = "admin-token"
    private val userToken = "user-token"
    private val author = "33333333-cccc-4ccc-8ccc-000000000003"
    private val moderator = "44444444-dddd-4ddd-8ddd-000000000004"
    private val categoria = "55555555-eeee-4eee-8eee-000000000005"
    private val otraCategoria = "55555555-eeee-4eee-8eee-000000000006"
    private val pregunta = "66666666-ffff-4fff-8fff-000000000006"
    private val ghost = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"

    // ------------------------------------------------------------------ PostgREST falso con estado

    private inner class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val tables = mutableMapOf<String, MutableList<JsonObject>>()
        var failOn: String? = null
        var failCounts = false
        private var seq = 0

        init {
            listOf(
                "publicaciones_oposired", "comentarios_oposired", "reportes_contenido", "acciones_moderacion", "feedback_pregunta",
                "perfiles_usuario", "categorias_oposired", "preguntas", "opciones_pregunta", "soluciones_pregunta", "recursos_pregunta",
            ).forEach { tables[it] = mutableListOf() }
            row("perfiles_usuario", "usuario_id" to author, "nombre_mostrar" to "Ana Autora")
            row("perfiles_usuario", "usuario_id" to moderator, "nombre_mostrar" to "Mario Moderador")
            row("categorias_oposired", "id" to categoria, "nombre" to "General")
            row("categorias_oposired", "id" to otraCategoria, "nombre" to "Dudas")
            row("preguntas", "id" to pregunta, "codigo_pregunta" to "RAF-001", "tipo_pregunta" to "rafaga", "titulo" to "Enunciado")
        }

        fun id(): String = "aaaaaaaa-0000-4000-8000-%012d".format(++seq)

        /** Instante con offset `+00:00`, como lo devuelve PostgREST: el `+` obliga a codificar el cursor al interpolarlo. */
        fun at(minute: Int): String = "2026-10-08T10:%02d:00+00:00".format(minute)

        fun row(table: String, vararg fields: Pair<String, Any?>): JsonObject {
            val obj = JsonObject(
                fields.associate { (k, v) ->
                    k to when (v) {
                        null -> JsonNull
                        is Boolean -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                },
            )
            tables.getValue(table) += obj
            return obj
        }

        fun thread(minute: Int, titulo: String = "Hilo $minute", estado: String = "publicada", categoriaId: String? = categoria, contenido: String = "Contenido $minute"): String {
            val id = id()
            row(
                "publicaciones_oposired", "id" to id, "usuario_id" to author, "categoria_id" to categoriaId, "titulo" to titulo,
                "contenido" to contenido, "estado" to estado, "fijada" to false, "creado_en" to at(minute), "actualizado_en" to at(minute),
            )
            return id
        }

        fun reply(threadId: String, minute: Int, estado: String = "publicado"): String {
            val id = id()
            row(
                "comentarios_oposired", "id" to id, "publicacion_id" to threadId, "usuario_id" to author,
                "contenido" to "Respuesta $minute", "estado" to estado, "creado_en" to at(minute),
            )
            return id
        }

        fun report(tipoEntidad: String, entidadId: String, minute: Int, estado: String = "pendiente"): String {
            val id = id()
            row(
                "reportes_contenido", "id" to id, "tipo_entidad" to tipoEntidad, "entidad_id" to entidadId, "motivo" to "spam",
                "descripcion" to "Reporte $minute", "estado" to estado, "reportado_por" to author, "creado_en" to at(minute), "revisado_en" to null,
            )
            return id
        }

        fun action(reportId: String, minute: Int, tipo: String = "advertir"): String {
            val id = id()
            row(
                "acciones_moderacion", "id" to id, "reporte_id" to reportId, "moderador_id" to moderator, "tipo_accion" to tipo,
                "notas" to "Nota $minute", "creado_en" to at(minute),
            )
            return id
        }

        fun feedback(minute: Int, estado: String = "pendiente", tipo: String = "mal_redactada", preguntaId: String = pregunta): String {
            val id = id()
            row(
                "feedback_pregunta", "id" to id, "usuario_id" to author, "pregunta_id" to preguntaId, "intento_id" to null,
                "tipo_feedback" to tipo, "descripcion" to "Feedback $minute", "estado" to estado, "creado_en" to at(minute),
            )
            return id
        }

        val requests: List<String> get() = sent.map { "${it.method()} ${URLDecoder.decode(it.uri().toString(), Charsets.UTF_8)}" }

        private fun text(row: JsonObject, column: String): String? = (row[column] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private val keyset = Regex("""^\((\w+)\.(lt|gt)\.([^,]+),and\(\w+\.eq\.[^,]+,id\.(lt|gt)\.([0-9a-f-]+)\)\)$""")

        fun respond(rawUri: String): V1StubResponse {
            val request = sent.last()
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            failOn?.let { if (uri.contains(it)) return V1StubResponse(500, """{"message":"boom"}""") }
            val table = uri.substringAfter("/rest/v1/").substringBefore('?')
            val source = tables[table] ?: error("tabla inesperada: $uri")
            val q = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            var rows: List<JsonObject> = source.toList()
            q.forEach { (key, value) ->
                rows = when {
                    key in setOf("select", "order", "limit", "offset") -> rows
                    key == "or" -> {
                        val m = keyset.matchEntire(value) ?: error("keyset inesperado: $value")
                        val (column, op, ts, _, id) = m.destructured
                        rows.filter { r ->
                            val c = text(r, column)!!.compareTo(ts)
                            val i = text(r, "id")!!.compareTo(id)
                            if (op == "lt") c < 0 || (c == 0 && i < 0) else c > 0 || (c == 0 && i > 0)
                        }
                    }
                    value.startsWith("eq.") -> rows.filter { text(it, key) == value.removePrefix("eq.") }
                    value.startsWith("in.(") -> value.removePrefix("in.(").removeSuffix(")").split(',').toSet().let { set -> rows.filter { text(it, key) in set } }
                    else -> error("filtro inesperado: $key=$value")
                }
            }
            if (request.headers().firstValue("Prefer").orElse("") == "count=exact") {
                if (failCounts) return V1StubResponse(500, """{"message":"count boom"}""")
                return V1StubResponse(206, "[]", contentRange = if (rows.isEmpty()) "*/0" else "0-0/${rows.size}")
            }
            q["order"]?.split(',')?.map { it.substringBefore('.') to it.endsWith(".desc") }?.let { keys ->
                rows = rows.sortedWith { a, b ->
                    keys.firstNotNullOfOrNull { (column, desc) ->
                        text(a, column).orEmpty().compareTo(text(b, column).orEmpty()).takeIf { it != 0 }?.let { if (desc) -it else it }
                    } ?: 0
                }
            }
            rows = rows.drop(q["offset"]?.toInt() ?: 0).take(q["limit"]?.toInt() ?: Int.MAX_VALUE)
            if (table == "publicaciones_oposired" && q["select"].orEmpty().contains("comentarios_oposired(count)")) {
                rows = rows.map { r ->
                    val count = tables.getValue("comentarios_oposired").count { text(it, "publicacion_id") == text(r, "id") }
                    JsonObject(r + ("comentarios_oposired" to JsonArray(listOf(JsonObject(mapOf("count" to JsonPrimitive(count)))))))
                }
            }
            return V1StubResponse(200, JsonArray(rows).toString())
        }
    }

    private val upstream = Upstream()

    private fun Application.moderationModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") {
                authenticate { credentials ->
                    when (credentials.token) {
                        adminToken -> UserIdPrincipal(adminUid)
                        userToken -> UserIdPrincipal(userUid)
                        else -> null
                    }
                }
            }
        }
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        routing {
            authenticate("gateway-auth") {
                install(AdminAuthorizationPlugin) {
                    this.appEnv = "production"
                    this.hasAdminRole = { it == adminUid }
                }
                installAdminAuditActor()
                adminV1Routes(gateway)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun ApplicationTestBuilder.call(path: String, token: String? = adminToken): HttpResponse =
        client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    private fun ids(body: String, key: String = "items"): List<String> = obj(body)[key]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun next(body: String): String? = (obj(body)["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun conforms(contractId: String, status: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue(status)
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId $status does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private suspend fun ApplicationTestBuilder.ok(contractId: String, path: String): String {
        val response = call(path)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, "$path -> $body")
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], path)
        conforms(contractId, "200", body)
        return body
    }

    /** Un 4xx sale con el sobre canonico, con el codigo esperado, y (si se pide) sin haber tocado la base. */
    private suspend fun ApplicationTestBuilder.rejected(contractId: String, path: String, status: HttpStatusCode, code: String, noUpstream: Boolean = true) {
        val before = upstream.sent.size
        val response = call(path)
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$path -> $body")
        assertEquals(code, code(body), path)
        // Sobre canonico (ErrorEnvelope) y error DECLARADO por la operacion: estado y codigo.
        val envelope = obj(body)
        assertTrue(envelope["message"]!!.jsonPrimitive.content.isNotBlank() && envelope["requestId"]!!.jsonPrimitive.content.isNotBlank(), body)
        val op = docs.operations().first { it.contractId == contractId }
        assertTrue(status.value.toString() in docs.responses(op), "$contractId does not document $status")
        assertTrue(code in (op.op["x-error-codes"] as List<*>), "$contractId does not list $code")
        if (noUpstream) assertEquals(before, upstream.sent.size, "$path reached the upstream: ${upstream.requests.drop(before)}")
    }

    private val operations = listOf(
        "/api/v1/admin/moderacion/hilos",
        "/api/v1/admin/moderacion/hilos/$ghost",
        "/api/v1/admin/moderacion/hilos/$ghost/respuestas",
        "/api/v1/admin/reportes",
        "/api/v1/admin/reportes/$ghost",
        "/api/v1/admin/feedback/preguntas",
        "/api/v1/admin/feedback/preguntas/$ghost",
    )

    // ================================================================== 401 / 403

    @Test
    fun everyOperationRejectsAnUnauthenticatedCallerAndTouchesNothing() = testApplication {
        application { moderationModule() }
        operations.forEach { path -> assertEquals(HttpStatusCode.Unauthorized, call(path, token = null).status, path) }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun everyOperationRejectsANonAdminAccountWith403AndTouchesNothing() = testApplication {
        application { moderationModule() }
        operations.forEach { path ->
            val response = call(path, token = userToken)
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
            assertEquals("FORBIDDEN", code(response.bodyAsText()), path)
        }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun theSevenOperationsOnlyEverReadFromTheUpstream() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1)
        upstream.reply(thread, 2)
        val report = upstream.report("publicacion_oposired", thread, 3)
        val feedback = upstream.feedback(4)
        listOf(
            "/api/v1/admin/moderacion/hilos", "/api/v1/admin/moderacion/hilos?modo=reportados", "/api/v1/admin/moderacion/hilos/$thread",
            "/api/v1/admin/moderacion/hilos/$thread/respuestas", "/api/v1/admin/reportes", "/api/v1/admin/reportes/$report",
            "/api/v1/admin/feedback/preguntas", "/api/v1/admin/feedback/preguntas/$feedback",
        ).forEach { assertEquals(HttpStatusCode.OK, call(it).status, it) }
        assertTrue(upstream.sent.isNotEmpty() && upstream.sent.all { it.method() == "GET" }, upstream.requests.toString())
    }

    // ================================================================== D5-20 hilos

    @Test
    fun threadsAreKeysetPagedNewestFirstAndConformToTheContract() = testApplication {
        application { moderationModule() }
        val created = (1..5).map { upstream.thread(it) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val body = ok("D5-20", "/api/v1/admin/moderacion/hilos?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += ids(body)
            cursor = next(body)
            pages++
        } while (cursor != null)
        assertEquals(3, pages)
        assertEquals(created.reversed(), seen)
        // Cada pagina pide limit + 1 y nunca una tabla sin cota.
        assertTrue(upstream.requests.filter { "publicaciones_oposired" in it }.all { "limit=3" in it }, upstream.requests.toString())
    }

    @Test
    fun aThreadRowCarriesTheCanonicalFieldsAndTheOnesTheCurrentClientReads() = testApplication {
        application { moderationModule() }
        val quiet = upstream.thread(1, estado = "oculta", contenido = "x".repeat(400))
        val reported = upstream.thread(2)
        upstream.reply(reported, 3)
        upstream.reply(reported, 4)
        upstream.report("publicacion_oposired", reported, 5)
        upstream.report("publicacion_oposired", reported, 6)
        upstream.report("publicacion_oposired", reported, 7, estado = "resuelto")
        val items = obj(ok("D5-20", "/api/v1/admin/moderacion/hilos"))["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val r = items.getValue(reported)
        assertEquals("true", r["reportado"]!!.jsonPrimitive.content)
        assertEquals(2, r["pendingReports"]!!.jsonPrimitive.int, "only PENDING reports count")
        assertEquals("true", r["tieneReportePendiente"]!!.jsonPrimitive.content, "legacy name kept")
        assertEquals(2, r["respuestas"]!!.jsonPrimitive.int)
        assertEquals("General", r["categoriaNombre"]!!.jsonPrimitive.content)
        assertEquals("Ana Autora", r["autorNombre"]!!.jsonPrimitive.content)
        val q = items.getValue(quiet)
        // Los valores por defecto SALEN por el cable (antes se omitian): false y 0 explicitos.
        assertEquals("false", q["reportado"]!!.jsonPrimitive.content)
        assertEquals(0, q["pendingReports"]!!.jsonPrimitive.int)
        assertEquals(0, q["respuestas"]!!.jsonPrimitive.int)
        assertEquals("false", q["fijada"]!!.jsonPrimitive.content)
        assertEquals("oculta", q["estado"]!!.jsonPrimitive.content, "moderation sees every estado")
        assertEquals(241, q["fragmento"]!!.jsonPrimitive.content.length, "the list carries a bounded fragment, never the full body")
    }

    @Test
    fun reportedModeListsThreadsWithAPendingReportOnTheThreadOrOnAReply() = testApplication {
        application { moderationModule() }
        val clean = upstream.thread(1)
        val direct = upstream.thread(2)
        val viaReply = upstream.thread(3)
        val resolved = upstream.thread(4)
        upstream.report("publicacion_oposired", direct, 5)
        val reply = upstream.reply(viaReply, 6)
        upstream.report("comentario_oposired", reply, 7)
        upstream.report("comentario_oposired", reply, 8)
        upstream.report("publicacion_oposired", resolved, 9, estado = "descartado")
        upstream.report("pregunta", pregunta, 10)

        val body = ok("D5-20", "/api/v1/admin/moderacion/hilos?modo=reportados")
        assertEquals(listOf(viaReply, direct), ids(body))
        assertFalse(clean in ids(body) || resolved in ids(body))
        val byId = obj(body)["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(2, byId.getValue(viaReply)["pendingReports"]!!.jsonPrimitive.int, "reports on a reply count for its thread")
        assertEquals(1, byId.getValue(direct)["pendingReports"]!!.jsonPrimitive.int)
        assertTrue(byId.values.all { it["reportado"]!!.jsonPrimitive.content == "true" })
        // El filtro usa los valores REALES del CHECK de produccion.
        assertTrue(upstream.requests.any { "reportes_contenido" in it && "publicacion_oposired" in it && "comentario_oposired" in it })

        // `reportados=true` (cliente OC actual) es lo mismo; `modo` manda si llegan los dos.
        assertEquals(ids(body), ids(ok("D5-20", "/api/v1/admin/moderacion/hilos?reportados=true")))
        assertEquals(4, ids(ok("D5-20", "/api/v1/admin/moderacion/hilos?modo=recientes&reportados=true")).size)
    }

    @Test
    fun reportedModeWithNoPendingReportIsAnEmptyPageWithoutReadingThreads() = testApplication {
        application { moderationModule() }
        upstream.thread(1)
        val body = ok("D5-20", "/api/v1/admin/moderacion/hilos?modo=reportados")
        assertTrue(ids(body).isEmpty())
        assertNull(next(body))
        assertTrue(upstream.requests.none { "publicaciones_oposired" in it })
    }

    @Test
    fun threadsFilterByCategory() = testApplication {
        application { moderationModule() }
        upstream.thread(1)
        val other = upstream.thread(2, categoriaId = otraCategoria)
        assertEquals(listOf(other), ids(ok("D5-20", "/api/v1/admin/moderacion/hilos?categoriaId=$otraCategoria")))
    }

    @Test
    fun invalidThreadListParametersAre400BeforeAnyRead() = testApplication {
        application { moderationModule() }
        rejected("D5-20", "/api/v1/admin/moderacion/hilos?modo=todos", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("D5-20", "/api/v1/admin/moderacion/hilos?categoriaId=not-a-uuid", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("D5-20", "/api/v1/admin/moderacion/hilos?categoriaId=$categoria%26select%3D*", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("D5-20", "/api/v1/admin/moderacion/hilos?limit=abc", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-20", "/api/v1/admin/moderacion/hilos?limit=0", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-20", "/api/v1/admin/moderacion/hilos?cursor=not-a-cursor", HttpStatusCode.BadRequest, "INVALID_CURSOR")
    }

    @Test
    fun aLimitAboveTheMaximumIsClamped() = testApplication {
        application { moderationModule() }
        upstream.thread(1)
        ok("D5-20", "/api/v1/admin/moderacion/hilos?limit=500")
        assertTrue(upstream.requests.single { "publicaciones_oposired" in it }.endsWith("limit=51"))
    }

    // ================================================================== D5-21 detalle de hilo

    @Test
    fun threadDetailCarriesTheFullBodyAndItsCounts() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1, contenido = "y".repeat(400))
        val reply = upstream.reply(thread, 2)
        upstream.reply(thread, 3)
        upstream.report("publicacion_oposired", thread, 4)
        upstream.report("comentario_oposired", reply, 5)
        upstream.report("comentario_oposired", reply, 6, estado = "resuelto")
        val detail = obj(ok("D5-21", "/api/v1/admin/moderacion/hilos/$thread"))
        assertEquals(400, detail["contenido"]!!.jsonPrimitive.content.length)
        assertEquals(2, detail["respuestas"]!!.jsonPrimitive.int)
        assertEquals(2, detail["reportesAsociados"]!!.jsonPrimitive.int, "pending on the thread + pending on its replies")
        assertEquals("General", detail["categoriaNombre"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnknownThreadIs404AndANonUuidIdIs400() = testApplication {
        application { moderationModule() }
        rejected("D5-21", "/api/v1/admin/moderacion/hilos/$ghost", HttpStatusCode.NotFound, "NOT_FOUND", noUpstream = false)
        rejected("D5-21", "/api/v1/admin/moderacion/hilos/not-a-uuid", HttpStatusCode.BadRequest, "VALIDATION")
    }

    // ================================================================== D5-22 respuestas

    @Test
    fun repliesAreKeysetPagedInReadingOrder() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1)
        val other = upstream.thread(2)
        val replies = (3..7).map { upstream.reply(thread, it, estado = if (it == 4) "oculto" else "publicado") }
        upstream.reply(other, 8)
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val body = ok("D5-22", "/api/v1/admin/moderacion/hilos/$thread/respuestas?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += ids(body)
            cursor = next(body)
        } while (cursor != null)
        assertEquals(replies, seen, "oldest first, only the replies of that thread, every estado")
        assertTrue(ids(ok("D5-22", "/api/v1/admin/moderacion/hilos/$ghost/respuestas")).isEmpty())
    }

    @Test
    fun invalidReplyListParametersAre400BeforeAnyRead() = testApplication {
        application { moderationModule() }
        rejected("D5-22", "/api/v1/admin/moderacion/hilos/not-a-uuid/respuestas", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-22", "/api/v1/admin/moderacion/hilos/$ghost/respuestas?limit=-1", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-22", "/api/v1/admin/moderacion/hilos/$ghost/respuestas?cursor=%3F%3F", HttpStatusCode.BadRequest, "INVALID_CURSOR")
    }

    // ================================================================== D5-25 reportes

    @Test
    fun reportsDefaultToThePendingQueueOldestFirstWithTheLegacyDuplicate() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1)
        val first = upstream.report("publicacion_oposired", thread, 2)
        val second = upstream.report("pregunta", pregunta, 3)
        upstream.report("usuario", author, 4, estado = "resuelto")
        val body = ok("D5-25", "/api/v1/admin/reportes")
        assertEquals(listOf(first, second), ids(body))
        // El cliente OC actual lee `data`: misma pagina, mismo orden.
        assertEquals(obj(body)["items"], obj(body)["data"])
        assertEquals(2, obj(body)["pendientesTotal"]!!.jsonPrimitive.int)
        assertNull(next(body), "a short page is the last page")
        val row = obj(body)["items"]!!.jsonArray.first().jsonObject
        assertEquals("Ana Autora", row["reporterNombre"]!!.jsonPrimitive.content)
        assertEquals("", row["revisadoEn"]!!.jsonPrimitive.content, "not reviewed yet: empty string, always present")
        assertFalse("contentPreview" in row || "acciones" in row, "the list resolves neither the preview nor the actions")
    }

    @Test
    fun reportsFilterByEstadoAndTipoEntidadAndThePendingTotalIgnoresTheFilters() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1)
        upstream.report("publicacion_oposired", thread, 2)
        val onQuestion = upstream.report("pregunta", pregunta, 3)
        val closed = upstream.report("pregunta", pregunta, 4, estado = "resuelto")

        val byType = ok("D5-25", "/api/v1/admin/reportes?tipoEntidad=pregunta")
        assertEquals(listOf(onQuestion), ids(byType))
        assertEquals(2, obj(byType)["pendientesTotal"]!!.jsonPrimitive.int)

        val resolved = ok("D5-25", "/api/v1/admin/reportes?estado=resuelto")
        assertEquals(listOf(closed), ids(resolved))
        assertEquals(2, obj(resolved)["pendientesTotal"]!!.jsonPrimitive.int)

        assertEquals(3, ids(ok("D5-25", "/api/v1/admin/reportes?estado=todos")).size)
    }

    @Test
    fun reportsPageByCursorAndAFullPageAnnouncesANextOne() = testApplication {
        application { moderationModule() }
        val reports = (1..4).map { upstream.report("pregunta", pregunta, it) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val body = ok("D5-25", "/api/v1/admin/reportes?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += ids(body)
            assertEquals(obj(body)["items"], obj(body)["data"])
            cursor = next(body)
            pages++
        } while (cursor != null)
        assertEquals(reports, seen)
        assertEquals(3, pages, "4 rows in pages of 2: the third page is empty and ends the walk")
        // La posicion del cursor viaja codificada (el instante lleva `+`).
        assertTrue(sentRaw().any { "reportes_contenido" in it && "creado_en.gt.2026-10-08T10%3A02%3A00%2B00%3A00" in it }, sentRaw().toString())
    }

    private fun sentRaw(): List<String> = upstream.sent.map { it.uri().rawQuery.orEmpty().let { q -> it.uri().rawPath + "?" + q } }

    @Test
    fun aFailedPendingCountIsNullAndNeverAFakeZero() = testApplication {
        application { moderationModule() }
        val report = upstream.report("pregunta", pregunta, 1)
        upstream.failCounts = true
        val body = ok("D5-25", "/api/v1/admin/reportes")
        assertEquals(listOf(report), ids(body))
        assertFalse("pendientesTotal" in obj(body), "null is omitted on the wire; a 0 would be a lie")
    }

    @Test
    fun invalidReportListParametersAre400BeforeAnyRead() = testApplication {
        application { moderationModule() }
        rejected("D5-25", "/api/v1/admin/reportes?estado=bogus", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-25", "/api/v1/admin/reportes?tipoEntidad=hilo", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("D5-25", "/api/v1/admin/reportes?tipoEntidad=pregunta%26select%3D*", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("D5-25", "/api/v1/admin/reportes?limit=abc", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-25", "/api/v1/admin/reportes?cursor=garbage", HttpStatusCode.BadRequest, "INVALID_CURSOR")
    }

    @Test
    fun aReportCursorIsBoundToItsFiltersAndNeverCombinesWithAnOffset() = testApplication {
        application { moderationModule() }
        (1..3).forEach { upstream.report("pregunta", pregunta, it) }
        val cursor = next(ok("D5-25", "/api/v1/admin/reportes?limit=2"))!!
        rejected("D5-25", "/api/v1/admin/reportes?limit=2&estado=todos&cursor=$cursor", HttpStatusCode.BadRequest, "INVALID_CURSOR")
        rejected("D5-25", "/api/v1/admin/reportes?limit=2&tipoEntidad=pregunta&cursor=$cursor", HttpStatusCode.BadRequest, "INVALID_CURSOR")
        rejected("D5-25", "/api/v1/admin/reportes?limit=2&offset=2&cursor=$cursor", HttpStatusCode.BadRequest, "VALIDATION")
    }

    @Test
    fun theLegacyReportAliasesKeepTheirShape() = testApplication {
        application { moderationModule() }
        val report = upstream.report("pregunta", pregunta, 1)
        listOf("/api/v1/admin/moderacion", "/api/v1/admin/moderacion/reportes").forEach { path ->
            val response = call(path)
            val body = obj(response.bodyAsText())
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals(setOf("data"), body.keys, "$path keeps the legacy wrapper only")
            assertEquals(listOf(report), ids(body.toString(), key = "data"))
        }
    }

    // ================================================================== D5-26 detalle de reporte

    @Test
    fun reportDetailCarriesThePreviewTheActionsAndTheLegacyDuplicate() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1, titulo = "Hilo reportado", estado = "oculta")
        val report = upstream.report("publicacion_oposired", thread, 2)
        val older = upstream.action(report, 3, tipo = "advertir")
        val newer = upstream.action(report, 4, tipo = "ocultar")
        upstream.action(upstream.report("pregunta", pregunta, 5), 6)

        val body = obj(ok("D5-26", "/api/v1/admin/reportes/$report"))
        val data = body["data"]!!.jsonObject
        assertEquals(report, data["id"]!!.jsonPrimitive.content)
        val preview = data["contentPreview"]!!.jsonObject
        assertEquals("hilo", preview["type"]!!.jsonPrimitive.content)
        assertEquals("Hilo reportado", preview["threadTitle"]!!.jsonPrimitive.content)
        assertEquals("oculta", preview["currentState"]!!.jsonPrimitive.content)
        assertEquals("Ana Autora", preview["author"]!!.jsonPrimitive.content)
        val acciones = data["acciones"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(newer, older), acciones.map { it["id"]!!.jsonPrimitive.content }, "newest first, only the actions of this report")
        assertEquals(moderator, acciones.first()["moderadorId"]!!.jsonPrimitive.content)
        assertEquals("ocultar", acciones.first()["tipoAccion"]!!.jsonPrimitive.content)
        assertTrue(upstream.requests.single { "acciones_moderacion" in it }.endsWith("limit=50"), "the action list is bounded in the query")
        // El cliente OC actual lee el reporte DESNUDO: los mismos campos siguen arriba.
        assertEquals(data, JsonObject(body - "data"))
    }

    @Test
    fun aReplyReportPreviewsTheReplyAndAReportWithoutPreviewOrActionsStillConforms() = testApplication {
        application { moderationModule() }
        val thread = upstream.thread(1, titulo = "Hilo padre")
        val reply = upstream.reply(thread, 2)
        val onReply = upstream.report("comentario_oposired", reply, 3)
        val preview = obj(ok("D5-26", "/api/v1/admin/reportes/$onReply"))["data"]!!.jsonObject["contentPreview"]!!.jsonObject
        assertEquals("comentario", preview["type"]!!.jsonPrimitive.content)
        assertEquals("Hilo padre", preview["threadTitle"]!!.jsonPrimitive.content)

        val onQuestion = upstream.report("pregunta", pregunta, 4)
        val data = obj(ok("D5-26", "/api/v1/admin/reportes/$onQuestion"))["data"]!!.jsonObject
        assertFalse("contentPreview" in data, "no preview for an entity type without one")
        assertTrue(data["acciones"]!!.jsonArray.isEmpty())
    }

    @Test
    fun anUnknownReportIs404AndANonUuidIdIs400() = testApplication {
        application { moderationModule() }
        rejected("D5-26", "/api/v1/admin/reportes/$ghost", HttpStatusCode.NotFound, "NOT_FOUND", noUpstream = false)
        rejected("D5-26", "/api/v1/admin/reportes/not-a-uuid", HttpStatusCode.BadRequest, "VALIDATION")
    }

    // ================================================================== D5-28 / D5-29 feedback

    @Test
    fun feedbackIsKeysetPagedNewestFirstWithTheLegacyDuplicate() = testApplication {
        application { moderationModule() }
        val created = (1..5).map { upstream.feedback(it) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val body = ok("D5-28", "/api/v1/admin/feedback/preguntas?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += ids(body)
            assertEquals(obj(body)["items"], obj(body)["data"], "the current client reads data")
            cursor = next(body)
        } while (cursor != null)
        assertEquals(created.reversed(), seen)
        val row = obj(ok("D5-28", "/api/v1/admin/feedback/preguntas?limit=1"))["items"]!!.jsonArray.single().jsonObject
        assertEquals("Ana Autora", row["autorNombre"]!!.jsonPrimitive.content)
        assertEquals("pendiente", row["estado"]!!.jsonPrimitive.content, "the default estado is on the wire, not omitted")
        assertFalse("resolucionNota" in row, "no resolution note column in production yet")
    }

    @Test
    fun feedbackFiltersByEstadoAndTipo() = testApplication {
        application { moderationModule() }
        upstream.feedback(1)
        val resolved = upstream.feedback(2, estado = "resuelto")
        val broken = upstream.feedback(3, tipo = "recurso_roto")
        assertEquals(listOf(resolved), ids(ok("D5-28", "/api/v1/admin/feedback/preguntas?estado=resuelto")))
        assertEquals(listOf(broken), ids(ok("D5-28", "/api/v1/admin/feedback/preguntas?tipoFeedback=recurso_roto")))
        // `tipo` es la grafia que puede enviar el cliente actual; si llegan las dos manda `tipoFeedback`.
        assertEquals(listOf(broken), ids(ok("D5-28", "/api/v1/admin/feedback/preguntas?tipo=recurso_roto")))
        assertEquals(listOf(broken), ids(ok("D5-28", "/api/v1/admin/feedback/preguntas?tipoFeedback=recurso_roto&tipo=otro")))
    }

    @Test
    fun invalidFeedbackListParametersAre400BeforeAnyRead() = testApplication {
        application { moderationModule() }
        rejected("D5-28", "/api/v1/admin/feedback/preguntas?limit=abc", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D5-28", "/api/v1/admin/feedback/preguntas?cursor=not-a-cursor", HttpStatusCode.BadRequest, "INVALID_CURSOR")
    }

    @Test
    fun feedbackDetailCarriesTheQuestionContext() = testApplication {
        application { moderationModule() }
        val feedback = upstream.feedback(1)
        val data = obj(ok("D5-29", "/api/v1/admin/feedback/preguntas/$feedback"))["data"]!!.jsonObject
        assertEquals(feedback, data["id"]!!.jsonPrimitive.content)
        assertEquals("RAF-001", data["pregunta"]!!.jsonObject["codigoPregunta"]!!.jsonPrimitive.content)
        assertEquals("Enunciado", data["pregunta"]!!.jsonObject["titulo"]!!.jsonPrimitive.content)

        val orphan = upstream.feedback(2, preguntaId = ghost)
        assertFalse("pregunta" in obj(ok("D5-29", "/api/v1/admin/feedback/preguntas/$orphan"))["data"]!!.jsonObject, "the question no longer exists")
    }

    @Test
    fun anUnknownFeedbackIs404AndANonUuidIdIs400() = testApplication {
        application { moderationModule() }
        rejected("D5-29", "/api/v1/admin/feedback/preguntas/$ghost", HttpStatusCode.NotFound, "NOT_FOUND", noUpstream = false)
        rejected("D5-29", "/api/v1/admin/feedback/preguntas/not-a-uuid", HttpStatusCode.BadRequest, "VALIDATION")
    }

    // ================================================================== fallo del upstream

    @Test
    fun anUpstreamFailureIs502WithTheCanonicalEnvelope() = testApplication {
        application { moderationModule() }
        upstream.thread(1)
        upstream.failOn = "/rest/v1/"
        listOf(
            "D5-20" to "/api/v1/admin/moderacion/hilos",
            "D5-25" to "/api/v1/admin/reportes",
            "D5-28" to "/api/v1/admin/feedback/preguntas",
        ).forEach { (contract, path) -> rejected(contract, path, HttpStatusCode.BadGateway, "UPSTREAM_ERROR", noUpstream = false) }
    }
}
