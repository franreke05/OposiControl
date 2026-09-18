package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.ApiClientFactory
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.storage.PlatformFileReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F-04 · El presupuesto de tiempo de las operaciones largas de Contenido.
 *
 * El cliente de la app instala `HttpTimeout` con 30 s de petición y de socket. Las
 * operaciones que el servidor tarda más en terminar amplían el suyo POR PETICIÓN; si
 * no lo hicieran, el cliente se rendiría mientras Ktor sigue escribiendo, y el
 * reintento natural del administrador crearía el lote o lo publicaría dos veces.
 *
 * El cliente de prueba instala el mismo defecto que el real, así que lo que se
 * comprueba es el presupuesto EFECTIVO que llega al motor: el `timeout { }` de la
 * petición mezclado con el defecto del cliente. Por eso un `timeout { }` que sólo
 * ampliara el de petición se ve aquí con el socket a 30 s, que es justo lo que
 * cortaría una publicación larga en la que el servidor no manda nada.
 */
class KtorAdminV1ContentTimeBudgetTest {

    private val tempFiles = mutableListOf<File>()

    private val defaultBudget = Budget(
        ApiClientFactory.DEFAULT_REQUEST_TIMEOUT_MILLIS,
        ApiClientFactory.DEFAULT_REQUEST_TIMEOUT_MILLIS,
    )

    @AfterTest
    fun deleteTempFiles() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun publishingABatchWaitsFiveMinutesForTheResponseAndForTheSocket() = runTest {
        val requests = mutableListOf<HttpRequestData>()

        assertIs<AppResult.Success<*>>(repository(requests).publishImportBatch("L-1"))

        assertEquals(Budget(300_000, 300_000), requests.single().budget())
    }

    /**
     * Importar preguntas crea el lote y lo publica en la misma operación: sólo la
     * publicación necesita los cinco minutos. Leer el catálogo, crear el lote y
     * releer las preguntas se quedan con el defecto del cliente.
     */
    @Test
    fun importingQuestionsGivesTheLongBudgetOnlyToThePublication() = runTest {
        val requests = mutableListOf<HttpRequestData>()

        assertIs<AppResult.Success<*>>(repository(requests).importQuestions("EX-1", emptyList()))

        val publication = requests.single { it.url.encodedPath.endsWith("/importar") }
        assertEquals(Budget(300_000, 300_000), publication.budget())
        val others = requests - publication
        assertTrue(others.isNotEmpty(), "la importación también lee y crea el lote")
        others.forEach { request ->
            assertEquals(defaultBudget, request.budget(), "${request.method.value} ${request.url.encodedPath}")
        }
    }

    @Test
    fun aPdfUploadWaitsThreeMinutesForTheResponseAndForTheSocket() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val pdf = tempFile(".pdf", "%PDF-1.4".encodeToByteArray())

        val result = repository(requests).uploadPdfToStaging("EX-1", "rafaga", pdf.absolutePath, pdf.name)

        assertIs<AppResult.Success<*>>(result)
        assertEquals(Budget(180_000, 180_000), requests.single().budget())
    }

    @Test
    fun aCoverUploadWaitsOneMinuteForTheResponseAndForTheSocket() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val cover = tempFile(".png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        assertIs<AppResult.Success<*>>(repository(requests).uploadExamCover("EX-1", cover.absolutePath))

        assertEquals(Budget(60_000, 60_000), requests.single().budget())
    }

    /** Una operación «larga» con menos tiempo que una corriente no tendría sentido. */
    @Test
    fun noLongOperationGetsLessTimeThanTheClientDefault() {
        listOf(
            ADMIN_V1_PDF_IMPORT_TIMEOUT_MILLIS,
            ADMIN_V1_BATCH_PUBLISH_TIMEOUT_MILLIS,
            ADMIN_V1_COVER_UPLOAD_TIMEOUT_MILLIS,
        ).forEach { budget ->
            assertTrue(budget > ApiClientFactory.DEFAULT_REQUEST_TIMEOUT_MILLIS, "$budget ms")
        }
    }

    // -----------------------------------------------------------------

    private data class Budget(val requestMillis: Long?, val socketMillis: Long?)

    private fun HttpRequestData.budget(): Budget =
        getCapabilityOrNull(HttpTimeoutCapability).let { Budget(it?.requestTimeoutMillis, it?.socketTimeoutMillis) }

    private fun tempFile(suffix: String, bytes: ByteArray): File =
        File.createTempFile("f04-budget", suffix).also { file ->
            file.writeBytes(bytes)
            tempFiles += file
        }

    private fun repository(record: MutableList<HttpRequestData>): KtorAdminV1ContentRepositoryImpl {
        val client = HttpClient(
            MockEngine { request ->
                record += request
                val path = request.url.encodedPath
                val body = when {
                    request.method == HttpMethod.Get && path.endsWith("/examenes") -> EXAMENES
                    request.method == HttpMethod.Get && path.endsWith("/preguntas") -> """{"data":[]}"""
                    path.contains("/importaciones") -> LOTE
                    else -> EXAMEN
                }
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            // El mismo defecto que instala `ApiClientFactory` en la app.
            install(HttpTimeout) {
                requestTimeoutMillis = ApiClientFactory.DEFAULT_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = ApiClientFactory.DEFAULT_REQUEST_TIMEOUT_MILLIS
            }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return KtorAdminV1ContentRepositoryImpl(
            client,
            AppConfig(
                environment = Environment.DEV,
                baseUrl = "http://localhost:8080",
                backendToken = "test-token",
                supabaseUrl = "https://example.supabase.co",
                supabaseAnonKey = "anon-key",
                supabaseBucketExamResources = "exam-resources",
                featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
                appName = "OposiControl Admin",
                appVersion = "test",
            ),
            SessionLocalDataSource(AdminDatabase(driver)),
            NetworkResultAdapter(NoOpLogger),
            PlatformFileReader(AppPlatformContext()),
        )
    }

    private companion object {
        val EXAMENES = """{"data":[{"id":"EX-1","especialidadId":"ESP","titulo":"Ráfaga","tipoExamen":"rafaga"}]}"""
        val EXAMEN = """{"data":{"id":"EX-1","especialidadId":"ESP","titulo":"Ráfaga","tipoExamen":"rafaga"}}"""
        val LOTE = """{"data":{"id":"L-1","examenId":"EX-1","nombreArchivo":"lote.pdf","tipoExamen":"rafaga"}}"""
    }
}
