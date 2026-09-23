package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.model.SystemStatusLevel
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * El adaptador que cierra el hueco de la pantalla de Estado del sistema.
 *
 * `GET /api/v1/admin/estado-sistema` respondía desde hace tiempo mientras la
 * pantalla seguía diciendo "sin conectar". Lo que estas pruebas fijan es que al
 * conectarlo no se haya colado la tentación contraria: rellenar con verde o con
 * ceros lo que el backend declara no instrumentado.
 */
class KtorSystemStatusRepositoryImplTest {

    @Test
    fun aRealResponseIsMappedAndWhatHasNoSourceStaysNull() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(
            """
            {"data":{
              "overallStatus":"DEGRADED","ktorStatus":"OPERATIVE","supabaseStatus":"DEGRADED",
              "workerStatus":"UNKNOWN","cpuPercent":12.5,"ramPercent":41.25,
              "apiLatencyMs":1800,"errors24h":0,"updatedAt":"2026-09-02T10:00:00Z",
              "notInstrumented":["workerStatus","mobileCrashes24h","queueDepth"]
            }}
            """.trimIndent(),
            record = requests,
        )

        val status = assertIs<SystemStatusAvailability.Available>(repository.loadStatus()).value

        assertEquals("/api/v1/admin/estado-sistema", requests.single().url.encodedPath)
        assertEquals(SystemStatusLevel.DEGRADED, status.overallStatus)
        assertEquals(SystemStatusLevel.OPERATIVE, status.ktorStatus)
        assertEquals(SystemStatusLevel.UNKNOWN, status.workerStatus)
        assertEquals(12.5, status.cpuPercent)
        assertEquals(41.25, status.ramPercent)
        assertEquals(1800L, status.apiLatencyMs)
        assertEquals(0L, status.errors24h)
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), status.updatedAt)
        // Lo que el backend no mide no llega como 0: un cero sería una
        // medición real, no una ausencia.
        assertNull(status.mobileCrashes24h)
        assertNull(status.queueDepth)
    }

    @Test
    fun anUnknownLevelIsUnknownAndNeverOperative() = runTest {
        val repository = repository(
            """{"data":{"overallStatus":"VERDE","ktorStatus":"","supabaseStatus":"operative","updatedAt":""}}""",
        )

        val status = assertIs<SystemStatusAvailability.Available>(repository.loadStatus()).value

        assertEquals(SystemStatusLevel.UNKNOWN, status.overallStatus, "Un nivel que no se entiende no se pinta verde.")
        assertEquals(SystemStatusLevel.UNKNOWN, status.ktorStatus)
        // El vocabulario del cable es en mayúsculas, pero aceptarlo en minúsculas
        // no inventa nada: es el mismo nombre.
        assertEquals(SystemStatusLevel.OPERATIVE, status.supabaseStatus)
        assertNull(status.updatedAt, "Sin marca de tiempo no se inventa una.")
    }

    @Test
    fun aFailedCallIsAFailureAndNotADowngradeToNotConnected() = runTest {
        val repository = repository("""{"error":"boom"}""", status = HttpStatusCode.InternalServerError)

        val availability = repository.loadStatus()

        assertIs<SystemStatusAvailability.Failure>(
            availability,
            "'No he podido preguntar' no es lo mismo que 'aquí no hay observabilidad': " +
                "confundirlos escondería una caída real tras un aviso de configuración pendiente.",
        )
    }

    @Test
    fun prometheusMetricsRemainUnavailableWhenTheBackendReturnsNulls() = runTest {
        val repository = repository(
            """{"data":{"overallStatus":"OPERATIVE","ktorStatus":"OPERATIVE","supabaseStatus":"OPERATIVE","workerStatus":"UNKNOWN","cpuPercent":null,"ramPercent":null,"apiLatencyMs":null,"errors24h":null,"updatedAt":"2026-09-22T10:00:00Z","notInstrumented":["workerStatus","cpuPercent","ramPercent","apiLatencyMs","errors24h","mobileCrashes24h","queueDepth"]}}""",
        )

        val status = assertIs<SystemStatusAvailability.Available>(repository.loadStatus()).value

        assertEquals(SystemStatusLevel.OPERATIVE, status.ktorStatus)
        assertEquals(SystemStatusLevel.OPERATIVE, status.supabaseStatus)
        assertEquals(SystemStatusLevel.UNKNOWN, status.workerStatus)
        assertNull(status.cpuPercent)
        assertNull(status.ramPercent)
        assertNull(status.apiLatencyMs)
        assertNull(status.errors24h)
        assertEquals(Instant.parse("2026-09-22T10:00:00Z"), status.updatedAt)
    }

    // -----------------------------------------------------------------

    private fun repository(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        record: MutableList<HttpRequestData>? = null,
    ): KtorSystemStatusRepositoryImpl {
        val client = HttpClient(
            MockEngine { request ->
                record?.add(request)
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return KtorSystemStatusRepositoryImpl(
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
        )
    }
}
