package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.model.NewsLayer
import com.oposicontrol.admin.domain.model.NewsListFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El cliente canonico de noticias contra un MockEngine: lo que se ENVIA (cabeceras, cuerpo, filtros) y como se LEE
 * la ficha. Ningun byte sale de la maquina.
 */
class KtorNewsRepositoryImplTest {
    private fun appConfig() = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "test-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private fun session(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    private class Recorded(val request: HttpRequestData, val body: String)

    private fun repository(
        requests: MutableList<Recorded>,
        reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorNewsRepositoryImpl {
        val client = HttpClient(MockEngine { request ->
            val body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> String(content.bytes())
                else -> ""
            }
            requests += Recorded(request, body)
            reply(request)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorNewsRepositoryImpl(client, appConfig(), session(), PlatformFileReader(AppPlatformContext()), NetworkResultAdapter(NoOpLogger))
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(content = ByteReadChannel(body), status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    private val article = NewsArticle(
        id = "", title = "  Convocatoria TCAE ", specialty = ESP, type = TIPO, backgroundType = "MINT",
        backgroundPath = null, status = "draft", publishFrom = "", text = "Cuerpo", imagePath = null,
        layers = listOf(
            NewsLayer("l2", "Texto", "texto", "Hola", 10, 20, 300, 40, 90, "serif", "#FFFFFF", true, sortOrder = 5, fontSizeSp = 18, fontWeight = "bold"),
            NewsLayer("l1", "Titulo", "titulo", "Convocatoria", 48, 42, 460, 92, 100, "sans", "", true, sortOrder = 1),
        ),
    )

    private val adminWire = """
        {"id":"$ID","titulo":"Convocatoria TCAE","texto":null,"capas":[
          {"id":"l1","nombre":"Titulo","tipo":"titulo","contenido":"Convocatoria","x":48,"y":42,"width":460,"height":92,"opacity":100,"visible":true,"orden":1},
          {"id":"rota","nombre":7,"x":"no"},
          {"id":"l0","nombre":"Fondo","tipo":"imagen","contenido":"https://example.supabase.co/storage/v1/object/public/news-assets/news/x/a.png","x":0,"y":0,"width":800,"height":500,"opacity":100,"visible":true,"orden":0,"rotationDegrees":-90}
        ],"tipoFondo":"C","especialidadId":"$ESP","tipoNoticiaId":"$TIPO",
         "backgroundPath":"https://example.supabase.co/storage/v1/object/public/news-assets/news/legacy/f.png",
         "backgroundUrl":"https://example.supabase.co/storage/v1/object/public/news-assets/https://garbage",
         "imagenPath":"news/$ID/i.png","imagenUrl":"https://example.supabase.co/storage/v1/object/public/news-assets/news/$ID/i.png",
         "estado":"scheduled","publicarDesde":"2026-12-01T09:00:00Z","publishedAt":null,"version":"2026-10-08T12:00:00.000001Z"}
    """.trimIndent()

    @Test
    fun createSendsIdempotencyKeyAndTheCreateBodyWithoutNullPaths() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.Created, adminWire) }

        val result = repository.createNews(article, KEY)

        assertIs<AppResult.Success<NewsArticle>>(result)
        val sent = requests.single()
        assertEquals("POST", sent.request.method.value)
        assertEquals("/api/v1/admin/noticias", sent.request.url.encodedPath)
        assertEquals(KEY, sent.request.headers["Idempotency-Key"])
        assertEquals("Bearer test-token", sent.request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("Convocatoria TCAE", body["titulo"]!!.jsonPrimitive.content)
        assertEquals("B", body["tipoFondo"]!!.jsonPrimitive.content)
        assertEquals(ESP, body["especialidadId"]!!.jsonPrimitive.content)
        assertFalse("backgroundPath" in body, "sin fondo no viaja la clave: el contrato no admite null al crear")
        assertFalse("imagenPath" in body)
        // Las capas van ordenadas y con `orden` reindexado desde 0, con los nombres de la columna jsonb.
        val capas = body["capas"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("titulo", "texto"), capas.map { it["tipo"]!!.jsonPrimitive.content })
        assertEquals(listOf(0, 1), capas.map { it["orden"]!!.jsonPrimitive.content.toInt() })
        assertEquals("Hola", capas[1]["contenido"]!!.jsonPrimitive.content)
        assertEquals(18, capas[1]["fontSizeSp"]!!.jsonPrimitive.content.toInt())
        assertEquals("bold", capas[1]["fontWeight"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateSendsIfMatchAndExplicitNullsToClearAssets() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.OK, adminWire) }

        val result = repository.updateNews(article.copy(id = ID, version = VERSION, backgroundPath = "", imagePath = "news/$ID/i.png"))

        assertIs<AppResult.Success<NewsArticle>>(result)
        val sent = requests.single()
        assertEquals("PATCH", sent.request.method.value)
        assertEquals("/api/v1/admin/noticias/$ID", sent.request.url.encodedPath)
        assertEquals(VERSION, sent.request.headers[HttpHeaders.IfMatch])
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(JsonNull, body["backgroundPath"], "vaciar el fondo exige un null EXPLICITO (DEBT-089)")
        assertEquals(JsonPrimitive("news/$ID/i.png"), body["imagenPath"])
        assertEquals("Cuerpo", body["texto"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateWithoutVersionIsRejectedBeforeSending() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.OK, adminWire) }

        val result = repository.updateNews(article.copy(id = ID, version = ""))

        assertIs<AppResult.Failure>(result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun getNewsMapsTheWireTolerantly() = runTest {
        val repository = repository(mutableListOf()) { json(HttpStatusCode.OK, adminWire) }

        val result = repository.getNews(ID)

        assertIs<AppResult.Success<NewsArticle>>(result)
        val news = result.value
        assertEquals("GOLD", news.backgroundType)
        assertEquals("", news.text, "texto null llega como vacio")
        assertEquals("2026-12-01T09:00:00Z", news.publishFrom)
        assertEquals(VERSION, news.version)
        assertNull(news.publishedAt)
        // La capa mal formada se omite; las otras dos sobreviven ordenadas por `orden` y con la rotacion normalizada.
        assertEquals(listOf("l0", "l1"), news.layers.map { it.id })
        assertEquals(1, news.droppedLayerCount, "la capa omitida se cuenta para que el editor avise")
        assertEquals(270, news.layers[0].rotationDegrees)
        // Un background_path que ya es una URL (filas del panel anterior) se usa tal cual; el imagenUrl del servidor manda.
        assertEquals("https://example.supabase.co/storage/v1/object/public/news-assets/news/legacy/f.png", news.backgroundUrl)
        assertEquals("https://example.supabase.co/storage/v1/object/public/news-assets/news/$ID/i.png", news.imageUrl)
    }

    @Test
    fun refreshAndLoadMoreSendTheFiltersAndKeepTheObservedList() = runTest {
        val requests = mutableListOf<Recorded>()
        val page1 = """{"items":[{"id":"a","titulo":"A","estado":"draft","especialidadId":"$ESP","tipoNoticiaId":"$TIPO","actualizadoEn":"2026-10-08T12:00:00Z"}],"nextCursor":"c2"}"""
        val page2 = """{"items":[{"id":"a","titulo":"A","estado":"draft","especialidadId":"$ESP","tipoNoticiaId":"$TIPO","actualizadoEn":"2026-10-08T12:00:00Z"},{"id":"b","titulo":"B","estado":"published","especialidadId":"$ESP","tipoNoticiaId":"$TIPO","publishedAt":"2026-10-01T10:00:00Z","actualizadoEn":"2026-10-07T12:00:00Z"}],"nextCursor":null}"""
        val repository = repository(requests) { request ->
            json(HttpStatusCode.OK, if (request.url.parameters["cursor"] == null) page1 else page2)
        }

        val first = repository.refreshNews(NewsListFilter(status = "draft", query = "  convocatoria "))
        assertIs<AppResult.Success<*>>(first)
        val more = repository.loadMoreNews(NewsListFilter(status = "draft", query = "convocatoria"), "c2")
        assertIs<AppResult.Success<*>>(more)

        val q1 = requests[0].request.url.parameters
        assertEquals("100", q1["limit"])
        assertEquals("draft", q1["estado"])
        assertEquals("convocatoria", q1["q"])
        assertNull(q1["especialidadId"])
        assertEquals("c2", requests[1].request.url.parameters["cursor"])
        val observed = repository.observeNews().first()
        assertEquals(listOf("a", "b"), observed.map { it.id }, "la segunda pagina se anade sin duplicar la fila repetida")
        assertEquals("2026-10-01T10:00:00Z", observed[1].publishedAt)
    }

    @Test
    fun staleVersionBecomesAValidationErrorWithItsCode() = runTest {
        val repository = repository(mutableListOf()) {
            json(HttpStatusCode.PreconditionFailed, """{"error":"precondition_failed","message":"El recurso ha cambiado desde la ultima lectura.","code":"PRECONDITION_FAILED"}""")
        }

        val result = repository.updateNews(article.copy(id = ID, version = VERSION))

        assertIs<AppResult.Failure>(result)
        val error = assertIs<AppError.Validation>(result.error)
        assertEquals("precondition_failed", error.code)
        assertEquals("El recurso ha cambiado desde la ultima lectura.", error.message)
    }

    @Test
    fun commandsPostToTheirRouteAndRefreshTheObservedRow() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.OK, adminWire) }

        repository.publishNews(ID)
        repository.scheduleNews(ID, "2026-12-01T09:00:00Z")
        repository.archiveNews(ID)

        assertEquals(
            listOf("/api/v1/admin/noticias/$ID/publicar", "/api/v1/admin/noticias/$ID/programar", "/api/v1/admin/noticias/$ID/archivar"),
            requests.map { it.request.url.encodedPath },
        )
        assertEquals("", requests[0].body, "publicar va sin cuerpo")
        assertEquals("2026-12-01T09:00:00Z", Json.parseToJsonElement(requests[1].body).jsonObject["publicarDesde"]!!.jsonPrimitive.content)
        val observed = repository.observeNews().first()
        assertEquals(listOf(ID), observed.map { it.id })
        assertEquals("scheduled", observed.single().status)
    }

    @Test
    fun uploadSendsMultipartWithIdempotencyKeyAndRejectsNonImages() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) {
            json(HttpStatusCode.Created, """{"path":"news/$ID/a.png","publicUrl":"https://example.supabase.co/storage/v1/object/public/news-assets/news/$ID/a.png","mime":"image/png","bytes":3}""")
        }
        val png = Files.createTempFile("news-asset", ".png").toFile()
        val txt = Files.createTempFile("news-asset", ".txt").toFile()
        try {
            png.writeBytes(byteArrayOf(1, 2, 3))
            txt.writeText("no")
            val progress = mutableListOf<Float>()
            val ok = repository.uploadNewsAsset("file://" + png.absolutePath.replace('\\', '/'), KEY) { progress += it }
            assertIs<AppResult.Success<NewsAssetUpload>>(ok)
            assertEquals("news/$ID/a.png", ok.value.path)
            val sent = requests.single()
            assertEquals(KEY, sent.request.headers["Idempotency-Key"])
            assertTrue(sent.request.body.contentType.toString().startsWith("multipart/form-data"), sent.request.body.contentType.toString())

            val rejected = repository.uploadNewsAsset(txt.absolutePath, KEY)
            assertIs<AppResult.Failure>(rejected)
            assertEquals(1, requests.size, "un fichero que no es imagen no llega a enviarse")
        } finally {
            png.delete()
            txt.delete()
        }
    }

    private companion object {
        const val ID = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"
        const val ESP = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val TIPO = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
        const val KEY = "6f1c2a3e-1111-4111-8111-aaaaaaaaaaa1"
        const val VERSION = "2026-10-08T12:00:00.000001Z"
    }
}
