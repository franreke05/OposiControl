package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.AnalyticsSectionReport
import com.oposicontrol.admin.domain.model.AnalyticsValue
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapeo de la analítica: lo que llega por el cable y lo que acaba en pantalla.
 *
 * El foco es el mismo que en el backend: que un cero real y un hueco no puedan
 * confundirse, y que un número de relleno acompañado de su clave marcada nunca
 * sobreviva hasta la UI.
 */
class KtorProductAnalyticsRepositoryImplTest {

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

    private fun sessionLocalDataSource(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    private fun repository(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        record: MutableList<HttpRequestData>? = null,
    ): KtorProductAnalyticsRepositoryImpl {
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
        return KtorProductAnalyticsRepositoryImpl(
            client,
            appConfig(),
            sessionLocalDataSource(),
            NetworkResultAdapter(NoOpLogger),
        )
    }

    private fun AppResult<AnalyticsSectionReport>.report(): AnalyticsSectionReport =
        assertIs<AppResult.Success<AnalyticsSectionReport>>(this).value

    // -----------------------------------------------------------------

    @Test
    fun everySectionGoesThroughTheKtorBackendAndNeverStraightToSupabase() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository("""{"generatedAt":"2026-09-02T00:00:00Z"}""", record = requests)

        AnalyticsSection.entries.forEach { repository.getSection(it) }

        assertEquals(6, requests.size)
        requests.forEach { request ->
            assertTrue(
                request.url.toString().startsWith("http://localhost:8080"),
                "La analítica pasa por Ktor, no por Supabase: ${request.url}",
            )
            assertTrue(
                !request.url.host.contains("supabase", ignoreCase = true),
                "El cliente nunca habla directamente con Supabase: ${request.url}",
            )
            assertEquals("GET", request.method.value, "La analítica es sólo lectura.")
        }
        assertEquals(
            listOf(
                "/api/v1/admin/analytics/producto",
                "/api/v1/admin/analytics/estudio",
                "/api/v1/admin/analytics/retencion",
                "/api/v1/admin/analytics/comunidad",
                "/api/v1/admin/analytics/economia",
                "/api/v1/admin/analytics/marketing",
            ),
            requests.map { it.url.encodedPath },
        )
    }

    @Test
    fun aRealZeroSurvivesTheMappingAsAValue() = runTest {
        val repository = repository(
            """{"totalUsers":3,"guestUsers":0,"generatedAt":"2026-09-02T00:00:00Z","unavailableMetrics":[],"notInstrumented":[]}""",
        )

        val report = repository.getSection(AnalyticsSection.PRODUCTO).report()

        val guests = report.metric("guestUsers")
        assertEquals(AnalyticsMetricStatus.AVAILABLE, guests?.status)
        assertEquals(AnalyticsValue.Count(0), guests?.value)
    }

    @Test
    fun aMetricNamedAsUnavailableIsNoDataEvenIfTheFieldCarriesANumber() = runTest {
        // El caso que mata el panel: el DTO trae 0 de relleno y a la vez declara
        // la clave. Si el mapeador se fiara del campo, la pantalla enseñaría un
        // cero que nadie ha calculado.
        val repository = repository(
            """{"totalUsers":0,"generatedAt":"2026-09-02T00:00:00Z","unavailableMetrics":["totalUsers"],"notInstrumented":[]}""",
        )

        val report = repository.getSection(AnalyticsSection.PRODUCTO).report()

        val total = report.metric("totalUsers")
        assertEquals(AnalyticsMetricStatus.NO_DATA, total?.status)
        assertNull(total?.value)
    }

    @Test
    fun aStructuralGapBecomesNotInstrumentedAndKeepsItsReason() = runTest {
        val repository = repository(
            """{
                "generatedAt":"2026-09-02T00:00:00Z",
                "unavailableMetrics":["avgScore"],
                "notInstrumented":[{"key":"avgScore","reason":"Necesita AVG(porcentaje_acierto)."}]
            }""",
        )

        val report = repository.getSection(AnalyticsSection.ESTUDIO).report()

        val avgScore = report.metric("avgScore")
        assertEquals(AnalyticsMetricStatus.NOT_INSTRUMENTED, avgScore?.status)
        assertEquals("Necesita AVG(porcentaje_acierto).", avgScore?.reason)
        assertNull(avgScore?.value)
    }

    @Test
    fun aGapWithoutItsOwnFieldStillGetsACardInTheReport() = runTest {
        // `attemptsPerDay` no tiene campo en el DTO porque no hay nada que
        // transportar; si no se materializara como tarjeta, el pendiente sería
        // invisible en la pantalla.
        val repository = repository(
            """{
                "generatedAt":"2026-09-02T00:00:00Z",
                "unavailableMetrics":["attemptsPerDay"],
                "notInstrumented":[{"key":"attemptsPerDay","reason":"Necesita GROUP BY por día."}]
            }""",
        )

        val report = repository.getSection(AnalyticsSection.ESTUDIO).report()

        val perDay = report.metric("attemptsPerDay")
        assertEquals(AnalyticsMetricStatus.NOT_INSTRUMENTED, perDay?.status)
        assertEquals("Necesita GROUP BY por día.", perDay?.reason)
    }

    @Test
    fun moneyArrivesAsWholeCentsAndIsMappedAsMoneyNotAsACount() = runTest {
        val repository = repository(
            """{"grossRevenueCents":9999,"generatedAt":"2026-09-02T00:00:00Z","unavailableMetrics":[],"notInstrumented":[]}""",
        )

        val report = repository.getSection(AnalyticsSection.ECONOMIA).report()

        assertEquals(AnalyticsValue.MoneyCents(9_999L), report.metric("grossRevenueCents")?.value)
    }

    @Test
    fun aFailedLeagueBucketIsShownAsUnknownAndNotAsZeroUsers() = runTest {
        val repository = repository(
            """{
                "generatedAt":"2026-09-02T00:00:00Z",
                "unavailableMetrics":[],
                "notInstrumented":[],
                "leagueDistribution":[
                    {"code":"liga_50","name":"Liga 50","minimumQuestions":50,"windowSize":50,"users":12},
                    {"code":"liga_200","name":"Liga 200","minimumQuestions":200,"windowSize":100,"users":null}
                ]
            }""",
        )

        val report = repository.getSection(AnalyticsSection.RETENCION).report()

        val table = report.tables.single { it.key == "leagueDistribution" }
        assertEquals(AnalyticsMetricStatus.AVAILABLE, table.status)
        assertEquals("12", table.rows.first().cells.last())
        assertEquals("sin dato", table.rows.last().cells.last())
    }

    @Test
    fun aTableThatCouldNotBeReadIsNotRenderedAsAnEmptyTable() = runTest {
        // Una tabla vacía se lee como "no hay nada", que es justo lo que aquí
        // nadie puede afirmar.
        val repository = repository(
            """{
                "generatedAt":"2026-09-02T00:00:00Z",
                "unavailableMetrics":["hardestQuestions"],
                "notInstrumented":[],
                "hardestQuestions":null
            }""",
        )

        val report = repository.getSection(AnalyticsSection.ESTUDIO).report()

        val table = report.tables.single { it.key == "hardestQuestions" }
        assertEquals(AnalyticsMetricStatus.NO_DATA, table.status)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun theHardestQuestionsTableKeepsUnknownColumnsAsDashesInsteadOfZeros() = runTest {
        val repository = repository(
            """{
                "generatedAt":"2026-09-02T00:00:00Z",
                "unavailableMetrics":[],
                "notInstrumented":[],
                "hardestQuestions":[
                    {"questionId":"p1","title":"¿Capital de Francia?","questionType":"rafaga",
                     "totalAnswers":null,"totalWrong":null,"errorRate":null,"difficulty1To10":null}
                ]
            }""",
        )

        val report = repository.getSection(AnalyticsSection.ESTUDIO).report()

        val row = report.tables.single { it.key == "hardestQuestions" }.rows.single()
        assertEquals("¿Capital de Francia?", row.cells[0])
        assertEquals("rafaga", row.cells[1])
        assertTrue(row.cells.drop(2).all { it == "-" }, "Una vista sin datos aún no se rellena con ceros: $row")
    }

    @Test
    fun aBackendErrorFailsInsteadOfProducingAReportFullOfZeros() = runTest {
        val repository = repository("""{"error":"boom"}""", status = HttpStatusCode.InternalServerError)

        val result = repository.getSection(AnalyticsSection.PRODUCTO)

        assertIs<AppResult.Failure>(result)
    }

    @Test
    fun theCanonicalExamModeBucketsAreMappedIncludingTheOtherBucket() = runTest {
        val repository = repository(
            """{
                "totalAttempts":12,"attemptsRafaga":7,"attemptsRazonado":2,"attemptsInmersivo":1,"attemptsOtherMode":2,
                "generatedAt":"2026-09-02T00:00:00Z","unavailableMetrics":[],"notInstrumented":[]
            }""",
        )

        val report = repository.getSection(AnalyticsSection.ESTUDIO).report()

        assertEquals(AnalyticsValue.Count(7), report.metric("attemptsRafaga")?.value)
        assertEquals(AnalyticsValue.Count(2), report.metric("attemptsRazonado")?.value)
        assertEquals(AnalyticsValue.Count(1), report.metric("attemptsInmersivo")?.value)
        // Las filas con vocabulario legacy siguen contadas, no desaparecen.
        assertEquals(AnalyticsValue.Count(2), report.metric("attemptsOtherMode")?.value)
        assertTrue(
            report.metrics.none { it.key.contains("guiad", ignoreCase = true) },
            "El mapeo no introduce vocabulario legacy.",
        )
    }
}
