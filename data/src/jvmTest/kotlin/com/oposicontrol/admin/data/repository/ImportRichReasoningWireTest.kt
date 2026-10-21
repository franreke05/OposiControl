package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.RazonadoMarkup
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FA38 en el cable (`E1-V1-MARKUP-CONTRACT.md` §4): `razonamiento` es el texto FUENTE con marcas y es lo único que se
 * escribe; `razonamientoRico` es la estructura derivada, de solo lectura. El panel la lee como árbol tipado y, con el
 * mismo texto, el analizador local produce EXACTAMENTE esa estructura (paridad entre cliente y servidor).
 */
class ImportRichReasoningWireTest {
    private fun appConfig() = AppConfig(
        environment = Environment.DEV, baseUrl = "http://localhost:8080", backendToken = "t", supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "k", supabaseBucketExamResources = "b", featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin", appVersion = "test",
    )

    private fun repo(body: String): KtorAdminV1ContentRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        val client = HttpClient(
            MockEngine { respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return KtorAdminV1ContentRepositoryImpl(
            client, appConfig(), SessionLocalDataSource(AdminDatabase(driver)), NetworkResultAdapter(NoOpLogger), PlatformFileReader(AppPlatformContext()),
        )
    }

    private val source = "**Clave** y [color:red]peligro[/color] [size:lg]grande[/size]\n[align:center]centrado[/align]"

    private fun stagingWith(rico: String?): String {
        val sourceJson = Json.encodeToString(kotlinx.serialization.serializer<String>(), source)
        val ricoPart = if (rico == null) "" else ""","razonamientoRico":$rico"""
        return """{"data":{"id":"b1","examenId":"e1","staging":[{"id":"s1","posicion":1,"titulo":"T","razonamiento":$sourceJson$ricoPart}]}}"""
    }

    private fun item(rico: String?): ContentImportStagingItem =
        (runTestBlocking { repo(stagingWith(rico)).getImportBatch("b1") } as AppResult.Success).value.staging.single()

    private fun <T> runTestBlocking(block: suspend () -> T): T {
        var out: Result<T>? = null
        runTest { out = Result.success(block()) }
        return out!!.getOrThrow()
    }

    @Test
    fun `el texto fuente con marcas se lee tal cual y la estructura del servidor llega como arbol tipado`() {
        val parsed = (RazonadoMarkup.parse(source) as RazonadoMarkup.Result.Ok).doc
        val serverStructure = RazonadoMarkup.toWireJson(parsed)
        val read = item(serverStructure)
        assertEquals(source, read.reasoning)
        assertNotNull(read.reasoningRich)
        // paridad: lo que el servidor deriva del texto es lo que el analizador local deriva del mismo texto
        assertEquals(parsed, read.reasoningRich)
    }

    @Test
    fun `sin estructura el razonamiento es solo texto`() {
        assertNull(item(null).reasoningRich)
        assertNull(item("null").reasoningRich)
    }

    @Test
    fun `lo que no se entiende se ignora sin inventar estilo`() {
        val json = """{"v":1,"blocks":[{"k":"p","a":"diagonal","spans":[
            {"text":"a","color":"fucsia","size":"huge","bold":true,"onclick":"x"},{"text":"","bold":true},{"nada":1}]},{"k":"zz","spans":[{"text":"b"}]}]}"""
        val doc = assertNotNull(item(json).reasoningRich)
        assertEquals(2, doc.blocks.size)
        val first = doc.blocks[0]
        assertNull(first.align)
        assertEquals(listOf(RazonadoMarkup.Span("a", bold = true)), first.spans)
        assertEquals(RazonadoMarkup.Kind.PARAGRAPH, doc.blocks[1].kind)
    }

    @Test
    fun `una version desconocida cae al texto fuente`() {
        assertNull(item("""{"v":2,"blocks":[{"k":"p","spans":[{"text":"a"}]}]}""").reasoningRich)
        assertNull(item("""{"blocks":[]}""").reasoningRich)
    }

    @Test
    fun `el colores hexadecimales validos pasan y un html camuflado queda como texto`() {
        val doc = assertNotNull(item("""{"v":1,"blocks":[{"k":"li","spans":[{"text":"<script>x</script>","color":"#aa00FF"},{"text":"y","color":"#GG0000"}]}]}""").reasoningRich)
        assertEquals("<script>x</script>", doc.blocks.single().spans[0].text)
        assertEquals("#aa00FF", doc.blocks.single().spans[0].color)
        assertNull(doc.blocks.single().spans[1].color)
        assertEquals(RazonadoMarkup.Kind.BULLET, doc.blocks.single().kind)
    }

    @Test
    fun `al guardar viaja el texto fuente y nunca la estructura derivada`() {
        val rich = RazonadoMarkup.toWireJson((RazonadoMarkup.parse(source) as RazonadoMarkup.Result.Ok).doc)
        val saved = item(rich)
        val body = saved.toPatchBody()
        assertEquals(source, body["razonamiento"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertFalse(body.containsKey("razonamientoRico") || body.containsKey("razonamiento_rico") || body.containsKey("razonamientoMarkup"), body.toString())
        assertTrue(body.jsonObject.keys.none { it.contains("rico", ignoreCase = true) })
    }

    @Test
    fun `ida y vuelta de la estructura con el escritor del panel`() {
        val doc = (RazonadoMarkup.parse(source) as RazonadoMarkup.Result.Ok).doc
        val again = item(RazonadoMarkup.toWireJson(doc)).reasoningRich
        assertEquals(doc, again)
        assertEquals(RazonadoMarkup.plain(doc), RazonadoMarkup.plain(assertNotNull(again)))
    }
}
