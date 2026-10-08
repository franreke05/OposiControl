package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FA11 (D4-01..D4-11) de punta a punta: ruta -> NewsService -> SupabaseNewsGateway -> transporte real, contra un PostgREST /
 * Storage falso con ESTADO (filas de `noticia`, `tipo_noticia`, `especialidades`, objetos del bucket). El cable se valida contra
 * el OpenAPI CONGELADO con [SchemaChecker]. Tambien prueba que las rutas legacy `/content/noticias*` siguen devolviendo su forma
 * sobre el MISMO servicio.
 */
class NewsAdminRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val json = Json { ignoreUnknownKeys = true }

    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val userUid = "22222222-bbbb-4bbb-8bbb-000000000002"
    private val adminToken = "admin-token"
    private val userToken = "user-token"
    private val esp = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    private val espInactiva = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5e"
    private val tipo = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
    private val tipoInactivo = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6e"
    private val ghost = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"
    private val key1 = "6f1c2a3e-1111-4111-8111-aaaaaaaaaaa1"
    private val key2 = "6f1c2a3e-2222-4222-8222-aaaaaaaaaaa2"
    private val key3 = "6f1c2a3e-3333-4333-8333-aaaaaaaaaaa3"
    private val fixedNow = Instant.parse("2026-10-08T12:00:00Z")
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

    // ------------------------------------------------------------------ upstream falso con estado

    private class Audit(val accion: String, val tabla: String, val registroId: String?, val actor: String?)

    private inner class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val noticias = LinkedHashMap<String, MutableMap<String, JsonElement>>()
        val tipos = LinkedHashMap<String, MutableMap<String, JsonElement>>()
        val especialidades = mutableMapOf(esp to true, espInactiva to false)
        val storage = linkedSetOf<String>()
        var tick = 0
        var failOn: String? = null
        var stampPublishedAtLikeTheTrigger = false

        init {
            tipos[tipo] = tipoRow(tipo, "AVISO", "Aviso", "aviso", true, 4)
            tipos[tipoInactivo] = tipoRow(tipoInactivo, "VIEJO", "Viejo", "viejo", false, 9)
            tipos["a0000000-0000-4000-8000-000000000001"] = tipoRow("a0000000-0000-4000-8000-000000000001", "CONVOCATORIA", "Convocatoria", "convocatoria", true, 1)
        }

        fun tipoRow(id: String, codigo: String, nombre: String, slug: String, activo: Boolean, posicion: Int): MutableMap<String, JsonElement> =
            mutableMapOf(
                "id" to JsonPrimitive(id), "codigo" to JsonPrimitive(codigo), "nombre" to JsonPrimitive(nombre), "slug" to JsonPrimitive(slug),
                "descripcion" to JsonNull, "activo" to JsonPrimitive(activo), "posicion" to JsonPrimitive(posicion),
            )

        fun stamp(): String = "2026-10-08T12:00:00.%06d+00:00".format(++tick)

        fun seed(
            titulo: String = "Titulo",
            estado: String = "draft",
            texto: String = "Cuerpo",
            especialidad: String = esp,
            tipoId: String = tipo,
            publicarDesde: String? = null,
            publishedAt: String? = null,
            background: String? = null,
            imagen: String? = null,
            capas: JsonArray = JsonArray(emptyList()),
        ): String {
            val id = UUID.randomUUID().toString()
            noticias[id] = mutableMapOf(
                "id" to JsonPrimitive(id), "titulo" to JsonPrimitive(titulo), "texto" to JsonPrimitive(texto), "capas" to capas,
                "tipo_fondo" to JsonPrimitive("A"), "especialidad_id" to JsonPrimitive(especialidad), "tipo_noticia_id" to JsonPrimitive(tipoId),
                "background_path" to (background?.let { JsonPrimitive(it) } ?: JsonNull), "imagen_path" to (imagen?.let { JsonPrimitive(it) } ?: JsonNull),
                "estado" to JsonPrimitive(estado), "publicar_desde" to (publicarDesde?.let { JsonPrimitive(it) } ?: JsonNull),
                "published_at" to (publishedAt?.let { JsonPrimitive(it) } ?: JsonNull), "creado_en" to JsonPrimitive(stamp()), "actualizado_en" to JsonPrimitive(stamp()),
            )
            return id
        }

        fun text(id: String, field: String): String? = (noticias.getValue(id)[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        val requests: List<String> get() = sent.map { "${it.method()} ${URLDecoder.decode(it.uri().toString(), Charsets.UTF_8)}" }
        val writes: List<String> get() = requests.filter { !it.startsWith("GET") }

        private fun query(uri: String): Map<String, String> =
            uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }

        private fun instantOf(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { OffsetDateTime.parse(value).toInstant() }

        private fun view(row: Map<String, JsonElement>): JsonObject = JsonObject(row)

        fun respond(rawUri: String): V1StubResponse {
            val request = sent.last()
            val method = request.method()
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            failOn?.let { if (uri.contains(it)) return V1StubResponse(500, """{"message":"boom"}""") }
            val path = uri.substringAfter("stub.supabase.test").substringBefore('?')
            val q = query(uri)
            return when {
                path == "/storage/v1/bucket" -> V1StubResponse(200, "{}")
                path.startsWith("/storage/v1/object/news-assets/") -> {
                    val objectPath = path.removePrefix("/storage/v1/object/news-assets/")
                    if (method == "DELETE") storage.remove(objectPath) else storage += objectPath
                    V1StubResponse(200, "{}")
                }
                path == "/rest/v1/noticia" -> noticia(method, q, request)
                path == "/rest/v1/especialidades" -> {
                    val id = q["id"]!!.removePrefix("eq.")
                    V1StubResponse(200, if (especialidades[id] == true) """[{"id":"$id"}]""" else "[]")
                }
                path == "/rest/v1/tipo_noticia" -> tipoNoticia(method, q, request)
                else -> error("peticion inesperada al upstream: $method $uri")
            }
        }

        private fun noticia(method: String, q: Map<String, String>, request: HttpRequest): V1StubResponse {
            var rows = noticias.values.toList()
            q["id"]?.removePrefix("eq.")?.let { id -> rows = rows.filter { (it["id"] as JsonPrimitive).content == id } }
            q["estado"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { (it["estado"] as JsonPrimitive).content == v } }
            q["especialidad_id"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { (it["especialidad_id"] as JsonPrimitive).content == v } }
            q["tipo_noticia_id"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { (it["tipo_noticia_id"] as JsonPrimitive).content == v } }
            q["titulo"]?.removePrefix("ilike.")?.let { pattern ->
                val needle = pattern.trim('*').replace("\\%", "%").replace("\\_", "_").lowercase()
                rows = rows.filter { (it["titulo"] as JsonPrimitive).content.lowercase().contains(needle) }
            }
            q["or"]?.let { or ->
                Regex("actualizado_en\\.lt\\.([^,]+),and\\(actualizado_en\\.eq\\.[^,]+,id\\.lt\\.([^)]+)\\)").find(or)?.let { m ->
                    val at = instantOf(m.groupValues[1])
                    val id = m.groupValues[2]
                    rows = rows.filter {
                        val rowAt = instantOf((it["actualizado_en"] as JsonPrimitive).content)
                        rowAt < at || (rowAt == at && (it["id"] as JsonPrimitive).content < id)
                    }
                }
                Regex("background_path\\.eq\\.([^,]+),imagen_path\\.eq\\.([^)]+)\\)").find(or)?.let { m ->
                    val p = m.groupValues[1]
                    rows = rows.filter { (it["background_path"] as? JsonPrimitive)?.content == p || (it["imagen_path"] as? JsonPrimitive)?.content == p }
                }
            }
            return when (method) {
                "GET" -> {
                    val sorted = if ("actualizado_en.desc" in q["order"].orEmpty()) {
                        rows.sortedWith(compareByDescending<Map<String, JsonElement>> { instantOf((it["actualizado_en"] as JsonPrimitive).content) }.thenByDescending { (it["id"] as JsonPrimitive).content })
                    } else {
                        rows.reversed()
                    }
                    val offset = q["offset"]?.toInt() ?: 0
                    val limited = sorted.drop(offset).take(q["limit"]?.toInt() ?: Int.MAX_VALUE)
                    V1StubResponse(200, JsonArray(limited.map(::view)).toString())
                }
                "POST" -> {
                    val body = Json.parseToJsonElement(readRequestBody(request)).jsonArray.single().jsonObject
                    val id = (body["id"] as? JsonPrimitive)?.content ?: UUID.randomUUID().toString()
                    val existing = noticias[id]
                    val row = existing ?: mutableMapOf(
                        "id" to JsonPrimitive(id), "texto" to JsonPrimitive(""), "capas" to JsonArray(emptyList()), "tipo_fondo" to JsonPrimitive("A"),
                        "background_path" to JsonNull, "imagen_path" to JsonNull, "estado" to JsonPrimitive("draft"), "publicar_desde" to JsonNull,
                        "published_at" to JsonNull, "creado_en" to JsonPrimitive(stamp()),
                    )
                    row.putAll(body)
                    row["actualizado_en"] = JsonPrimitive(stamp())
                    if (stampPublishedAtLikeTheTrigger && (row["estado"] as JsonPrimitive).content == "published" && row["published_at"] is JsonNull) {
                        row["published_at"] = JsonPrimitive("2026-10-08T12:00:00+00:00")
                    }
                    noticias[id] = row
                    V1StubResponse(201, JsonArray(listOf(view(row))).toString())
                }
                "PATCH" -> {
                    val match = rows.firstOrNull { row ->
                        q["actualizado_en"] == null || instantOf((row["actualizado_en"] as JsonPrimitive).content) == instantOf(q["actualizado_en"]!!.removePrefix("eq."))
                    } ?: return V1StubResponse(200, "[]")
                    val body = Json.parseToJsonElement(readRequestBody(request)).jsonObject
                    val row = noticias.getValue((match["id"] as JsonPrimitive).content)
                    row.putAll(body)
                    row["actualizado_en"] = JsonPrimitive(stamp())
                    if (stampPublishedAtLikeTheTrigger && (row["estado"] as JsonPrimitive).content == "published" && row["published_at"] is JsonNull) {
                        row["published_at"] = JsonPrimitive("2026-10-08T12:00:00+00:00")
                    }
                    V1StubResponse(200, JsonArray(listOf(view(row))).toString())
                }
                "DELETE" -> {
                    rows.forEach { noticias.remove((it["id"] as JsonPrimitive).content) }
                    V1StubResponse(200, JsonArray(rows.map(::view)).toString())
                }
                else -> error("metodo inesperado $method")
            }
        }

        private fun tipoNoticia(method: String, q: Map<String, String>, request: HttpRequest): V1StubResponse {
            if (method == "POST") {
                val body = Json.parseToJsonElement(readRequestBody(request)).jsonArray.single().jsonObject
                val codigo = (body["codigo"] as JsonPrimitive).content
                val existing = tipos.values.firstOrNull { (it["codigo"] as JsonPrimitive).content == codigo }
                val row = existing ?: tipoRow(UUID.randomUUID().toString(), codigo, "", "", true, 0).also { tipos[(it["id"] as JsonPrimitive).content] = it }
                row.putAll(body)
                return V1StubResponse(201, JsonArray(listOf(JsonObject(row))).toString())
            }
            var rows = tipos.values.toList()
            q["id"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { (it["id"] as JsonPrimitive).content == v } }
            q["codigo"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { (it["codigo"] as JsonPrimitive).content == v } }
            q["slug"]?.removePrefix("eq.")?.let { v -> rows = rows.filter { (it["slug"] as JsonPrimitive).content == v } }
            if (q["activo"] == "eq.true") rows = rows.filter { (it["activo"] as JsonPrimitive).content == "true" }
            rows = rows.sortedWith(compareBy<Map<String, JsonElement>> { (it["posicion"] as JsonPrimitive).content.toInt() }.thenBy { (it["codigo"] as JsonPrimitive).content })
            val total = rows.size
            val offset = q["offset"]?.toInt() ?: 0
            val page = rows.drop(offset).take(q["limit"]?.toInt() ?: Int.MAX_VALUE)
            val range = if (page.isEmpty()) "*/$total" else "$offset-${offset + page.size - 1}/$total"
            return V1StubResponse(200, JsonArray(page.map { JsonObject(it) }).toString(), contentRange = range)
        }
    }

    private val upstream = Upstream()
    private val audits = CopyOnWriteArrayList<Audit>()

    private fun Application.newsModule() {
        install(ContentNegotiation) { json(testJson) }
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
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        val gateway = SupabaseNewsGateway.forTesting(transport, json)
        val service = NewsService(
            gateway = gateway,
            audit = NewsAuditSink { accion, tabla, id, _, _ -> audits += Audit(accion, tabla, id, AdminAuditActor.current()) },
            clock = { fixedNow },
        )
        val runtime = NewsRuntime(service, InMemoryIdempotencyStore { fixedNow })
        routing {
            authenticate("gateway-auth") {
                install(AdminAuthorizationPlugin) {
                    this.appEnv = "production"
                    this.hasAdminRole = { it == adminUid }
                }
                installAdminAuditActor()
                adminNewsRoutes(runtime)
                route("/content") { newsContentRoutes(gateway, onIndexed = {}, onRemoved = {}, news = service) }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        token: String? = adminToken,
        key: String? = null,
        body: String? = null,
        ifMatch: String? = null,
    ): HttpResponse = request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        key?.let { header("Idempotency-Key", it) }
        ifMatch?.let { header(HttpHeaders.IfMatch, it) }
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    private fun conforms(contractId: String, status: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue(status)
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId $status does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private fun createBody(titulo: String = "Convocatoria 2026", extra: String = "") =
        """{"titulo":"$titulo","texto":"Cuerpo","capas":[{"id":"c1","tipo":"titulo","contenido":"Hola"}],"tipoFondo":"B","especialidadId":"$esp","tipoNoticiaId":"$tipo"$extra}"""

    private fun multipart(bytes: ByteArray, mime: String, fileName: String = "fondo.png", rol: String? = null, partName: String = "file") =
        MultiPartFormDataContent(
            formData {
                append(
                    partName, bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"$partName\"; filename=\"$fileName\"")
                    },
                )
                rol?.let { append("rol", it) }
            },
        )

    private suspend fun HttpClient.upload(content: MultiPartFormDataContent, key: String? = key1, token: String? = adminToken): HttpResponse =
        request("/api/v1/admin/noticias/assets") {
            method = HttpMethod.Post
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            key?.let { header("Idempotency-Key", it) }
            setBody(content)
        }

    private val adminPaths = listOf(
        HttpMethod.Get to "/api/v1/admin/noticias",
        HttpMethod.Post to "/api/v1/admin/noticias",
        HttpMethod.Post to "/api/v1/admin/noticias/assets",
        HttpMethod.Get to "/api/v1/admin/noticias/$ghost",
        HttpMethod.Patch to "/api/v1/admin/noticias/$ghost",
        HttpMethod.Delete to "/api/v1/admin/noticias/$ghost",
        HttpMethod.Post to "/api/v1/admin/noticias/$ghost/publicar",
        HttpMethod.Post to "/api/v1/admin/noticias/$ghost/programar",
        HttpMethod.Post to "/api/v1/admin/noticias/$ghost/archivar",
        HttpMethod.Get to "/api/v1/admin/tipos-noticia",
        HttpMethod.Put to "/api/v1/admin/tipos-noticia/AVISO",
    )

    // ================================================================== 401 / 403

    @Test
    fun everyOperationRejectsAnUnauthenticatedCallerAndTouchesNothing() = testApplication {
        application { newsModule() }
        adminPaths.forEach { (method, path) ->
            val response = client.call(method, path, token = null, key = key1, body = "{}", ifMatch = "\"2026-10-08T12:00:00Z\"")
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$method $path")
        }
        assertTrue(upstream.sent.isEmpty() && audits.isEmpty())
    }

    @Test
    fun everyOperationRejectsANonAdminAccountWith403AndTouchesNothing() = testApplication {
        application { newsModule() }
        adminPaths.forEach { (method, path) ->
            val response = client.call(method, path, token = userToken, key = key1, body = "{}", ifMatch = "\"2026-10-08T12:00:00Z\"")
            assertEquals(HttpStatusCode.Forbidden, response.status, "$method $path")
        }
        assertTrue(upstream.sent.isEmpty() && audits.isEmpty())
    }

    // ================================================================== D4-01 list

    @Test
    fun listIsKeysetPagedSummaryOnlyAndConformsToTheFrozenWire() = testApplication {
        application { newsModule() }
        val ids = (1..5).map { upstream.seed(titulo = "Noticia $it", capas = JsonArray(listOf(buildJsonObject { })), imagen = if (it == 1) "news/$ghost/a.png" else null) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val response = client.call(HttpMethod.Get, "/api/v1/admin/noticias?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            conforms("D4-01", "200", body)
            val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
            items.forEach { assertFalse("texto" in it || "capas" in it, "summary rows carry no texto and no capas") }
            seen += items.map { it["id"]!!.jsonPrimitive.content }
            cursor = (obj(body)["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            pages++
        } while (cursor != null)
        assertEquals(3, pages)
        assertEquals(ids.reversed(), seen, "actualizado_en desc, id desc: last written first, no row repeated or skipped")
        val withImage = obj(client.call(HttpMethod.Get, "/api/v1/admin/noticias?limit=100").bodyAsText())["items"]!!.jsonArray
            .map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == ids[0] }
        assertEquals("https://stub.supabase.test/storage/v1/object/public/news-assets/news/$ghost/a.png", withImage["imagenUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun listFiltersByEstadoEspecialidadTipoAndTitle() = testApplication {
        application { newsModule() }
        upstream.seed(titulo = "Simulacro Andalucia", estado = "published")
        upstream.seed(titulo = "Otro 100%", estado = "draft", tipoId = tipoInactivo)
        upstream.seed(titulo = "Simulacro Madrid", estado = "draft", especialidad = espInactiva)
        suspend fun titles(query: String) = obj(client.call(HttpMethod.Get, "/api/v1/admin/noticias?$query").bodyAsText()).let { it["items"]!!.jsonArray.map { r -> r.jsonObject["titulo"]!!.jsonPrimitive.content } }
        assertEquals(setOf("Simulacro Andalucia"), titles("estado=published").toSet())
        assertEquals(setOf("Simulacro Madrid"), titles("especialidadId=$espInactiva").toSet())
        assertEquals(setOf("Otro 100%"), titles("tipoNoticiaId=$tipoInactivo").toSet())
        assertEquals(setOf("Simulacro Andalucia", "Simulacro Madrid"), titles("q=SIMULACRO").toSet())
        assertTrue(upstream.requests.any { it.contains("titulo=ilike.*SIMULACRO*") || it.contains("titulo=ilike.*simulacro*", ignoreCase = true) })
    }

    @Test
    fun listRejectsBadFiltersCursorsAndLimitsBeforeTouchingTheDatabase() = testApplication {
        application { newsModule() }
        val long = "x".repeat(101)
        listOf("estado=zzz", "especialidadId=nope", "tipoNoticiaId=nope", "q=$long").forEach { query ->
            val response = client.call(HttpMethod.Get, "/api/v1/admin/noticias?$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("INVALID_FILTER", code(response.bodyAsText()), query)
        }
        val badCursor = client.call(HttpMethod.Get, "/api/v1/admin/noticias?cursor=garbage")
        assertEquals("INVALID_CURSOR", code(badCursor.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, badCursor.status)
        val zero = client.call(HttpMethod.Get, "/api/v1/admin/noticias?limit=0")
        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals("VALIDATION", code(zero.bodyAsText()))
        // Un cursor emitido para otros filtros no vale para estos.
        repeat(3) { upstream.seed(estado = "published") }
        val first = obj(client.call(HttpMethod.Get, "/api/v1/admin/noticias?estado=published&limit=1").bodyAsText())
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        val other = client.call(HttpMethod.Get, "/api/v1/admin/noticias?estado=draft&limit=1&cursor=$cursor")
        assertEquals("INVALID_CURSOR", code(other.bodyAsText()))
        assertTrue(upstream.requests.none { it.contains("estado=eq.zzz") || it.contains("especialidad_id=eq.nope") })
        // Un limit por encima del tope se recorta (no es error).
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Get, "/api/v1/admin/noticias?limit=5000").status)
        assertTrue(upstream.requests.any { it.contains("limit=101") })
    }

    // ================================================================== D4-03 create

    @Test
    fun createAlwaysMakesADraftAuditsWithTheJwtActorAndReplaysIdempotently() = testApplication {
        application { newsModule() }
        val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody())
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Created, response.status, body)
        conforms("D4-03", "201", body)
        val created = obj(body)
        assertEquals("draft", created["estado"]!!.jsonPrimitive.content)
        assertEquals("/api/v1/admin/noticias/${created["id"]!!.jsonPrimitive.content}", response.headers[HttpHeaders.Location])
        assertEquals("B", created["tipoFondo"]!!.jsonPrimitive.content)
        assertNull(created["publicarDesde"]); assertNull(created["publishedAt"])
        assertEquals(1, upstream.noticias.size)
        assertEquals(listOf("noticia_creada"), audits.map { it.accion })
        assertEquals(adminUid, audits.single().actor, "the actor is the JWT sub")
        assertEquals(created["id"]!!.jsonPrimitive.content, audits.single().registroId)

        val replay = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody())
        assertEquals(HttpStatusCode.Created, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replayed"])
        assertEquals(body, replay.bodyAsText())
        assertEquals(1, upstream.noticias.size, "a replay creates nothing")
        assertEquals(1, audits.size, "a replay audits nothing")

        val reused = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody(titulo = "Otra"))
        assertEquals(HttpStatusCode.UnprocessableEntity, reused.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(reused.bodyAsText()))
        assertEquals(1, upstream.noticias.size)

        // Sin texto, el DEFAULT de la tabla ('') rige: nunca null en la fila.
        val noText = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key2, body = createBody().replace("\"texto\":\"Cuerpo\",", ""))
        assertEquals(HttpStatusCode.Created, noText.status)
        assertEquals("", obj(noText.bodyAsText())["texto"]!!.jsonPrimitive.content)
    }

    @Test
    fun createNeedsAValidIdempotencyKey() = testApplication {
        application { newsModule() }
        val missing = client.call(HttpMethod.Post, "/api/v1/admin/noticias", body = createBody())
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(missing.bodyAsText()))
        val invalid = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = "not-a-uuid", body = createBody())
        assertEquals("IDEMPOTENCY_KEY_INVALID", code(invalid.bodyAsText()))
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun createValidatesTheBodyAndServerOwnedFieldsAreUnknown() = testApplication {
        application { newsModule() }
        suspend fun issue(body: String): Pair<String, String?> {
            val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = UUID.randomUUID().toString(), body = body)
            val text = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, text)
            return code(text) to obj(text)["details"]?.jsonObject?.get("issues")?.jsonArray?.firstOrNull()?.jsonObject?.let { it["field"]!!.jsonPrimitive.content + ":" + it["issue"]!!.jsonPrimitive.content }
        }
        assertEquals("VALIDATION" to "estado:UNKNOWN_FIELD", issue(createBody(extra = ""","estado":"published"""")))
        assertEquals("VALIDATION" to "publishedAt:UNKNOWN_FIELD", issue(createBody(extra = ""","publishedAt":"2026-01-01T00:00:00Z"""")))
        assertEquals("VALIDATION" to "publicarDesde:UNKNOWN_FIELD", issue(createBody(extra = ""","publicarDesde":"2027-01-01T00:00:00Z"""")))
        assertEquals("VALIDATION" to "titulo:REQUIRED", issue("""{"capas":[],"tipoFondo":"A","especialidadId":"$esp","tipoNoticiaId":"$tipo"}"""))
        assertEquals("VALIDATION" to "titulo:INVALID_FORMAT", issue(createBody(titulo = "   ")))
        assertEquals("VALIDATION" to "tipoFondo:INVALID_FORMAT", issue(createBody().replace("\"B\"", "\"Z\"")))
        assertEquals("VALIDATION" to "especialidadId:INVALID_FORMAT", issue(createBody().replace(esp, "no-uuid")))
        assertEquals("VALIDATION" to "capas:INVALID_FORMAT", issue(createBody().replace(Regex("\"capas\":\\[.*?\\],"), "\"capas\":{},")))
        assertEquals("MALFORMED_BODY" to null, issue("{no json"))
        assertTrue(upstream.sent.isEmpty(), "nothing validated reaches the database")
    }

    @Test
    fun createChecksActiveReferencesAndKnownAssetPaths() = testApplication {
        application { newsModule() }
        val inactiveEsp = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody().replace(esp, espInactiva))
        assertEquals(HttpStatusCode.UnprocessableEntity, inactiveEsp.status)
        assertEquals("INACTIVE_TIPO_OR_ESPECIALIDAD", code(inactiveEsp.bodyAsText()))
        val inactiveTipo = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key2, body = createBody().replace(tipo, tipoInactivo))
        assertEquals("INACTIVE_TIPO_OR_ESPECIALIDAD", code(inactiveTipo.bodyAsText()))
        val unknownTipo = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key3, body = createBody().replace(tipo, ghost))
        assertEquals("INACTIVE_TIPO_OR_ESPECIALIDAD", code(unknownTipo.bodyAsText()))
        val badAsset = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = UUID.randomUUID().toString(), body = createBody(extra = ""","imagenPath":"https://evil.example/x.png""""))
        assertEquals(HttpStatusCode.UnprocessableEntity, badAsset.status)
        assertEquals("ASSET_PATH_UNKNOWN", code(badAsset.bodyAsText()))
        assertTrue(upstream.noticias.isEmpty(), "no row was inserted")
        // Un 422 no consume la clave: se puede reintentar con la misma tras corregir el cuerpo.
        val ok = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody(extra = ""","imagenPath":"news/$ghost/portada.png""""))
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        assertEquals("news/$ghost/portada.png", obj(ok.bodyAsText())["imagenPath"]!!.jsonPrimitive.content)
        assertNotNull(obj(ok.bodyAsText())["imagenUrl"])
    }

    // ================================================================== D4-02 get

    @Test
    fun getReturnsTheFullArticleWithItsVersionAsEtag() = testApplication {
        application { newsModule() }
        val id = upstream.seed(capas = JsonArray(listOf(buildJsonObject { put("contenido", JsonPrimitive("hola")) })), background = "news/$ghost/bg.png")
        val response = client.call(HttpMethod.Get, "/api/v1/admin/noticias/$id")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        conforms("D4-02", "200", body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val article = obj(body)
        assertEquals("\"${article["version"]!!.jsonPrimitive.content}\"", response.headers[HttpHeaders.ETag])
        assertEquals(1, article["capas"]!!.jsonArray.size)
        assertNotNull(article["backgroundUrl"])
        val missing = client.call(HttpMethod.Get, "/api/v1/admin/noticias/$ghost")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("NEWS_NOT_FOUND", code(missing.bodyAsText()))
        val bad = client.call(HttpMethod.Get, "/api/v1/admin/noticias/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("VALIDATION", code(bad.bodyAsText()))
    }

    // ================================================================== D4-04 patch

    @Test
    fun patchUpdatesContentUnderIfMatchAndAuditsAndAStaleVersionIs412() = testApplication {
        application { newsModule() }
        val id = upstream.seed(titulo = "Antes", background = "news/$ghost/bg.png")
        val first = client.call(HttpMethod.Get, "/api/v1/admin/noticias/$id")
        val version = obj(first.bodyAsText())["version"]!!.jsonPrimitive.content
        val patched = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$id", ifMatch = first.headers[HttpHeaders.ETag], body = """{"titulo":"  Despues  ","backgroundPath":null,"texto":"Nuevo"}""")
        val body = patched.bodyAsText()
        assertEquals(HttpStatusCode.OK, patched.status, body)
        conforms("D4-04", "200", body)
        assertEquals("Despues", obj(body)["titulo"]!!.jsonPrimitive.content)
        assertNull(obj(body)["backgroundPath"], "null clears the link")
        assertNotEquals(version, obj(body)["version"]!!.jsonPrimitive.content)
        assertEquals(listOf("noticia_actualizada"), audits.map { it.accion })
        assertEquals(adminUid, audits.single().actor)
        // La version vieja ya no vale.
        val stale = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$id", ifMatch = version, body = """{"titulo":"Otra vez"}""")
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals("PRECONDITION_FAILED", code(stale.bodyAsText()))
        assertEquals("Despues", upstream.text(id, "titulo"))
        // La condicion viaja al UPDATE (compare-and-set), no solo se comprueba en memoria.
        assertTrue(upstream.requests.any { it.startsWith("PATCH") && it.contains("actualizado_en=eq.") })
    }

    @Test
    fun patchRequiresIfMatchRejectsServerOwnedFieldsAndIsOnlyForDraftAndScheduled() = testApplication {
        application { newsModule() }
        val draft = upstream.seed()
        val published = upstream.seed(estado = "published", publishedAt = "2026-10-01T00:00:00+00:00")
        val archived = upstream.seed(estado = "archived")
        suspend fun version(id: String) = obj(client.call(HttpMethod.Get, "/api/v1/admin/noticias/$id").bodyAsText())["version"]!!.jsonPrimitive.content
        val noHeader = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$draft", body = """{"titulo":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, noHeader.status)
        assertEquals("VALIDATION", code(noHeader.bodyAsText()))
        val badHeader = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$draft", ifMatch = "nope", body = """{"titulo":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, badHeader.status)
        val owned = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$draft", ifMatch = version(draft), body = """{"estado":"published"}""")
        assertEquals(HttpStatusCode.BadRequest, owned.status)
        assertEquals("estado", obj(owned.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray[0].jsonObject["field"]!!.jsonPrimitive.content)
        listOf(published, archived).forEach { id ->
            val response = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$id", ifMatch = version(id), body = """{"titulo":"x"}""")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("NEWS_NOT_EDITABLE", code(response.bodyAsText()))
        }
        val missing = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/$ghost", ifMatch = "2026-10-08T12:00:00Z", body = """{"titulo":"x"}""")
        assertEquals("NEWS_NOT_FOUND", code(missing.bodyAsText()))
        val bad = client.call(HttpMethod.Patch, "/api/v1/admin/noticias/zzz", ifMatch = "2026-10-08T12:00:00Z", body = """{"titulo":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(upstream.writes.isEmpty() && audits.isEmpty())
    }

    @Test
    fun patchChecksReferencesAndAssetPathsAndAScheduledArticleStaysEditable() = testApplication {
        application { newsModule() }
        val id = upstream.seed(estado = "scheduled", publicarDesde = "2027-01-01T00:00:00+00:00")
        suspend fun patch(body: String) = client.call(
            HttpMethod.Patch, "/api/v1/admin/noticias/$id",
            ifMatch = obj(client.call(HttpMethod.Get, "/api/v1/admin/noticias/$id").bodyAsText())["version"]!!.jsonPrimitive.content, body = body,
        )
        assertEquals("INACTIVE_TIPO_OR_ESPECIALIDAD", code(patch("""{"tipoNoticiaId":"$tipoInactivo"}""").bodyAsText()))
        assertEquals("INACTIVE_TIPO_OR_ESPECIALIDAD", code(patch("""{"especialidadId":"$espInactiva"}""").bodyAsText()))
        assertEquals("ASSET_PATH_UNKNOWN", code(patch("""{"imagenPath":"../../etc/passwd"}""").bodyAsText()))
        val ok = patch("""{"imagenPath":"news/$ghost/x.webp","tipoFondo":"C"}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("scheduled", obj(ok.bodyAsText())["estado"]!!.jsonPrimitive.content, "content edits never change the state")
        assertEquals("2027-01-01T00:00:00Z", obj(ok.bodyAsText())["publicarDesde"]!!.jsonPrimitive.content)
        // Un parche vacio no escribe ni audita.
        val writesBefore = upstream.writes.size
        val empty = patch("{}")
        assertEquals(HttpStatusCode.OK, empty.status)
        assertEquals(writesBefore, upstream.writes.size)
        assertEquals(1, audits.size)
    }

    // ================================================================== D4-05 / D4-06 / D4-07 state commands

    @Test
    fun publishStampsPublishedAtOnceAndIsIdempotentByState() = testApplication {
        application { newsModule() }
        val id = upstream.seed(estado = "draft")
        val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/publicar")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        conforms("D4-05", "200", body)
        assertEquals("published", obj(body)["estado"]!!.jsonPrimitive.content)
        assertEquals("2026-10-08T12:00:00Z", obj(body)["publishedAt"]!!.jsonPrimitive.content, "server clock, sent explicitly (correct with or without the DB trigger)")
        assertTrue(upstream.writes.any { it.startsWith("PATCH") }, "the stamp travels in the conditional UPDATE")
        val again = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/publicar")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(obj(body)["publishedAt"], obj(again.bodyAsText())["publishedAt"])
        assertEquals(1, upstream.writes.count { it.startsWith("PATCH") }, "an already published article is not written again")
        assertEquals(listOf("noticia_publicada"), audits.map { it.accion })
    }

    @Test
    fun publishErrorsAreExplicit() = testApplication {
        application { newsModule() }
        val archived = upstream.seed(estado = "archived")
        val empty = upstream.seed(texto = "", capas = JsonArray(listOf(buildJsonObject { put("contenido", JsonPrimitive("  ")) })))
        val noTrigger = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$archived/publicar")
        assertEquals(HttpStatusCode.Conflict, noTrigger.status)
        assertEquals("INVALID_TRANSITION", code(noTrigger.bodyAsText()))
        val incomplete = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$empty/publicar")
        assertEquals(HttpStatusCode.UnprocessableEntity, incomplete.status)
        assertEquals("NEWS_INCOMPLETE", code(incomplete.bodyAsText()))
        assertEquals("NEWS_NOT_FOUND", code(client.call(HttpMethod.Post, "/api/v1/admin/noticias/$ghost/publicar").bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.call(HttpMethod.Post, "/api/v1/admin/noticias/x/publicar").status)
        assertTrue(upstream.writes.isEmpty() && audits.isEmpty())
    }

    @Test
    fun publishKeepsAnExistingStampWorksWithTheTriggerAndPublishesAScheduledArticleNow() = testApplication {
        application { newsModule() }
        upstream.stampPublishedAtLikeTheTrigger = true
        val scheduled = upstream.seed(estado = "scheduled", publicarDesde = "2027-01-01T00:00:00+00:00")
        val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$scheduled/publicar")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("published", obj(response.bodyAsText())["estado"]!!.jsonPrimitive.content)
        assertNull(obj(response.bodyAsText())["publicarDesde"], "a future schedule would hide a published article (RLS): publishing clears it")
        val stamped = upstream.seed(estado = "draft", publishedAt = "2026-09-01T00:00:00+00:00")
        val keep = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$stamped/publicar")
        assertEquals("2026-09-01T00:00:00Z", obj(keep.bodyAsText())["publishedAt"]!!.jsonPrimitive.content, "first publication wins")
        // Una publicada sin sello (anterior al trigger) lo recibe una vez.
        val legacyPublished = upstream.seed(estado = "published")
        val backfill = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$legacyPublished/publicar")
        assertEquals("2026-10-08T12:00:00Z", obj(backfill.bodyAsText())["publishedAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun scheduleNeedsAFutureInstantAndIsIdempotent() = testApplication {
        application { newsModule() }
        val id = upstream.seed(estado = "draft")
        val future = "2026-10-09T08:30:00Z"
        val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = """{"publicarDesde":"$future"}""")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        conforms("D4-06", "200", body)
        assertEquals("scheduled", obj(body)["estado"]!!.jsonPrimitive.content)
        assertEquals(future, obj(body)["publicarDesde"]!!.jsonPrimitive.content)
        val patches = upstream.writes.count { it.startsWith("PATCH") }
        val same = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = """{"publicarDesde":"$future"}""")
        assertEquals(HttpStatusCode.OK, same.status)
        assertEquals(patches, upstream.writes.count { it.startsWith("PATCH") }, "the same instant is a 200 no-op")
        val moved = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = """{"publicarDesde":"2026-10-10T00:00:00+02:00"}""")
        assertEquals("2026-10-09T22:00:00Z", obj(moved.bodyAsText())["publicarDesde"]!!.jsonPrimitive.content)
        assertEquals(listOf("noticia_programada", "noticia_programada"), audits.map { it.accion })
        val past = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = """{"publicarDesde":"2026-10-08T11:00:00Z"}""")
        assertEquals(HttpStatusCode.BadRequest, past.status)
        assertEquals("NOT_FUTURE_INSTANT", code(past.bodyAsText()))
        val now = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = """{"publicarDesde":"2026-10-08T12:00:00Z"}""")
        assertEquals("NOT_FUTURE_INSTANT", code(now.bodyAsText()), "strictly in the future")
        listOf("""{}""", """{"publicarDesde":"mañana"}""", """{"publicarDesde":"2027-01-01T00:00:00Z","estado":"published"}""").forEach {
            val bad = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = it)
            assertEquals(HttpStatusCode.BadRequest, bad.status, it)
            assertEquals("VALIDATION", code(bad.bodyAsText()), it)
        }
    }

    @Test
    fun scheduleOfPublishedOrArchivedIsAnInvalidTransitionAndUnknownIdsAre404() = testApplication {
        application { newsModule() }
        val published = upstream.seed(estado = "published")
        val archived = upstream.seed(estado = "archived")
        listOf(published, archived).forEach { id ->
            val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/programar", body = """{"publicarDesde":"2027-01-01T00:00:00Z"}""")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("INVALID_TRANSITION", code(response.bodyAsText()))
        }
        assertEquals("NEWS_NOT_FOUND", code(client.call(HttpMethod.Post, "/api/v1/admin/noticias/$ghost/programar", body = """{"publicarDesde":"2027-01-01T00:00:00Z"}""").bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.call(HttpMethod.Post, "/api/v1/admin/noticias/zzz/programar", body = """{"publicarDesde":"2027-01-01T00:00:00Z"}""").status)
        assertTrue(upstream.writes.isEmpty())
    }

    @Test
    fun archiveKeepsThePublishedStampAndIsIdempotentByState() = testApplication {
        application { newsModule() }
        val id = upstream.seed(estado = "published", publishedAt = "2026-09-01T00:00:00+00:00")
        val response = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/archivar")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        conforms("D4-07", "200", body)
        assertEquals("archived", obj(body)["estado"]!!.jsonPrimitive.content)
        assertEquals("2026-09-01T00:00:00Z", obj(body)["publishedAt"]!!.jsonPrimitive.content)
        val again = client.call(HttpMethod.Post, "/api/v1/admin/noticias/$id/archivar")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(1, upstream.writes.count { it.startsWith("PATCH") })
        assertEquals(listOf("noticia_archivada"), audits.map { it.accion })
        val draft = upstream.seed()
        assertEquals("archived", obj(client.call(HttpMethod.Post, "/api/v1/admin/noticias/$draft/archivar").bodyAsText())["estado"]!!.jsonPrimitive.content)
        assertEquals("NEWS_NOT_FOUND", code(client.call(HttpMethod.Post, "/api/v1/admin/noticias/$ghost/archivar").bodyAsText()))
    }

    // ================================================================== D4-08 delete

    @Test
    fun deleteRemovesTheRowAuditsAndCleansOnlyUnreferencedAssets() = testApplication {
        application { newsModule() }
        val own = "news/$ghost/own.png"
        val shared = "news/$ghost/shared.png"
        upstream.storage += own; upstream.storage += shared
        val id = upstream.seed(background = own, imagen = shared)
        upstream.seed(imagen = shared)
        val response = client.call(HttpMethod.Delete, "/api/v1/admin/noticias/$id")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertFalse(id in upstream.noticias)
        assertEquals(listOf("noticia_eliminada"), audits.map { it.accion })
        assertEquals(adminUid, audits.single().actor)
        assertFalse(own in upstream.storage, "an object no article references is removed after the commit")
        assertTrue(shared in upstream.storage, "an object another article still references is kept")
        val again = client.call(HttpMethod.Delete, "/api/v1/admin/noticias/$id")
        assertEquals(HttpStatusCode.NotFound, again.status)
        assertEquals("NEWS_NOT_FOUND", code(again.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.call(HttpMethod.Delete, "/api/v1/admin/noticias/zzz").status)
    }

    @Test
    fun aFailedAssetCleanupNeverTurnsASuccessfulDeleteIntoAnError() = testApplication {
        application { newsModule() }
        val id = upstream.seed(imagen = "news/$ghost/own.png")
        upstream.failOn = "/storage/v1/object/news-assets/"
        assertEquals(HttpStatusCode.NoContent, client.call(HttpMethod.Delete, "/api/v1/admin/noticias/$id").status)
        assertFalse(id in upstream.noticias)
    }

    // ================================================================== D4-09 assets

    @Test
    fun assetUploadStoresInNewsAssetsAuditsAndReplaysByIdempotencyKey() = testApplication {
        application { newsModule() }
        val response = client.upload(multipart(png, "image/png", rol = "imagen"))
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Created, response.status, body)
        conforms("D4-09", "201", body)
        val asset = obj(body)
        val path = asset["path"]!!.jsonPrimitive.content
        assertTrue(NewsRules.ASSET_PATH.matches(path), path)
        assertEquals(setOf(path), upstream.storage)
        assertEquals("https://stub.supabase.test/storage/v1/object/public/news-assets/$path", asset["publicUrl"]!!.jsonPrimitive.content)
        assertTrue(asset["mime"]!!.jsonPrimitive.content in setOf("image/png", "image/jpeg", "image/webp", "image/gif"))
        assertTrue(asset["bytes"]!!.jsonPrimitive.content.toInt() > 0)
        assertEquals(listOf("noticia_asset_subido"), audits.map { it.accion })
        assertEquals(adminUid, audits.single().actor)

        val replay = client.upload(multipart(png, "image/png", rol = "imagen"))
        assertEquals(HttpStatusCode.Created, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replayed"])
        assertEquals(body, replay.bodyAsText())
        assertEquals(1, upstream.storage.size, "a replay uploads nothing")
        val reused = client.upload(multipart(png + byteArrayOf(0), "image/png", rol = "imagen"))
        assertEquals(HttpStatusCode.UnprocessableEntity, reused.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(reused.bodyAsText()))

        // La ruta devuelta es la que D4-03 acepta.
        val created = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key2, body = createBody(extra = ""","backgroundPath":"$path""""))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    }

    @Test
    fun assetUploadRejectsMissingKeyBadTypeBadBytesAndOversizeBeforeStoring() = testApplication {
        application { newsModule() }
        val noKey = client.upload(multipart(png, "image/png"), key = null)
        assertEquals(HttpStatusCode.BadRequest, noKey.status)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(noKey.bodyAsText()))
        assertEquals("IDEMPOTENCY_KEY_INVALID", code(client.upload(multipart(png, "image/png"), key = "zzz").bodyAsText()))
        val badType = client.upload(multipart(png, "text/plain"), key = key1)
        assertEquals(HttpStatusCode.UnsupportedMediaType, badType.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", code(badType.bodyAsText()))
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.upload(multipart(png, "image/svg+xml"), key = key1).status)
        val liar = client.upload(multipart("<html>not an image</html>".toByteArray(), "image/png"), key = key2)
        assertEquals(HttpStatusCode.BadRequest, liar.status)
        assertEquals("VALIDATION", code(liar.bodyAsText()))
        val noFile = client.upload(multipart(png, "image/png", partName = "otro"), key = key3)
        assertEquals(HttpStatusCode.BadRequest, noFile.status)
        assertEquals("VALIDATION", code(noFile.bodyAsText()))
        val badRol = client.upload(multipart(png, "image/png", rol = "portada"), key = UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.BadRequest, badRol.status)
        val notMultipart = client.call(HttpMethod.Post, "/api/v1/admin/noticias/assets", key = UUID.randomUUID().toString(), body = "{}")
        assertEquals(HttpStatusCode.UnsupportedMediaType, notMultipart.status)
        val big = ByteArray(NEWS_ASSET_MAX_BYTES + 1).also { png.copyInto(it) }
        val tooLarge = client.upload(multipart(big, "image/png"), key = UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
        assertEquals("PAYLOAD_TOO_LARGE", code(tooLarge.bodyAsText()))
        assertTrue(upstream.storage.isEmpty() && upstream.writes.isEmpty() && audits.isEmpty(), "nothing rejected reaches Storage")
    }

    // ================================================================== D4-10 / D4-11 types

    @Test
    fun typesListIsOffsetPagedOrderedByPositionAndConforms() = testApplication {
        application { newsModule() }
        val response = client.call(HttpMethod.Get, "/api/v1/admin/tipos-noticia")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        conforms("D4-10", "200", body)
        assertEquals("private, max-age=60", response.headers[HttpHeaders.CacheControl])
        assertEquals(listOf("CONVOCATORIA", "AVISO", "VIEJO"), obj(body)["items"]!!.jsonArray.map { it.jsonObject["codigo"]!!.jsonPrimitive.content })
        assertEquals(3, obj(body)["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(100, obj(body)["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, obj(body)["offset"]!!.jsonPrimitive.content.toInt())
        val active = obj(client.call(HttpMethod.Get, "/api/v1/admin/tipos-noticia?includeInactive=false").bodyAsText())
        assertEquals(listOf("CONVOCATORIA", "AVISO"), active["items"]!!.jsonArray.map { it.jsonObject["codigo"]!!.jsonPrimitive.content })
        val paged = obj(client.call(HttpMethod.Get, "/api/v1/admin/tipos-noticia?limit=1&offset=1").bodyAsText())
        assertEquals(listOf("AVISO"), paged["items"]!!.jsonArray.map { it.jsonObject["codigo"]!!.jsonPrimitive.content })
        assertEquals(3, paged["total"]!!.jsonPrimitive.content.toInt())
        client.call(HttpMethod.Get, "/api/v1/admin/tipos-noticia?limit=9999&offset=-5")
        assertTrue(upstream.requests.any { it.contains("limit=200") && it.contains("offset=0") }, "limit is clamped and a negative offset is 0")
        assertEquals(HttpStatusCode.BadRequest, client.call(HttpMethod.Get, "/api/v1/admin/tipos-noticia?includeInactive=maybe").status)
        assertEquals(HttpStatusCode.BadRequest, client.call(HttpMethod.Get, "/api/v1/admin/tipos-noticia?limit=0").status)
    }

    @Test
    fun typePutUpsertsByCodigoWith201Then200AuditsAndRejectsSlugConflicts() = testApplication {
        application { newsModule() }
        val body = """{"nombre":"Recurso","slug":"recurso","descripcion":"Material","activo":true,"posicion":3}"""
        val created = client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/RECURSO", body = body)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        conforms("D4-11", "201", created.bodyAsText())
        assertEquals("RECURSO", obj(created.bodyAsText())["codigo"]!!.jsonPrimitive.content)
        val again = client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/RECURSO", body = body)
        assertEquals(HttpStatusCode.OK, again.status)
        conforms("D4-11", "200", again.bodyAsText())
        assertEquals(obj(created.bodyAsText()), obj(again.bodyAsText()), "natural idempotency: the repeat is the same resource")
        assertEquals(4, upstream.tipos.size)
        val retire = client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/RECURSO", body = """{"nombre":"Recurso","slug":"recurso","activo":false,"posicion":3}""")
        assertEquals(false, obj(retire.bodyAsText())["activo"]!!.jsonPrimitive.content.toBoolean())
        assertNull(obj(retire.bodyAsText())["descripcion"], "PUT replaces: an omitted descripcion is cleared")
        assertEquals(listOf("tipo_noticia_guardado", "tipo_noticia_guardado", "tipo_noticia_guardado"), audits.map { it.accion })
        assertEquals(adminUid, audits.first().actor)
        val conflict = client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/OTRO", body = """{"nombre":"Otro","slug":"aviso","activo":true,"posicion":1}""")
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("SLUG_CONFLICT", code(conflict.bodyAsText()))
        assertFalse(upstream.tipos.values.any { (it["codigo"] as JsonPrimitive).content == "OTRO" })
        // Su propio slug no es un conflicto.
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/AVISO", body = """{"nombre":"Aviso","slug":"aviso","activo":true,"posicion":4}""").status)
    }

    @Test
    fun typePutValidatesTheBodyAndTheCodigo() = testApplication {
        application { newsModule() }
        listOf(
            """{"slug":"s","activo":true,"posicion":1}""" to "nombre:REQUIRED",
            """{"nombre":"  ","slug":"s","activo":true,"posicion":1}""" to "nombre:INVALID_FORMAT",
            """{"nombre":"n","slug":"s","activo":"yes","posicion":1}""" to "activo:INVALID_FORMAT",
            """{"nombre":"n","slug":"s","activo":true,"posicion":"1"}""" to "posicion:INVALID_FORMAT",
            """{"nombre":"n","slug":"s","activo":true,"posicion":1,"codigo":"X"}""" to "codigo:UNKNOWN_FIELD",
        ).forEach { (body, expected) ->
            val response = client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/NUEVO", body = body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            val first = obj(response.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray[0].jsonObject
            assertEquals(expected, first["field"]!!.jsonPrimitive.content + ":" + first["issue"]!!.jsonPrimitive.content)
        }
        val badCodigo = client.call(HttpMethod.Put, "/api/v1/admin/tipos-noticia/a%20b", body = """{"nombre":"n","slug":"s","activo":true,"posicion":1}""")
        assertEquals(HttpStatusCode.BadRequest, badCodigo.status)
        assertEquals("VALIDATION", code(badCodigo.bodyAsText()))
        assertTrue(upstream.writes.isEmpty() && audits.isEmpty())
    }

    // ================================================================== fallos del upstream

    @Test
    fun anUpstreamFailureIsAnUpstreamErrorAndAFailedCreateReleasesTheKey() = testApplication {
        application { newsModule() }
        upstream.failOn = "/rest/v1/especialidades"
        val failed = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody())
        assertEquals(HttpStatusCode.BadGateway, failed.status, failed.bodyAsText())
        assertEquals("UPSTREAM_ERROR", code(failed.bodyAsText()))
        assertFalse(failed.bodyAsText().contains("boom"), "the upstream text is never published")
        assertTrue(audits.isEmpty())
        upstream.failOn = null
        val retried = client.call(HttpMethod.Post, "/api/v1/admin/noticias", key = key1, body = createBody())
        assertEquals(HttpStatusCode.Created, retried.status, retried.bodyAsText())
        assertNull(retried.headers["Idempotent-Replayed"], "the failed attempt did not leave a stored response")
        upstream.failOn = "/rest/v1/noticia"
        assertEquals(HttpStatusCode.BadGateway, client.call(HttpMethod.Get, "/api/v1/admin/noticias").status)
        assertEquals(HttpStatusCode.BadGateway, client.call(HttpMethod.Get, "/api/v1/admin/noticias/$ghost").status)
        assertEquals(HttpStatusCode.BadGateway, client.call(HttpMethod.Post, "/api/v1/admin/noticias/$ghost/publicar").status)
    }

    // ================================================================== LEGACY: misma forma, mismo servicio

    @Test
    fun legacyRoutesKeepTheirWireShapeOverTheSameService() = testApplication {
        application { newsModule() }
        val legacyBody = """{"id":"","titulo":"Simulacro","especialidad":"$esp","tipo":"$tipo","fondo":"MINT","fondoPath":"news/$ghost/bg.png","estado":"draft","publicarDesde":"","texto":"Cuerpo","imagenPath":"news/$ghost/img.png","capas":[{"id":"c1","nombre":"Titulo","tipo":"titulo","contenido":"Hola","x":4,"y":5,"width":300,"height":40,"opacity":90,"visible":true,"locked":false,"orden":0,"fontSizeSp":42,"fontWeight":"bold"}]}"""
        val created = client.call(HttpMethod.Post, "/content/noticias", token = adminToken, body = legacyBody)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = obj(created.bodyAsText())
        assertEquals(
            setOf("id", "titulo", "especialidad", "tipo", "fondo", "fondoPath", "texto", "imagenPath", "capas"),
            dto.keys - setOf("estado", "publicarDesde"),
            "the legacy NoticiaDto shape (defaults omitted exactly as the module Json always did)",
        )
        assertEquals("Simulacro", dto["titulo"]!!.jsonPrimitive.content)
        assertEquals("MINT", dto["fondo"]!!.jsonPrimitive.content)
        assertEquals("news/$ghost/bg.png", dto["fondoPath"]!!.jsonPrimitive.content)
        assertEquals(esp, dto["especialidad"]!!.jsonPrimitive.content)
        val layer = dto["capas"]!!.jsonArray.single().jsonObject
        assertEquals(42, layer["fontSizeSp"]!!.jsonPrimitive.content.toInt())
        assertEquals("Deprecation" in created.headers.names(), true)
        val id = dto["id"]!!.jsonPrimitive.content

        val read = client.call(HttpMethod.Get, "/content/noticias/$id", token = adminToken)
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(dto, obj(read.bodyAsText()), "GET by id returns the same object")
        val list = client.call(HttpMethod.Get, "/content/noticias", token = adminToken)
        assertEquals(listOf(dto), json.parseToJsonElement(list.bodyAsText()).jsonArray.map { it.jsonObject })
        val put = client.call(HttpMethod.Put, "/content/noticias", token = adminToken, body = legacyBody.replace("\"id\":\"\"", "\"id\":\"$id\"").replace("\"estado\":\"draft\"", "\"estado\":\"published\""))
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals("published", obj(put.bodyAsText())["estado"]!!.jsonPrimitive.content)
        assertEquals(1, upstream.noticias.size, "PUT with the same id updates, never duplicates")
        assertEquals(listOf("noticia_guardada_legacy", "noticia_guardada_legacy"), audits.map { it.accion }, "legacy writes are audited by the shared service")

        val deleted = client.call(HttpMethod.Delete, "/content/noticias/$id", token = adminToken)
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())
        assertTrue(upstream.noticias.isEmpty())
        assertEquals("noticia_no_encontrada", obj(client.call(HttpMethod.Get, "/content/noticias/$id", token = adminToken).bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun legacyUploadKeepsItsExactShapeAndThePathRulesOfTheSharedUpload() = testApplication {
        application { newsModule() }
        val response = client.request("/content/noticias/assets") {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            setBody(multipart(png, "image/png", fileName = "Fondo Rojo.PNG"))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val asset = obj(response.bodyAsText())
        assertEquals(setOf("path", "publicUrl", "mimeType"), asset.keys, "legacy shape: no `bytes`/`mime`")
        assertTrue(asset["path"]!!.jsonPrimitive.content.matches(Regex("^news/[0-9a-f-]{36}/[a-z0-9._-]+$")), asset["path"]!!.jsonPrimitive.content)
        assertTrue(asset["publicUrl"]!!.jsonPrimitive.content.startsWith("https://stub.supabase.test/storage/v1/object/public/news-assets/news/"))
        assertEquals(1, upstream.storage.size)
        val notImage = client.request("/content/noticias/assets") {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            setBody(multipart(png, "application/pdf"))
        }
        assertEquals(HttpStatusCode.BadRequest, notImage.status, "legacy: any non-image is the same 400 as before")
    }

    @Test
    fun legacyAppliesTheSharedStateRulesAndKeepsTheOldValidationMessages() = testApplication {
        application { newsModule() }
        suspend fun put(body: String) = client.call(HttpMethod.Put, "/content/noticias", token = adminToken, body = body)
        val base = """"especialidad":"$esp","tipo":"$tipo","fondo":"SKY","publicarDesde":"""
        val noTitle = put("""{"titulo":" ",$base"","texto":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, noTitle.status)
        assertTrue(noTitle.bodyAsText().contains("titulo"))
        val noContent = put("""{"titulo":"T",$base"","texto":"","capas":[]}""")
        assertEquals(HttpStatusCode.BadRequest, noContent.status)
        assertTrue(noContent.bodyAsText().contains("texto o capas"))
        val scheduledWithoutDate = put("""{"titulo":"T",$base"","texto":"x","estado":"scheduled"}""")
        assertEquals(HttpStatusCode.BadRequest, scheduledWithoutDate.status, "scheduled requires publicarDesde (shared rule, was a DB check violation)")
        val badState = put("""{"titulo":"T",$base"","texto":"x","estado":"zzz"}""")
        assertEquals(HttpStatusCode.BadRequest, badState.status)
        assertTrue(upstream.writes.isEmpty())
        val scheduled = put("""{"titulo":"T",$base"2027-01-01T00:00:00Z","texto":"x","estado":"scheduled"}""")
        assertEquals(HttpStatusCode.OK, scheduled.status, scheduled.bodyAsText())
    }

    @Test
    fun legacyRoutesAdvertiseTheirCanonicalSuccessorsWithoutBeingRetired() = testApplication {
        application { newsModule() }
        val id = upstream.seed()
        val list = client.call(HttpMethod.Get, "/content/noticias", token = adminToken)
        assertEquals("@${LegacyEndpoints.SLICE_0_DEPRECATED_AT.epochSecond}", list.headers["Deprecation"])
        assertEquals("</api/v1/admin/noticias>; rel=\"successor-version\"", list.headers[HttpHeaders.Link])
        val deleted = client.call(HttpMethod.Delete, "/content/noticias/$id", token = adminToken)
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("</api/v1/admin/noticias/$id>; rel=\"successor-version\"", deleted.headers[HttpHeaders.Link])
        assertEquals(
            mapOf(
                "EP-111" to "/api/v1/admin/noticias", "EP-199" to "/api/v1/admin/noticias", "EP-229" to "/api/v1/admin/noticias/{noticiaId}",
                "EP-015" to "/api/v1/admin/noticias/{noticiaId}", "EP-200" to "/api/v1/admin/noticias/assets",
                "EP-025" to "/api/v1/admin/tipos-noticia", "EP-145" to "/api/v1/admin/tipos-noticia/{codigo}",
            ),
            LegacyEndpoints.SLICE_0.filter { it.endpointId != "EP-046" && it.endpointId != "EP-144" }.associate { it.endpointId to it.successorPath },
        )
    }
}
