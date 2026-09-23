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
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.RankingPageState
import com.oposicontrol.admin.domain.model.RankingSection
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El repositorio de rankings visto desde la red: qué pide exactamente y cómo
 * traduce lo que recibe.
 *
 * Los dos riesgos que estas pruebas cubren son el de convertir un listado en una
 * descarga de la tabla y el de que un hueco llegue a la pantalla disfrazado de
 * cero.
 */
class KtorRankingsRepositoryImplTest {

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
        requests: MutableList<HttpRequestData>,
        body: String,
    ): KtorRankingsRepositoryImpl {
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorRankingsRepositoryImpl(client, appConfig(), sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))
    }

    @Test
    fun anAbsurdPageSizeNeverLeavesTheClient() = runTest {
        // El backend recorta, pero el panel tampoco debe llegar a pedirlo: una
        // URL con `limit=999999` en los logs ya sería un bug visible.
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests, """{"data":[],"total":0,"limit":200,"offset":0}""")

        repository.getGlobalRanking(RankingPageRequest.of(999_999, -30))

        val url = requests.single().url
        assertEquals("200", url.parameters["limit"])
        assertEquals("0", url.parameters["offset"])
    }

    @Test
    fun everyReadGoesThroughKtorAndNeverStraightToSupabase() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests, """{"data":[]}""")
        val config = appConfig()

        repository.getLeagueLevels()
        repository.getGlobalRanking(RankingPageRequest.of())
        repository.getLeagueRanking("52405025-45e3-41ab-9d45-d80ec42ed98e", RankingPageRequest.of())
        repository.searchUsers("premium", RankingPageRequest.of())

        assertEquals(4, requests.size)
        requests.forEach { request ->
            assertTrue(
                request.url.toString().startsWith(config.baseUrl),
                "Rankings debe pasar por el backend Ktor (${config.baseUrl}): ${request.url}",
            )
            assertTrue(
                !request.url.host.contains("supabase", ignoreCase = true),
                "El escritorio nunca habla directamente con Supabase: ${request.url}",
            )
        }
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/rankings/ligas" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/rankings/global" })
        assertTrue(
            requests.any {
                it.url.encodedPath == "/api/v1/admin/rankings/liga/52405025-45e3-41ab-9d45-d80ec42ed98e"
            },
        )
    }

    @Test
    fun everyRankingCallIsARead() = runTest {
        // Regla de producto: la clasificación no se ajusta a mano. Si alguien
        // añadiese una escritura al repositorio, aparecería aquí como un método
        // distinto de GET.
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests, """{"data":[]}""")

        repository.getLeagueLevels()
        repository.getGlobalRanking(RankingPageRequest.of())
        repository.getUserStanding("b69164ad-1851-4dea-ab2a-572be7cef33f")
        repository.searchUsers("premium", RankingPageRequest.of())

        assertTrue(requests.all { it.method.value == "GET" }, "Rankings es de solo lectura.")
    }

    @Test
    fun anEmptyProvisionedTableArrivesAsAConfirmedZeroNotAsAGap() = runTest {
        // Respuesta real esperada hoy: la tabla existe y no tiene filas.
        val repository = repository(mutableListOf(), """{"data":[],"total":0,"limit":50,"offset":0}""")

        val page = (repository.getGlobalRanking(RankingPageRequest.of()) as AppResult.Success).value

        assertEquals(0, page.total)
        assertEquals(RankingPageState.EMPTY, page.state)
    }

    @Test
    fun anOmittedTotalStaysUnknownInsteadOfBecomingZero() = runTest {
        // El backend omite `total` cuando el conteo falla (explicitNulls = false).
        // Si el DTO lo tomase como 0, la pantalla diría "no compite nadie".
        val repository = repository(
            mutableListOf(),
            """{"data":[],"limit":50,"offset":0,"seccionesNoDisponibles":["total"]}""",
        )

        val page = (repository.getGlobalRanking(RankingPageRequest.of()) as AppResult.Success).value

        assertNull(page.total)
        assertEquals(RankingPageState.UNAVAILABLE, page.state)
        assertTrue(page.isUnavailable(RankingSection.TOTAL))
    }

    @Test
    fun aRowIsMappedWithItsSignedEvolutionAndInternalPercentileIntact() = runTest {
        val repository = repository(
            mutableListOf(),
            """
            {"data":[{"usuario_id":"b69164ad-1851-4dea-ab2a-572be7cef33f",
            "nombre_mostrar":"Usuario Premium Test","username":"premium_test",
            "liga_nivel_id":"52405025-45e3-41ab-9d45-d80ec42ed98e","liga_codigo":"liga_50",
            "liga_nombre":"Liga 50","ventana_calculo":50,"preguntas_validas_ranking":64,
            "nota_media_actual":7.4,"nota_referencia_evolucion":8.1,"evolucion_nota":-0.7,
            "preguntas_desde_ultima_evolucion":14,"ranking_liga_posicion":3,
            "ranking_global_posicion":41,"percentil_global":92.5}],"total":1,"limit":50,"offset":0}
            """.trimIndent(),
        )

        val entry = (repository.getGlobalRanking(RankingPageRequest.of()) as AppResult.Success)
            .value.entries.single()

        assertEquals("Usuario Premium Test", entry.label)
        assertEquals("liga_50", entry.leagueCode)
        assertEquals(50, entry.calculationWindow)
        // Una evolución negativa debe llegar negativa: si el mapeo perdiese el
        // signo, un usuario que empeora se leería como que mejora.
        assertEquals(-0.7, entry.evolution)
        assertEquals(14, entry.questionsSinceLastEvolution)
        assertEquals(92.5, entry.globalPercentile)
        assertEquals(41, entry.globalRank)
    }

    @Test
    fun aUserWhoDoesNotCompeteYetIsNotConfusedWithAFailedRead() = runTest {
        val repository = repository(
            mutableListOf(),
            """{"data":{"usuario_id":"b69164ad-1851-4dea-ab2a-572be7cef33f","nombre_mostrar":"Usuario Premium Test"}}""",
        )

        val standing = (
            repository.getUserStanding("b69164ad-1851-4dea-ab2a-572be7cef33f") as AppResult.Success
            ).value

        assertNull(standing.competitive)
        assertTrue(standing.unavailableSections.isEmpty())
        assertEquals("Usuario Premium Test", standing.label)
    }

    @Test
    fun aFailedCompetitiveReadIsCarriedThroughAsUnavailable() = runTest {
        val repository = repository(
            mutableListOf(),
            """{"data":{"usuario_id":"b69164ad-1851-4dea-ab2a-572be7cef33f","seccionesNoDisponibles":["competitivo"]}}""",
        )

        val standing = (
            repository.getUserStanding("b69164ad-1851-4dea-ab2a-572be7cef33f") as AppResult.Success
            ).value

        assertNull(standing.competitive)
        assertTrue(standing.isUnavailable(RankingSection.COMPETITIVO))
    }

    @Test
    fun theFiveSeededLeaguesSurviveTheRoundTripWithTheirWindows() = runTest {
        // Cuerpo con la forma exacta de `ligas_nivel` en producción.
        val repository = repository(
            mutableListOf(),
            """
            {"data":[
            {"id":"52405025-45e3-41ab-9d45-d80ec42ed98e","codigo":"liga_50","nombre":"Liga 50","preguntas_minimas":50,"ventana_calculo":50,"activa":true,"posicion":1},
            {"id":"10e67677-9b0b-4b02-8ee9-347163556b21","codigo":"liga_200","nombre":"Liga 200","preguntas_minimas":200,"ventana_calculo":100,"activa":true,"posicion":2},
            {"id":"59ce3f62-9bd3-4791-80e2-1845ac57b9cc","codigo":"liga_500","nombre":"Liga 500","preguntas_minimas":500,"ventana_calculo":100,"activa":true,"posicion":3},
            {"id":"0223d969-f2d0-4733-8a6b-40692963aa8b","codigo":"liga_800","nombre":"Liga 800","preguntas_minimas":800,"ventana_calculo":100,"activa":true,"posicion":4},
            {"id":"f63c1793-4786-4907-ba43-5d27063442e0","codigo":"liga_1000","nombre":"Liga 1000","preguntas_minimas":1000,"ventana_calculo":100,"activa":true,"posicion":5}]}
            """.trimIndent(),
        )

        val leagues = (repository.getLeagueLevels() as AppResult.Success).value

        assertEquals(5, leagues.size)
        assertEquals(listOf(50, 100, 100, 100, 100), leagues.map { it.calculationWindow })
        assertTrue(leagues.none { it.windowDivergesFromRule })
    }

    @Test
    fun aBlankSearchDoesNotSendAnEmptyQueryParameter() = runTest {
        // `q=` vacío obligaría al backend a distinguir "sin filtro" de "filtra por
        // nada"; se omite y el backend recibe una petición inequívoca.
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests, """{"data":[],"total":0}""")

        repository.searchUsers("   ", RankingPageRequest.of())

        assertNull(requests.single().url.parameters["q"])
    }
}
