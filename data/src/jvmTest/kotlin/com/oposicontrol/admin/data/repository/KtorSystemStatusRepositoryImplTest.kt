package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.DeployedVersion
import com.oposicontrol.admin.domain.model.SupabaseProjectionBasis
import com.oposicontrol.admin.domain.model.SupabaseQuota
import com.oposicontrol.admin.domain.model.SupabaseQuotaTarget
import com.oposicontrol.admin.domain.model.SystemAlert
import com.oposicontrol.admin.domain.model.SystemMetric
import com.oposicontrol.admin.domain.model.SystemReplica
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
              "supabaseDiskPercent":3.5632,
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
        assertEquals(12.5, status.cpuPercent)
        assertEquals(41.25, status.ramPercent)
        assertEquals(3.5632, status.supabaseDiskPercent)
        assertEquals(1800L, status.apiLatencyMs)
        assertEquals(0L, status.errors24h)
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), status.updatedAt)
        // Esta es la respuesta de un backend ANTERIOR: todavía manda `workerStatus` y declara worker, crashes y cola
        // como no instrumentados. Nada de eso existe ya en el panel, así que se ignora sin romper la lectura…
        assertEquals(emptySet(), status.notInstrumented)
        // …y lo que ese backend aún no sabe decir llega como ausencia, no como «cero réplicas» ni «ninguna alerta».
        assertNull(status.replicas)
        assertNull(status.alerts)
        assertNull(status.supabaseQuota)
        assertNull(status.deployedVersion)
        assertNull(status.prometheusResponding)
    }

    @Test
    fun replicasAlertsQuotaVersionAndSourceAreMappedAsTheBackendSendsThem() = runTest {
        val repository = repository(
            """
            {"data":{
              "overallStatus":"DEGRADED","ktorStatus":"DEGRADED","supabaseStatus":"OPERATIVE",
              "cpuPercent":12.5,"updatedAt":"2026-10-13T10:00:00Z",
              "notInstrumented":["ramPercent","supabaseDiskPercent","apiLatencyMs","errors24h","algoNuevo"],
              "replicas":[
                {"nombre":"bot-1","perfil":"bot","arriba":true,"veSupabase":true,"version":"886dac044b6e","arrancadaEn":"2026-10-12T08:00:00Z"},
                {"nombre":"bot-2","perfil":"bot","arriba":false},
                {"nombre":"control-1","perfil":"control","arriba":true,"veSupabase":false,"version":"0123456789ab","arrancadaEn":"no-es-una-fecha"},
                {"nombre":"  ","arriba":true}
              ],
              "alertas":[{"nombre":"KtorInstanceDown","severidad":"critical","replica":"bot-2"},{"nombre":"SupabaseEgressHigh","severidad":"warning","resumen":""}],
              "cuotaSupabase":{"bytes24h":120000000,"bytes7d":700000000,"proyeccionMensualBytes":3000000000,"proyeccionBase":"7d",
                "limiteMensualBytes":5000000000,"porDestino":[{"destino":"rest","bytes24h":100000000,"bytes7d":600000000},{"destino":"storage","bytes24h":0}]},
              "version":{"sha":"886dac044b6e","replicasDifieren":true},
              "fuente":{"prometheusResponde":true}
            }}
            """.trimIndent(),
        )

        val status = assertIs<SystemStatusAvailability.Available>(repository.loadStatus()).value

        assertEquals(
            listOf(
                SystemReplica("bot-1", "bot", up = true, seesSupabase = true, version = "886dac044b6e", startedAt = Instant.parse("2026-10-12T08:00:00Z")),
                // Caída: sin los datos que ya no puede publicar. Ni «ve Supabase: no» ni una versión supuesta.
                SystemReplica("bot-2", "bot", up = false),
                // En pie y sin llegar a Supabase es un NO medido; una fecha que no se entiende no se inventa.
                SystemReplica("control-1", "control", up = true, seesSupabase = false, version = "0123456789ab", startedAt = null),
            ),
            status.replicas,
            "una fila sin nombre no identifica a ninguna réplica y se descarta",
        )
        assertEquals(
            listOf(
                SystemAlert("KtorInstanceDown", severity = "critical", replica = "bot-2"),
                SystemAlert("SupabaseEgressHigh", severity = "warning", summary = null),
            ),
            status.alerts,
        )
        assertEquals(
            SupabaseQuota(
                bytes24h = 120_000_000,
                bytes7d = 700_000_000,
                projectedMonthlyBytes = 3_000_000_000,
                projectionBasis = SupabaseProjectionBasis.SEVEN_DAYS,
                monthlyLimitBytes = 5_000_000_000,
                byTarget = listOf(
                    SupabaseQuotaTarget("rest", 100_000_000, 600_000_000),
                    // Cero medido en 24 h, y sin lectura en 7 d: las dos cosas se conservan distintas.
                    SupabaseQuotaTarget("storage", bytes24h = 0, bytes7d = null),
                ),
            ),
            status.supabaseQuota,
        )
        assertEquals(DeployedVersion(sha = "886dac044b6e", replicasDiffer = true), status.deployedVersion)
        assertEquals(true, status.prometheusResponding)
        assertEquals(
            setOf(SystemMetric.MEMORY, SystemMetric.SUPABASE_DISK, SystemMetric.API_LATENCY, SystemMetric.API_ERRORS),
            status.notInstrumented,
            "una clave desconocida se ignora",
        )
    }

    @Test
    fun noAlertsIsAnEmptyListAndASilentPrometheusLeavesEverythingMissing() = runTest {
        val head = """{"data":{"overallStatus":"UNKNOWN","ktorStatus":"UNKNOWN","supabaseStatus":"OPERATIVE","updatedAt":"2026-10-13T10:00:00Z","""

        val calm = assertIs<SystemStatusAvailability.Available>(
            repository("""$head"alertas":[],"fuente":{"prometheusResponde":true}}}""").loadStatus(),
        ).value
        val silent = assertIs<SystemStatusAvailability.Available>(
            repository("""$head"notInstrumented":["cpuPercent"],"fuente":{"prometheusResponde":false}}}""").loadStatus(),
        ).value

        assertEquals(emptyList(), calm.alerts, "«ninguna alerta activa» es un dato")
        assertNull(silent.alerts, "sin lectura no hay lista: no se puede afirmar que no haya alertas")
        assertNull(silent.replicas)
        assertEquals(false, silent.prometheusResponding)
        assertEquals(SystemStatusLevel.UNKNOWN, silent.ktorStatus, "nunca verde sin lectura")
    }

    @Test
    fun aQuotaWithoutAUsableLimitOrBasisKeepsThemMissing() = runTest {
        val status = assertIs<SystemStatusAvailability.Available>(
            repository(
                """{"data":{"overallStatus":"OPERATIVE","ktorStatus":"OPERATIVE","supabaseStatus":"OPERATIVE","updatedAt":"",""" +
                    """"cuotaSupabase":{"bytes24h":5,"proyeccionBase":"30d","limiteMensualBytes":0}}}""",
            ).loadStatus(),
        ).value

        assertEquals(5L, status.supabaseQuota?.bytes24h)
        assertNull(status.supabaseQuota?.bytes7d)
        assertNull(status.supabaseQuota?.projectionBasis)
        assertNull(status.supabaseQuota?.monthlyLimitBytes, "un límite de cero no es un límite")
    }

    // --- sesión ---

    @Test
    fun theSessionComesFromTheServerWithItsRealRoles() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(
            """{"usuarioId":"5e5e5e5e-0000-4000-8000-000000000001","nombre":" Fran ","roles":["admin","superadmin"],"esSuperadmin":true,"esAdmin":true}""",
            record = requests,
        )

        val session = assertIs<AppResult.Success<AdminSessionInfo>>(repository.loadSession()).value

        assertEquals("/api/v1/admin/sesion", requests.single().url.encodedPath)
        assertEquals(
            AdminSessionInfo("5e5e5e5e-0000-4000-8000-000000000001", "Fran", listOf("admin", "superadmin"), isSuperAdmin = true, isAdmin = true),
            session,
        )
    }

    @Test
    fun aSessionWithoutFlagsIsNeitherAdminNorSuperadmin() = runTest {
        // El backend no codifica los valores por defecto: `false` y la lista vacía llegan como campos AUSENTES.
        val session = assertIs<AppResult.Success<AdminSessionInfo>>(
            repository("""{"usuarioId":"5e5e5e5e-0000-4000-8000-000000000002"}""").loadSession(),
        ).value

        assertEquals(AdminSessionInfo("5e5e5e5e-0000-4000-8000-000000000002"), session)
    }

    @Test
    fun aForbiddenSessionIsAnAuthorizationErrorAndNotAnEmptySession() = runTest {
        val result = repository("""{"error":"FORBIDDEN","message":"No tienes permisos"}""", status = HttpStatusCode.Forbidden).loadSession()

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
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
        assertEquals(
            setOf(SystemMetric.CPU, SystemMetric.MEMORY, SystemMetric.API_LATENCY, SystemMetric.API_ERRORS),
            status.notInstrumented,
        )
        assertNull(status.cpuPercent)
        assertNull(status.ramPercent)
        assertNull(status.apiLatencyMs)
        assertNull(status.errors24h)
        assertEquals(Instant.parse("2026-09-22T10:00:00Z"), status.updatedAt)
    }

    @Test
    fun theSupabaseDiskKeepsAMeasuredZeroAndAnAbsenceAsNull() = runTest {
        val head = """{"data":{"overallStatus":"OPERATIVE","ktorStatus":"OPERATIVE","supabaseStatus":"OPERATIVE","""

        val measuredZero = assertIs<SystemStatusAvailability.Available>(
            repository("""$head"supabaseDiskPercent":0.0,"updatedAt":"2026-09-23T10:00:00Z"}}""").loadStatus(),
        ).value
        val explicitNull = assertIs<SystemStatusAvailability.Available>(
            repository("""$head"supabaseDiskPercent":null,"updatedAt":"2026-09-23T10:00:00Z"}}""").loadStatus(),
        ).value
        // El backend omite los nulos (`explicitNulls = false`), así que el campo
        // ausente también tiene que decodificar como ausencia, no como cero.
        val omitted = assertIs<SystemStatusAvailability.Available>(
            repository("""$head"updatedAt":"2026-09-23T10:00:00Z"}}""").loadStatus(),
        ).value

        assertEquals(0.0, measuredZero.supabaseDiskPercent, "Cero medido no es lo mismo que no disponible.")
        assertNull(explicitNull.supabaseDiskPercent)
        assertNull(omitted.supabaseDiskPercent)
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
