package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F25 / FA49: D4-18 (`GET /api/v1/app/noticias`) y D4-27 (`GET /api/v1/app/noticias/{noticiaId}`) de punta a punta (ruta ->
 * servicio -> pasarela -> transporte real) contra un upstream de pruebas con la semantica de PostgREST que usa la pasarela.
 * El cable se valida contra el OpenAPI. Solo lecturas: todo lo que sale es GET (las imagenes son URLs publicas, no se firma nada).
 */
class AppNewsRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val esp = "eeeeeeee-eeee-4eee-8eee-000000000001"
    private val otraEsp = "eeeeeeee-eeee-4eee-8eee-000000000002"

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) },
    )

    private fun uuid(n: Int) = "dddddddd-dddd-4ddd-8ddd-%012d".format(n)
    private fun asset(n: Int) = "news/${uuid(n)}/img.webp"

    private data class Row(
        val id: String,
        val estado: String,
        val publishedAt: String? = null,
        val publicarDesde: String? = null,
        val especialidad: String,
        val texto: String = "",
        val background: String? = null,
        val imagen: String? = null,
        val capas: String = "[]",
        val tipoActivo: Boolean = true,
    ) {
        fun json(): String =
            """{"id":"$id","titulo":"T $id","capas":$capas,"tipo_fondo":"B","background_path":${background.q()},"imagen_path":${imagen.q()},""" +
                """"estado":"$estado","publicar_desde":${publicarDesde.q()},"published_at":${publishedAt.q()},"tipo_noticia":{"activo":$tipoActivo}""" +
                ",\"texto\":" + kotlinx.serialization.json.JsonPrimitive(texto).toString() + "}"

        private fun String?.q() = this?.let { "\"$it\"" } ?: "null"

    }

    private class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val urls: List<String> get() = sent.map { URLDecoder.decode(it.uri().toString(), Charsets.UTF_8) }
        var specialty: String? = null
        var rows: List<Row> = emptyList()

        fun respond(rawUri: String): V1StubResponse {
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            val body = when {
                uri.contains("/rest/v1/perfiles_usuario") -> specialty?.let { """[{"especialidad_principal_id":"$it"}]""" } ?: "[]"
                uri.contains("/rest/v1/noticia") -> noticias(uri)
                else -> error("peticion inesperada al upstream: $uri")
            }
            return V1StubResponse(200, body)
        }

        private fun t(s: String) = OffsetDateTime.parse(s).toInstant()

        private fun noticias(uri: String): String {
            val q = uri.substringAfter('?')
            var out = rows.asSequence()
            assertTrue(q.contains("select=") && q.contains("tipo_noticia!inner(activo)") && q.contains("tipo_noticia.activo=is.true"), q)
            out = out.filter { it.tipoActivo }
            Regex("especialidad_id=eq\\.([0-9a-f-]+)").find(q)?.let { m -> out = out.filter { it.especialidad == m.groupValues[1] } }
            Regex("[?&]id=eq\\.([0-9a-f-]+)").find("&$q")?.let { m -> return out.filter { it.id == m.groupValues[1] }.take(1).joinToString(",", "[", "]") { it.json() } }
            val estado = Regex("estado=eq\\.(\\w+)").find(q)!!.groupValues[1]
            out = out.filter { it.estado == estado }
            val column = if (estado == "published") "published_at" else "publicar_desde"
            fun key(r: Row) = t((if (estado == "published") r.publishedAt else r.publicarDesde)!!)
            if (estado == "published") {
                assertTrue(q.contains("published_at=not.is.null"))
                val cut = t(Regex("publicar_desde\\.lte\\.([^,)]+)").find(q)!!.groupValues[1])
                out = out.filter { it.publishedAt != null && (it.publicarDesde == null || !t(it.publicarDesde).isAfter(cut)) }
            } else {
                val cut = t(Regex("publicar_desde=lte\\.([^&]+)").find(q)!!.groupValues[1])
                out = out.filter { !t(it.publicarDesde!!).isAfter(cut) }
            }
            Regex("or\\($column\\.lt\\.([^,]+),and\\($column\\.eq\\.([^,]+),id\\.lt\\.([^)]+)\\)\\)").find(q)?.let { m ->
                val cut = t(m.groupValues[1])
                out = out.filter { key(it).isBefore(cut) || (key(it) == cut && it.id < m.groupValues[3]) }
            }
            assertTrue(q.contains("order=$column.desc,id.desc"), q)
            val limit = Regex("&limit=(\\d+)").find(q)!!.groupValues[1].toInt()
            return out.sortedWith(compareByDescending<Row> { key(it) }.thenByDescending { it.id }).take(limit).joinToString(",", "[", "]") { it.json() }
        }
    }

    private val upstream = Upstream()

    private fun Application.newsModule() {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        val service = AppNewsService(
            AppNewsGateway(upstream = { path -> transport.get(path) }),
            publicUrl = { path -> transport.storagePublicUrl(NEWS_ASSETS_BUCKET, path) },
            clock = { now },
        )
        routing { appNewsRoutes(AppNewsRuntime(resolver, service)) }
    }

    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse =
        get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content
    private fun ids(body: String) = obj(body)["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun assertConforms(contractId: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue("200")
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId does not conform to the OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private fun assertReadOnly() {
        assertTrue(upstream.sent.isNotEmpty())
        upstream.sent.forEach { req ->
            assertEquals("GET", req.method(), req.uri().toString())
        }
        upstream.urls.filter { it.contains("/rest/v1/") }.forEach {
            assertFalse(it.contains("select=*"), "columnas explicitas: $it")
        }
    }

    private val capas = """[
        {"id":"c1","nombre":"Titulo","tipo":"titulo","contenido":"Escucha. Aprende. Apruebe.","x":64,"y":40,"width":800,"height":120,"opacity":100,"fontFamily":"sans","textColor":"#FFFFFF","visible":true,"locked":false,"orden":1,"rotationDegrees":0,"fontSizeSp":0,"fontWeight":""},
        {"id":"c0","nombre":"Foto","tipo":"imagen","contenido":"https://x.supabase.co/storage/v1/object/public/news-assets/${asset(3)}","x":900,"y":0,"width":532,"height":592,"opacity":80,"fontFamily":"sans","textColor":"","visible":true,"locked":false,"orden":0,"rotationDegrees":0,"fontSizeSp":0,"fontWeight":""},
        {"id":"c2","nombre":"Oculta","tipo":"texto","contenido":"no","x":0,"y":0,"width":100,"height":40,"opacity":100,"fontFamily":"sans","textColor":"","visible":false,"locked":false,"orden":2,"rotationDegrees":0,"fontSizeSp":0,"fontWeight":""},
        {"id":"c3","nombre":"Ajena","tipo":"imagen","contenido":"https://evil.example/x.png","x":0,"y":0,"width":100,"height":40,"opacity":100,"fontFamily":"sans","textColor":"bad","visible":true,"locked":false,"orden":3,"rotationDegrees":0,"fontSizeSp":0,"fontWeight":""}
    ]"""

    private fun seed() {
        upstream.specialty = esp
        upstream.rows = listOf(
            Row(uuid(1), "published", publishedAt = "2026-11-10T09:00:00+00:00", especialidad = esp, texto = "\n  Primer parrafo.\nSegundo.",
                background = asset(1), imagen = asset(2), capas = capas),
            Row(uuid(2), "scheduled", publicarDesde = "2026-11-11T08:00:00+00:00", especialidad = esp),
            Row(uuid(3), "published", publishedAt = "2026-11-09T09:00:00+00:00", publicarDesde = "2026-11-08T00:00:00+00:00", especialidad = esp,
                texto = "x".repeat(400)),
            Row(uuid(4), "scheduled", publicarDesde = "2026-11-12T08:00:00+00:00", especialidad = esp), // futura
            Row(uuid(5), "draft", especialidad = esp),
            Row(uuid(6), "archived", publishedAt = "2026-11-01T00:00:00+00:00", especialidad = esp),
            Row(uuid(7), "published", publishedAt = "2026-11-10T10:00:00+00:00", especialidad = otraEsp),
            Row(uuid(8), "published", publishedAt = "2026-11-10T09:30:00+00:00", publicarDesde = "2026-11-20T00:00:00+00:00", especialidad = esp),
            Row(uuid(9), "published", publishedAt = "2026-11-10T11:00:00+00:00", especialidad = esp, tipoActivo = false), // tipo desactivado
            Row(uuid(10), "scheduled", publicarDesde = "2026-11-10T12:00:00+00:00", especialidad = esp, tipoActivo = false),
        )
    }

    @Test
    fun listMergesVisibleNewsNewestFirstWithPublicImagesAndConforms() = testApplication {
        application { newsModule() }
        seed()
        val response = client.getAs("/api/v1/app/noticias")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D4-18", body)
        assertEquals(listOf(uuid(2), uuid(1), uuid(3)), ids(body))
        assertNull(obj(body)["nextCursor"])

        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("2026-11-11T08:00:00Z", items[0]["fecha"]!!.jsonPrimitive.content, "programada vencida = publicada en publicar_desde")
        val first = items[1]
        assertEquals("2026-11-10T09:00:00Z", first["fecha"]!!.jsonPrimitive.content)
        assertEquals("Primer parrafo.", first["resumen"]!!.jsonPrimitive.content)
        assertEquals("B", first["tipoFondo"]!!.jsonPrimitive.content)
        assertEquals("https://stub.supabase.test/storage/v1/object/public/news-assets/${asset(1)}", first["backgroundUrl"]!!.jsonPrimitive.content)
        assertEquals("https://stub.supabase.test/storage/v1/object/public/news-assets/${asset(2)}", first["imagenUrl"]!!.jsonPrimitive.content)
        assertNull(first["texto"], "el cuerpo solo viaja en el detalle")
        val layers = first["capas"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("imagen", "titulo", "imagen"), layers.map { it["tipo"]!!.jsonPrimitive.content }, "ordenadas, sin la oculta")
        assertEquals("https://stub.supabase.test/storage/v1/object/public/news-assets/${asset(3)}", layers[0]["contenido"]!!.jsonPrimitive.content)
        assertEquals("Escucha. Aprende. Apruebe.", layers[1]["contenido"]!!.jsonPrimitive.content)
        assertEquals("", layers[2]["contenido"]!!.jsonPrimitive.content, "una URL ajena nunca se reenvia")
        assertEquals("", layers[2]["textColor"]!!.jsonPrimitive.content)
        assertEquals(160, items[2]["resumen"]!!.jsonPrimitive.content.length)
        assertNull(items[0]["resumen"])

        upstream.urls.filter { it.contains("/rest/v1/noticia") }.forEach { assertTrue(it.contains("especialidad_id=eq.$esp"), it) }
        upstream.urls.filter { it.contains("/rest/v1/perfiles_usuario") }.forEach { assertTrue(it.contains("usuario_id=eq.$TEST_UID"), it) }
        upstream.urls.forEach { assertFalse(it.contains("evil.example"), "una URL ajena no se firma: $it") }
        assertReadOnly()
    }

    @Test
    fun cursorPagesWithoutGapsOrRepeats() = testApplication {
        application { newsModule() }
        seed()
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val response = client.getAs("/api/v1/app/noticias?limit=1" + (cursor?.let { "&cursor=$it" } ?: ""))
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            seen += ids(body)
            cursor = obj(body)["nextCursor"]?.jsonPrimitive?.content
        } while (cursor != null && seen.size < 10)
        assertEquals(listOf(uuid(2), uuid(1), uuid(3)), seen)

        val bad = client.getAs("/api/v1/app/noticias?cursor=nope")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("INVALID_CURSOR", code(bad.bodyAsText()))
    }

    @Test
    fun userWithoutSpecialtySeesTheVisibleNewsOfEverySpecialty() = testApplication {
        application { newsModule() }
        seed()
        upstream.specialty = null
        val response = client.getAs("/api/v1/app/noticias")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals(listOf(uuid(2), uuid(7), uuid(1), uuid(3)), ids(body), "todas las especialidades, misma regla de visibilidad")
        assertConforms("D4-18", body)
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/noticias/${uuid(7)}").status)
        listOf(uuid(4), uuid(8), uuid(9)).forEach { assertEquals(HttpStatusCode.NotFound, client.getAs("/api/v1/app/noticias/$it").status, it) }
        upstream.urls.filter { it.contains("/rest/v1/noticia") }.forEach { assertFalse(it.contains("especialidad_id="), it) }
        assertReadOnly()
    }

    @Test
    fun detailReturnsTheBodyOnlyForVisibleNewsOfTheSpecialty() = testApplication {
        application { newsModule() }
        seed()
        val response = client.getAs("/api/v1/app/noticias/${uuid(1)}")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D4-27", body)
        assertEquals("\n  Primer parrafo.\nSegundo.", obj(body)["texto"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/noticias/${uuid(2)}").status, "programada ya vencida")

        listOf(uuid(4), uuid(5), uuid(6), uuid(7), uuid(8), uuid(9), uuid(10), uuid(99)).forEach { id ->
            val r = client.getAs("/api/v1/app/noticias/$id")
            assertEquals(HttpStatusCode.NotFound, r.status, id)
            assertEquals("NEWS_NOT_FOUND", code(r.bodyAsText()))
        }
        val bad = client.getAs("/api/v1/app/noticias/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertReadOnly()
    }

    @Test
    fun withoutCredentialsItIs401AndNothingIsRead() = testApplication {
        application { newsModule() }
        seed()
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/noticias", token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/noticias/${uuid(1)}", token = null).status)
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun resumenIsTheFirstParagraphCut() {
        assertNull(AppNewsService.resumenOf(null))
        assertNull(AppNewsService.resumenOf(" \n "))
        assertEquals("Hola", AppNewsService.resumenOf("\nHola\nadios"))
        val cut = AppNewsService.resumenOf("a".repeat(500))!!
        assertEquals(APP_NEWS_RESUMEN_MAX, cut.length)
        assertTrue(cut.endsWith("…"))
    }
}
