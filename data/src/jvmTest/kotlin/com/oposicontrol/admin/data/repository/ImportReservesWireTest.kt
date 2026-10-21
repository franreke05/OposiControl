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
import com.oposicontrol.admin.domain.model.IMPORT_BLOCK_MAIN
import com.oposicontrol.admin.domain.model.IMPORT_BLOCK_RESERVE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FA37 en el cable: el bloque de cada fila, la cabecera del lote, el lote repetido y el resultado de publicar. */
class ImportReservesWireTest {
    private fun row(block: String = IMPORT_BLOCK_MAIN, annulled: Boolean = false) = ContentImportStagingItem(
        id = "s1", position = 1, title = "T", optionA = "a", optionB = "b", optionC = "c", optionD = "d", correctAnswer = "A",
        reasoning = "", sourceText = "", tagText = "", originalBlock = "", parseStatus = "ok", annulled = annulled, block = block,
    )

    @Test
    fun `el bloque viaja siempre en el PATCH de una fila y vale PRINCIPAL o RESERVA`() {
        assertEquals("PRINCIPAL", row().toPatchBody()["bloque"]?.jsonPrimitive?.content)
        assertEquals("RESERVA", row(IMPORT_BLOCK_RESERVE).toPatchBody()["bloque"]?.jsonPrimitive?.content)
        // un valor ajeno nunca sale: cualquier cosa que no sea RESERVA es PRINCIPAL
        assertEquals("PRINCIPAL", row("otro").toPatchBody()["bloque"]?.jsonPrimitive?.content)
        // y una anulada sigue llevando su anulación junto al bloque
        assertEquals("true", row(IMPORT_BLOCK_RESERVE, annulled = true).toPatchBody()["anulada"]?.jsonPrimitive?.content)
    }

    private fun appConfig() = AppConfig(
        environment = Environment.DEV, baseUrl = "http://localhost:8080", backendToken = "t", supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "k", supabaseBucketExamResources = "b", featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin", appVersion = "test",
    )

    private fun repo(body: String, requests: MutableList<HttpRequestData> = mutableListOf()): KtorAdminV1ContentRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return KtorAdminV1ContentRepositoryImpl(
            client, appConfig(), SessionLocalDataSource(AdminDatabase(driver)), NetworkResultAdapter(NoOpLogger), PlatformFileReader(AppPlatformContext()),
        )
    }

    private val lote = """
        {"data":{"id":"b1","examenId":"e1","nombreArchivo":"oficial.pdf","tipoExamen":"rafaga","estado":"parseado","totalDetectadas":2,
        "codigoExamen":"OFI-2024","huella":"abc123","duplicadoDe":"lote-viejo",
        "staging":[{"id":"s1","posicion":1,"titulo":"T","bloque":"PRINCIPAL"},{"id":"s2","posicion":2,"titulo":"R","bloque":"RESERVA"}]}}
    """.trimIndent()

    @Test
    fun `el lote trae la cabecera la huella y el lote repetido y cada fila su bloque`() = runTest {
        val batch = (repo(lote).getImportBatch("b1") as AppResult.Success).value
        assertEquals("OFI-2024", batch.examCode)
        assertEquals("abc123", batch.fingerprint)
        assertEquals("lote-viejo", batch.duplicateOfBatchId)
        assertEquals(listOf("PRINCIPAL", "RESERVA"), batch.staging.map { it.block })
    }

    @Test
    fun `un backend anterior sin los campos nuevos se lee como principales sin cabecera`() = runTest {
        val old = """{"data":{"id":"b1","examenId":"e1","staging":[{"id":"s1","posicion":1,"titulo":"T"}]}}"""
        val batch = (repo(old).getImportBatch("b1") as AppResult.Success).value
        assertNull(batch.examCode)
        assertNull(batch.duplicateOfBatchId)
        assertEquals(IMPORT_BLOCK_MAIN, batch.staging.single().block)
    }

    @Test
    fun `un bloque desconocido se lee como principal nunca como reserva`() = runTest {
        val odd = """{"data":{"id":"b1","examenId":"e1","staging":[{"id":"s1","posicion":1,"bloque":"RESERVAS"}]}}"""
        assertEquals(IMPORT_BLOCK_MAIN, (repo(odd).getImportBatch("b1") as AppResult.Success).value.staging.single().block)
    }

    @Test
    fun `publicar devuelve lo que hizo la llamada con reservas problema y avisos`() = runTest {
        val body = """
            {"data":{"id":"b1","examenId":"e1","estado":"importado","staging":[]},
             "resultado":{"ejecutado":true,"nuevas":47,"vinculadas":0,"omitidas":0,"totalImportadas":50,"anuladas":2,"reservas":3,
             "huella":"abc123","problema":"REEMPLAZO_NOT_ENOUGH_RESERVES","avisos":["MORE_RESERVES_THAN_MAX"]}}
        """.trimIndent()
        val result = (repo(body).publishImportBatch("b1", confirmExamPublished = false) as AppResult.Success).value.publishResult
        val r = assertIs<com.oposicontrol.admin.domain.model.ContentImportPublishResult>(result)
        assertTrue(r.executed)
        assertEquals(3, r.reserves)
        assertEquals(2, r.annulled)
        assertEquals("REEMPLAZO_NOT_ENOUGH_RESERVES", r.problem)
        assertEquals(listOf("MORE_RESERVES_THAN_MAX"), r.warnings)
    }

    @Test
    fun `una publicacion de un backend anterior no inventa reservas`() = runTest {
        val body = """{"data":{"id":"b1","examenId":"e1","staging":[]},"resultado":{"ejecutado":true,"nuevas":5}}"""
        val r = (repo(body).publishImportBatch("b1", false) as AppResult.Success).value.publishResult!!
        assertEquals(0, r.reserves)
        assertNull(r.problem)
        assertFalse(r.warnings.isNotEmpty())
    }
}
