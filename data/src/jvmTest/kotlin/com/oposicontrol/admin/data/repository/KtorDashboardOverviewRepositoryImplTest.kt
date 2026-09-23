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
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardMetricKeys
import com.oposicontrol.admin.domain.model.DashboardOverview
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
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
import kotlin.test.assertTrue

/**
 * Mapeo del Dashboard V2: lo que llega por el cable y lo que acaba en pantalla.
 *
 * El foco es el mismo que en el backend, con una prueba de más que no existía en
 * ninguna otra parte: la de **mutación**. Si alguien sustituye un `null` por un
 * `0` en este mapeo —el defecto original del dashboard, literalmente un
 * `catch { 0 }`— hay un test que falla. Sin ella, la regla "aquí no hay un solo
 * `?: 0`" sería otra vez sólo un comentario.
 */
class KtorDashboardOverviewRepositoryImplTest {

    // -----------------------------------------------------------------
    // Prueba de mutación: null nunca puede volverse 0
    // -----------------------------------------------------------------

    @Test
    fun aMetricWithNoValueAndNoExplanationArrivesAsNoDataAndNeverAsZero() = runTest {
        // ESTA ES LA PRUEBA DE MUTACIÓN. `draftExams` no viene en el cuerpo y
        // tampoco está nombrado en `unavailableMetrics` (el backend puede omitir
        // el campo: su Json usa `explicitNulls = false`). Si alguien escribiese
        // `mapping.count(DRAFT_EXAMS, draftExams ?: 0)`, esta métrica pasaría a
        // AVAILABLE con valor 0 y el panel enseñaría "0 borradores" sin que nadie
        // lo haya contado. Las dos asserts siguientes son las que lo impiden.
        val overview = overview(
            """
            {
              "attention": {
                "reportsPending": 0,
                "generatedAt": "2026-09-02T10:00:00Z",
                "unavailableMetrics": [],
                "notInstrumented": []
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val draftExams = overview.blocks.getValue(DashboardBlock.ATTENTION)
            .metric(DashboardMetricKeys.DRAFT_EXAMS)
        assertEquals(
            AnalyticsMetricStatus.NO_DATA,
            draftExams?.status,
            "Sin valor y sin explicación se falla cerrado: NO_DATA, jamás AVAILABLE con 0.",
        )
        assertNull(draftExams?.value, "Un hueco no tiene valor. Un `?: 0` en el mapeo rompería aquí.")
    }

    @Test
    fun aLegitimateZeroArrivesAsAnAvailableZeroAndNotAsAHole() = runTest {
        // La otra mitad de la pinza: si alguien "arreglase" el test anterior
        // mapeando todo a null, este fallaría. Un cero contado es un dato.
        val overview = overview(
            """
            {
              "attention": {
                "reportsPending": 0,
                "generatedAt": "2026-09-02T10:00:00Z",
                "unavailableMetrics": [],
                "notInstrumented": []
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val reportsPending = overview.blocks.getValue(DashboardBlock.ATTENTION)
            .metric(DashboardMetricKeys.REPORTS_PENDING)
        assertEquals(AnalyticsMetricStatus.AVAILABLE, reportsPending?.status)
        assertEquals(AnalyticsValue.Count(0), reportsPending?.value)
    }

    @Test
    fun aNumberThatArrivesWithItsKeyMarkedUnavailableIsDiscarded() = runTest {
        // El backend puede mandar relleno y a la vez declarar la clave. Gana la
        // declaración: es la regla estricta de AnalyticsMetricPolicy, y es lo que
        // impide que un número que el servidor sabe falso llegue a pintarse.
        val overview = overview(
            """
            {
              "content": {
                "totalExams": 42,
                "generatedAt": "2026-09-02T10:00:00Z",
                "unavailableMetrics": ["totalExams"],
                "notInstrumented": []
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val totalExams = overview.blocks.getValue(DashboardBlock.CONTENT)
            .metric(DashboardMetricKeys.TOTAL_EXAMS)
        assertEquals(AnalyticsMetricStatus.NO_DATA, totalExams?.status)
        assertNull(totalExams?.value, "El 42 llegó, pero el backend lo declaró inválido: no se pinta.")
    }

    // -----------------------------------------------------------------
    // Franjas: pedida, ausente, fallida
    // -----------------------------------------------------------------

    @Test
    fun aBlockThatWasNotRequestedIsAbsentAndAFailedBlockIsNamed() = runTest {
        val overview = overview(
            """
            {
              "content": {
                "totalExams": 11,
                "generatedAt": "2026-09-02T10:00:00Z",
                "unavailableMetrics": [],
                "notInstrumented": []
              },
              "failedBlocks": ["activity"],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals(setOf(DashboardBlock.CONTENT), overview.blocks.keys)
        assertEquals(
            setOf(DashboardBlock.ACTIVITY),
            overview.failedBlocks,
            "'No lo pediste' y 'lo pedí y se rompió' no pueden llegar iguales a la pantalla.",
        )
        assertTrue(DashboardBlock.ECONOMY !in overview.failedBlocks, "No pedida no es fallida.")
    }

    @Test
    fun everyBlockOfAFullResponseIsMapped() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(FULL_RESPONSE, record = requests)

        val overview = assertIs<AppResult.Success<DashboardOverview>>(repository.getOverview()).value

        assertEquals(DashboardBlock.entries.toSet(), overview.blocks.keys)
        assertEquals(1, requests.size, "El panel entero es UNA petición, no cinco más una por examen.")
        assertEquals("/api/v1/admin/dashboard/overview", requests.single().url.encodedPath)
        assertNull(
            requests.single().url.parameters["sections"],
            "Pedir todas las franjas se expresa omitiendo el parámetro, no mandándolo vacío.",
        )
    }

    @Test
    fun requestingASubsetSendsExactlyThoseSectionNames() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(FULL_RESPONSE, record = requests)

        repository.getOverview(setOf(DashboardBlock.SYSTEM, DashboardBlock.ATTENTION))

        assertEquals("system,attention", requests.single().url.parameters["sections"])
    }

    @Test
    fun aFailedRequestIsAFailureAndNotAnEmptyPanel() = runTest {
        val repository = repository("""{"error":"nope"}""", status = HttpStatusCode.InternalServerError)

        val result = repository.getOverview()

        assertIs<AppResult.Failure>(result, "Un panel vacío sería una pantalla de ceros que nadie calculó.")
    }

    // -----------------------------------------------------------------
    // Cronología
    // -----------------------------------------------------------------

    @Test
    fun theTimelineIsSortedNewestFirstHereAndNotTrustedToArriveSorted() = runTest {
        // El consumidor hace `take(5)` para enseñar "lo más reciente". Si el orden
        // fuese sólo una promesa del backend (`order=creado_en.desc`), tocar esa
        // query enseñaría cinco filas cualesquiera SIN que nada fallara. Este test
        // recibe las entradas desordenadas a propósito: sólo pasa si el mapeador
        // ordena, no si se limita a conservar el orden del cable.
        val overview = overview(
            """
            {
              "activity": {
                "entries": [
                  {"id":"mid","action":"b","occurredAt":"2026-09-01T12:00:00Z"},
                  {"id":"oldest","action":"c","occurredAt":"2026-08-30T09:00:00Z"},
                  {"id":"newest","action":"a","occurredAt":"2026-09-02T08:30:00Z"}
                ],
                "generatedAt": "2026-09-02T10:00:00Z"
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("newest", "mid", "oldest"),
            overview.blocks.getValue(DashboardBlock.ACTIVITY).activity.map { it.id },
            "La cronología se ordena aquí: quien la consume no puede depender de una promesa no declarada.",
        )
    }

    @Test
    fun theDropCountFromKtorSurvivesToTheReportSoAnEmptyListCanBeExplained() = runTest {
        // El contrato del dominio lo fija: con `droppedActivityEntries > 0` una
        // lista vacía NO puede rotularse «todavía no ha pasado nada». Si este
        // número se perdiera en el mapeo, la pantalla volvería a no poder
        // distinguir «leí ocho filas ilegibles» de «la tabla está vacía».
        val overview = overview(
            """
            {
              "activity": {
                "entries": [],
                "droppedEntries": 4,
                "generatedAt": "2026-09-02T10:00:00Z"
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val report = overview.blocks.getValue(DashboardBlock.ACTIVITY)
        assertTrue(report.activity.isEmpty())
        assertEquals(4, report.droppedActivityEntries)
    }

    @Test
    fun entriesDroppedOnThisSideAreAddedToTheOnesKtorAlreadyDropped() = runTest {
        // Contar sólo las del backend publicaría un número que no es el que dice
        // ser: el descarte de este lado existe y debe sumar al mismo contador.
        val overview = overview(
            """
            {
              "activity": {
                "entries": [
                  {"id":"a1","action":"examen_publicado","occurredAt":"2026-09-01T10:15:00Z"},
                  {"id":"a2","action":"fecha_basura","occurredAt":"ayer"}
                ],
                "droppedEntries": 3,
                "generatedAt": "2026-09-02T10:00:00Z"
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val report = overview.blocks.getValue(DashboardBlock.ACTIVITY)
        assertEquals(listOf("a1"), report.activity.map { it.id })
        assertEquals(4, report.droppedActivityEntries, "3 descartadas por Ktor + 1 descartada aquí.")
    }

    @Test
    fun aCleanTimelineReportsNoDrops() = runTest {
        val report = overview(FULL_RESPONSE).blocks.getValue(DashboardBlock.ACTIVITY)

        assertEquals(1, report.activity.size)
        assertEquals(0, report.droppedActivityEntries, "Nada descartado: el contador no se inventa un hueco.")
    }

    @Test
    fun anActivityEntryWithoutAUsableTimestampIsDropped() = runTest {
        val overview = overview(
            """
            {
              "activity": {
                "entries": [
                  {"id":"a1","action":"examen_publicado","targetTable":"examenes","targetId":"e1",
                   "occurredAt":"2026-09-01T10:15:00Z"},
                  {"id":"a2","action":"sin_fecha"},
                  {"id":"a3","action":"fecha_basura","occurredAt":"ayer"}
                ],
                "generatedAt": "2026-09-02T10:00:00Z"
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val entries = overview.blocks.getValue(DashboardBlock.ACTIVITY).activity
        assertEquals(listOf("a1"), entries.map { it.id })
        assertEquals(
            Instant.parse("2026-09-01T10:15:00Z"),
            entries.single().occurredAt,
            "La fecha es la del hecho, nunca la de la lectura.",
        )
    }

    // -----------------------------------------------------------------
    // Huecos declarados y salud
    // -----------------------------------------------------------------

    /**
     * Un servidor que todavía DECLARA las sumas como huecos (anterior a PC-11) se sigue
     * mapeando bien: huecos con motivo, fuera del panel. Es la compatibilidad hacia
     * atrás del contrato, no el estado actual del backend.
     */
    @Test
    fun moneyGapsTravelAsNotInstrumentedWithTheirReasonAndAreNotRendered() = runTest {
        val overview = overview(FULL_RESPONSE)
        val economy = overview.blocks.getValue(DashboardBlock.ECONOMY)

        listOf(
            DashboardMetricKeys.GROSS_REVENUE_CENTS,
            DashboardMetricKeys.TICKETS_IN_CIRCULATION,
        ).forEach { key ->
            val metric = economy.metric(key)
            assertEquals(AnalyticsMetricStatus.NOT_INSTRUMENTED, metric?.status, "'$key' es un hueco estructural.")
            assertNull(metric?.value)
            assertTrue(metric?.reason.orEmpty().isNotBlank(), "'$key' debe decir qué vista o RPC hace falta.")
        }
        // Se declaran, pero no ocupan sitio en el panel: reintentar no las arregla.
        assertTrue(economy.renderable.none { it.key == DashboardMetricKeys.GROSS_REVENUE_CENTS })
        // Un NO_DATA sí se pinta: reintentar puede arreglarlo y el operador debe saber que le falta.
        assertEquals(3, economy.renderable.size)
    }

    // -----------------------------------------------------------------
    // PC-11 · Economía con número · PC-04 · Leída sin dato ≠ lectura fallida
    // -----------------------------------------------------------------

    @Test
    fun theEconomyTotalsArriveAsMoneyAndAsACountAndAreRendered() = runTest {
        val overview = overview(
            """
            {
              "economy": {
                "subscriptionsActive": 0,
                "subscriptionsExpiring30d": 0,
                "commercialProductsActive": 4,
                "grossRevenueCents": 0,
                "ticketsInCirculation": 21475,
                "generatedAt": "2026-09-17T08:15:13Z",
                "unavailableMetrics": [],
                "failedMetrics": [],
                "notInstrumented": []
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-17T08:15:13Z"
            }
            """.trimIndent(),
        )

        val economy = overview.blocks.getValue(DashboardBlock.ECONOMY)
        val revenue = economy.metric(DashboardMetricKeys.GROSS_REVENUE_CENTS)
        assertEquals(AnalyticsMetricStatus.AVAILABLE, revenue?.status)
        assertEquals(AnalyticsValue.MoneyCents(0), revenue?.value, "Un 0 € leído es un dato.")
        assertEquals(
            AnalyticsValue.Count(21475),
            economy.metric(DashboardMetricKeys.TICKETS_IN_CIRCULATION)?.value,
            "Los OposiTickets son un conteo: como dinero saldrían «214,75 €».",
        )
        assertEquals(5, economy.renderable.size, "Con número, las dos métricas ocupan su sitio en el panel.")
    }

    /**
     * Las dos formas de no tener número, y la que no se puede confundir con un cero:
     * `activeUsers30d` no se pudo LEER (va en `failedMetrics`) y `globalErrorRate` se
     * leyó y no hay nada que medir. Las dos son NO_DATA; sólo la primera lleva
     * `readFailed`, que es lo que le deja a la pantalla decir «no se pudo leer».
     */
    @Test
    fun aFailedReadIsFlaggedAndANoDataReadIsNot() = runTest {
        val overview = overview(
            """
            {
              "pulse": {
                "attemptsLast30d": 0,
                "publishedExams": 1,
                "generatedAt": "2026-09-17T08:15:13Z",
                "unavailableMetrics": ["activeUsers30d", "globalErrorRate"],
                "failedMetrics": ["activeUsers30d"],
                "notInstrumented": []
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-17T08:15:13Z"
            }
            """.trimIndent(),
        )

        val pulse = overview.blocks.getValue(DashboardBlock.PULSE)
        val activeUsers = pulse.metric(DashboardMetricKeys.ACTIVE_USERS_30D)
        val errorRate = pulse.metric(DashboardMetricKeys.GLOBAL_ERROR_RATE)
        assertEquals(AnalyticsMetricStatus.NO_DATA, activeUsers?.status)
        assertEquals(true, activeUsers?.readFailed)
        assertNull(activeUsers?.value)
        assertEquals(AnalyticsMetricStatus.NO_DATA, errorRate?.status)
        assertEquals(false, errorRate?.readFailed)
    }

    /** Una clave en `failedMetrics` nunca sale con número, aunque el servidor lo mandara. */
    @Test
    fun aNumberForAKeyDeclaredFailedIsDiscarded() = runTest {
        val overview = overview(
            """
            {
              "content": {
                "totalExams": 42,
                "generatedAt": "2026-09-17T08:15:13Z",
                "unavailableMetrics": [],
                "failedMetrics": ["totalExams"],
                "notInstrumented": []
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-17T08:15:13Z"
            }
            """.trimIndent(),
        )

        val totalExams = overview.blocks.getValue(DashboardBlock.CONTENT).metric(DashboardMetricKeys.TOTAL_EXAMS)
        assertEquals(AnalyticsMetricStatus.NO_DATA, totalExams?.status)
        assertEquals(true, totalExams?.readFailed)
        assertNull(totalExams?.value)
    }

    // -----------------------------------------------------------------
    // PC-09 · Presupuesto de tiempo propio
    // -----------------------------------------------------------------

    /**
     * El panel pide 25 s para SU petición. Con el presupuesto por defecto del cliente
     * (antes 6 s) una franja lenta tumbaba la respuesta entera y la degradación por
     * franja nunca llegaba a verse.
     */
    @Test
    fun theOverviewRequestCarriesItsOwnTwentyFiveSecondBudget() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(FULL_RESPONSE, record = requests)

        repository.getOverview()

        assertEquals(
            25_000L,
            requests.single().getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis,
        )
    }

    @Test
    fun healthKeepsTheSameLevelVocabularyAsTheSystemStatusScreen() = runTest {
        val health = overview(FULL_RESPONSE).blocks.getValue(DashboardBlock.SYSTEM).health

        assertEquals(SystemStatusLevel.DEGRADED, health?.overall)
        assertEquals(SystemStatusLevel.OPERATIVE, health?.ktor)
        assertEquals(SystemStatusLevel.DEGRADED, health?.supabase)
        assertTrue("workerStatus" in health?.notInstrumented.orEmpty())
        assertEquals(
            1800L,
            health?.supabaseLatencyMs,
            "La sonda a Supabase es una medición real ya hecha por Ktor: descartarla es perder un dato.",
        )
        assertTrue(
            "apiLatencyMs" in health?.notInstrumented.orEmpty(),
            "La latencia GENERAL de la API sigue sin instrumentar: la sonda a Supabase no es lo mismo.",
        )
    }

    @Test
    fun aMissingSupabaseLatencyStaysNullAndIsNeverReadAsZeroMilliseconds() = runTest {
        // El backend omite el campo cuando la sonda no llegó a completarse (y su
        // Json usa explicitNulls=false, así que ni siquiera viaja como null). Un 0
        // se leería como "Supabase responde instantáneamente", justo lo contrario
        // de "no hubo respuesta que cronometrar".
        val overview = overview(
            """
            {
              "system": {
                "health": {
                  "overallStatus": "DOWN", "ktorStatus": "OPERATIVE", "supabaseStatus": "DOWN",
                  "updatedAt": "2026-09-02T10:00:00Z", "notInstrumented": ["apiLatencyMs"]
                },
                "generatedAt": "2026-09-02T10:00:00Z"
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val health = overview.blocks.getValue(DashboardBlock.SYSTEM).health
        assertEquals(SystemStatusLevel.DOWN, health?.supabase)
        assertNull(health?.supabaseLatencyMs, "Sin sonda completada no hay latencia; 0 ms sería una mentira.")
    }

    @Test
    fun anUnknownHealthLevelIsUnknownAndNeverOperative() = runTest {
        val overview = overview(
            """
            {
              "system": {
                "health": {
                  "overallStatus": "VERDE",
                  "ktorStatus": "",
                  "supabaseStatus": "OPERATIVE",
                  "updatedAt": "",
                  "notInstrumented": []
                },
                "generatedAt": "2026-09-02T10:00:00Z"
              },
              "failedBlocks": [],
              "generatedAt": "2026-09-02T10:00:00Z"
            }
            """.trimIndent(),
        )

        val health = overview.blocks.getValue(DashboardBlock.SYSTEM).health
        assertEquals(SystemStatusLevel.UNKNOWN, health?.overall, "Un nivel que no se entiende no se pinta en verde.")
        assertEquals(SystemStatusLevel.UNKNOWN, health?.ktor)
        assertNull(health?.updatedAt, "Sin marca de tiempo no se inventa una.")
    }

    // -----------------------------------------------------------------
    // Infraestructura
    // -----------------------------------------------------------------

    private suspend fun overview(body: String): DashboardOverview =
        assertIs<AppResult.Success<DashboardOverview>>(repository(body).getOverview()).value

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
    ): KtorDashboardOverviewRepositoryImpl {
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
        return KtorDashboardOverviewRepositoryImpl(
            client,
            appConfig(),
            sessionLocalDataSource(),
            NetworkResultAdapter(NoOpLogger),
        )
    }

    private companion object {
        val FULL_RESPONSE = """
        {
          "system": {
            "health": {
              "overallStatus": "DEGRADED",
              "ktorStatus": "OPERATIVE",
              "supabaseStatus": "DEGRADED",
              "updatedAt": "2026-09-02T10:00:00Z",
              "supabaseLatencyMs": 1800,
              "notInstrumented": ["workerStatus","cpuPercent","apiLatencyMs"]
            },
            "generatedAt": "2026-09-02T10:00:00Z"
          },
          "attention": {
            "reportsPending": 3,
            "questionFeedbackPending": 0,
            "draftExams": 8,
            "subscriptionsExpiring7d": 2,
            "generatedAt": "2026-09-02T10:00:00Z",
            "unavailableMetrics": [],
            "notInstrumented": []
          },
          "pulse": {
            "activeUsers30d": 120,
            "attemptsLast30d": 400,
            "publishedExams": 3,
            "globalErrorRate": 31.5,
            "generatedAt": "2026-09-02T10:00:00Z",
            "unavailableMetrics": [],
            "notInstrumented": []
          },
          "content": {
            "totalExams": 11,
            "totalQuestions": 900,
            "totalResources": 40,
            "importBatches": 5,
            "generatedAt": "2026-09-02T10:00:00Z",
            "unavailableMetrics": [],
            "notInstrumented": []
          },
          "activity": {
            "entries": [
              {"id":"a1","action":"examen_publicado","targetTable":"examenes","targetId":"e1",
               "occurredAt":"2026-09-01T10:15:00Z"}
            ],
            "generatedAt": "2026-09-02T10:00:00Z"
          },
          "economy": {
            "subscriptionsActive": 7,
            "subscriptionsExpiring30d": 2,
            "commercialProductsActive": 4,
            "generatedAt": "2026-09-02T10:00:00Z",
            "unavailableMetrics": ["grossRevenueCents","ticketsInCirculation"],
            "notInstrumented": [
              {"key":"grossRevenueCents","reason":"Necesita SUM(precio_centimos) sobre compras_usuario: vista v_ingresos_brutos."},
              {"key":"ticketsInCirculation","reason":"Necesita SUM(saldo_tickets) sobre carteras_usuario: vista v_opositickets_en_circulacion."}
            ]
          },
          "failedBlocks": [],
          "generatedAt": "2026-09-02T10:00:00Z"
        }
        """.trimIndent()
    }
}
