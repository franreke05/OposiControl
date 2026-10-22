package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agregación del estado del sistema.
 *
 * La regla que estas pruebas protegen: **lo desconocido no se pinta de verde, y
 * tampoco tiñe de amarillo lo que sí funciona**. Un panel en verde por
 * desconocimiento se mira y se cree; uno en amarillo permanente deja de mirarse.
 */
class AdminSystemStatusTest {

    @Test
    fun everythingOperativeAggregatesToOperative() {
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.OPERATIVE)),
        )
    }

    @Test
    fun oneComponentDownTakesTheWholeSystemDown() {
        // Con Supabase caído el panel no sirve para nada aunque Ktor responda.
        assertEquals(
            SystemStatusLevelWire.DOWN,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.DOWN)),
        )
    }

    @Test
    fun downWinsOverDegraded() {
        assertEquals(
            SystemStatusLevelWire.DOWN,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.DEGRADED, SystemStatusLevelWire.DOWN)),
        )
    }

    @Test
    fun oneDegradedComponentDegradesTheWhole() {
        assertEquals(
            SystemStatusLevelWire.DEGRADED,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.DEGRADED)),
        )
    }

    @Test
    fun knowingNothingIsUnknownNotOperative() {
        // El caso peligroso: sin fuentes, verde sería una mentira creíble.
        assertEquals(SystemStatusLevelWire.UNKNOWN, aggregateSystemStatus(emptyList()))
    }

    @Test
    fun anUnknownComponentAmongOperativeOnesDoesNotClaimEverythingIsFine() {
        assertEquals(
            SystemStatusLevelWire.UNKNOWN,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.UNKNOWN)),
        )
    }

    // --- traducción del chequeo de Supabase ---

    @Test
    fun anUnreachableSupabaseIsDown() {
        assertEquals(
            SystemStatusLevelWire.DOWN,
            supabaseLevelFrom(reachable = false, latencyMs = null, slowThresholdMs = 1500),
        )
    }

    @Test
    fun aFastReadIsOperative() {
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            supabaseLevelFrom(reachable = true, latencyMs = 120, slowThresholdMs = 1500),
        )
    }

    @Test
    fun aSlowButCorrectReadIsDegradedNotDown() {
        // La diferencia importa: responde, pero mal. Quien decide si hay que
        // actuar ahora necesita distinguirlo de una caída.
        assertEquals(
            SystemStatusLevelWire.DEGRADED,
            supabaseLevelFrom(reachable = true, latencyMs = 4000, slowThresholdMs = 1500),
        )
    }

    @Test
    fun exactlyAtTheThresholdIsStillOperative() {
        // El umbral marca "por encima de", no "a partir de": evita que un valor
        // justo en el límite oscile entre verde y amarillo en cada refresco.
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            supabaseLevelFrom(reachable = true, latencyMs = 1500, slowThresholdMs = 1500),
        )
    }

    @Test
    fun reachableWithoutAMeasuredLatencyIsOperativeNotDegraded() {
        // Sin medida no hay evidencia de lentitud; inventar degradación sería
        // tan falso como inventar salud.
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            supabaseLevelFrom(reachable = true, latencyMs = null, slowThresholdMs = 1500),
        )
    }

    // --- honestidad del DTO ---

    @Test
    fun whatDoesNotExistIsNeitherSentNorDeclaredAsAPerpetualGap() {
        val status = statusWith(topology = SystemTopologySnapshot(prometheusResponded = true, replicas = allUp))
        val wire = backendJson.encodeToString(AdminSystemStatusResponse.serializer(), AdminSystemStatusResponse(status))

        // No hay worker, ni fuente de crashes móviles, ni cola de trabajos. Antes viajaban como tres «no instrumentado»
        // fijos: una ausencia que nunca iba a cambiar no es un dato de estado.
        listOf("workerStatus", "mobileCrashes24h", "queueDepth").forEach { field ->
            assertFalse("\"$field\"" in wire, "$field no debe viajar: $wire")
        }
        listOf(
            SystemStatusNotInstrumented.WORKER,
            SystemStatusNotInstrumented.MOBILE_CRASHES,
            SystemStatusNotInstrumented.QUEUE_DEPTH,
        ).forEach { key -> assertFalse(key in status.notInstrumented, key) }
        // Lo que SÍ depende del despliegue se sigue declarando, y llega como null, nunca como 0.
        assertNull(status.cpuPercent)
        assertTrue(SystemStatusNotInstrumented.CPU in status.notInstrumented)
        assertTrue(SystemStatusNotInstrumented.SUPABASE_DISK in status.notInstrumented)
    }

    @Test
    fun anOlderPanelStillDecodesTheNewAnswer() {
        // El panel ya instalado declara `workerStatus` con valor por defecto y no conoce los campos nuevos: los ignora.
        val status = statusWith(topology = SystemTopologySnapshot(prometheusResponded = true, replicas = allUp))
        val wire = backendJson.encodeToString(AdminSystemStatusResponse.serializer(), AdminSystemStatusResponse(status))

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(AdminSystemStatusResponse.serializer(), wire).data
        assertEquals(SystemStatusLevelWire.UNKNOWN, decoded.workerStatus)
        assertEquals(SystemStatusLevelWire.OPERATIVE, decoded.ktorStatus)
    }

    // --- Ktor ya no es una constante: sale de sus réplicas ---

    private fun replica(name: String, profile: String?, up: Boolean, sha: String? = null) =
        ReplicaReading(name = name, profile = profile, up = up, supabaseUp = true.takeIf { up }, sha = sha.takeIf { up })

    private val allUp = listOf(
        replica("bot-1", "bot", up = true, sha = "aaaaaaaaaaaa"),
        replica("bot-2", "bot", up = true, sha = "aaaaaaaaaaaa"),
        replica("control-1", "control", up = true, sha = "aaaaaaaaaaaa"),
    )

    @Test
    fun aReplicaThatCannotReachSupabaseTakesTheGreenAwayEvenIfThePanelReplicaCan() {
        val oneBlind = allUp.map { if (it.name == "bot-1") it.copy(supabaseUp = false) else it }
        val allBlind = allUp.map { it.copy(supabaseUp = false) }
        val nobodySays = allUp.map { it.copy(supabaseUp = null) }

        assertEquals(SystemStatusLevelWire.OPERATIVE, supabaseLevelFromReplicas(allUp))
        assertEquals(SystemStatusLevelWire.DEGRADED, supabaseLevelFromReplicas(oneBlind))
        assertEquals(SystemStatusLevelWire.DOWN, supabaseLevelFromReplicas(allBlind))
        // Sin dato no se inventa ni un verde ni un rojo; una réplica caída no opina.
        assertNull(supabaseLevelFromReplicas(nobodySays))
        assertNull(supabaseLevelFromReplicas(null))
        assertNull(supabaseLevelFromReplicas(allBlind.map { it.copy(up = false) }))

        // La sonda de la réplica del panel dice UP; el conjunto no sale operativo.
        val status = statusWith(topology = SystemTopologySnapshot(prometheusResponded = true, replicas = oneBlind), readiness = "UP")
        assertEquals(SystemStatusLevelWire.DEGRADED, status.overallStatus)
    }

    @Test
    fun allReplicasUpIsOperative() {
        assertEquals(SystemStatusLevelWire.OPERATIVE, ktorLevelFromReplicas(allUp))
    }

    @Test
    fun oneReplicaDownIsDegradedWhicheverItIs() {
        val oneAppReplicaDown = listOf(replica("bot-1", "bot", false), replica("bot-2", "bot", true), replica("control-1", "control", true))
        val controlDown = listOf(replica("bot-1", "bot", true), replica("bot-2", "bot", true), replica("control-1", "control", false))

        assertEquals(SystemStatusLevelWire.DEGRADED, ktorLevelFromReplicas(oneAppReplicaDown))
        assertEquals(SystemStatusLevelWire.DEGRADED, ktorLevelFromReplicas(controlDown))
    }

    @Test
    fun noAppReplicaUpIsDownEvenIfThePanelReplicaAnswers() {
        // El caso que la constante escondía: la réplica del panel responde (por eso se ve esta pantalla) y la app no
        // tiene quién la sirva.
        val appDown = listOf(replica("bot-1", "bot", false), replica("bot-2", "bot", false), replica("control-1", "control", true))
        val everythingDown = appDown.map { it.copy(up = false) }

        assertEquals(SystemStatusLevelWire.DOWN, ktorLevelFromReplicas(appDown))
        assertEquals(SystemStatusLevelWire.DOWN, ktorLevelFromReplicas(everythingDown))
    }

    @Test
    fun withoutProfileLabelsEveryReplicaCountsAsTheApp() {
        // Scrape sin etiqueta `profile` (un solo Ktor): no hay «réplicas de la app» que distinguir.
        assertEquals(SystemStatusLevelWire.DEGRADED, ktorLevelFromReplicas(listOf(replica("a", null, true), replica("b", null, false))))
        assertEquals(SystemStatusLevelWire.DOWN, ktorLevelFromReplicas(listOf(replica("a", null, false))))
    }

    @Test
    fun aSilentPrometheusIsUnknownAndNeverGreen() {
        assertEquals(SystemStatusLevelWire.UNKNOWN, ktorLevelFromReplicas(null))
        assertEquals(SystemStatusLevelWire.UNKNOWN, ktorLevelFromReplicas(emptyList()))

        val status = statusWith(topology = SystemTopologySnapshot(prometheusResponded = false), readiness = "UP")

        assertEquals(SystemStatusLevelWire.UNKNOWN, status.ktorStatus)
        assertEquals(SystemStatusLevelWire.OPERATIVE, status.supabaseStatus)
        // Supabase responde, pero de Ktor no se sabe nada: el conjunto NO puede salir en verde.
        assertEquals(SystemStatusLevelWire.UNKNOWN, status.overallStatus)
        assertEquals(false, status.fuente?.prometheusResponde)
        assertNull(status.replicas)
        assertNull(status.alertas)
        assertNull(status.cuotaSupabase)
        assertNull(status.version)
    }

    @Test
    fun theGatewayDerivesKtorFromItsReplicasInEveryCase() {
        fun ktor(vararg up: Boolean): AdminSystemStatusDto = statusWith(
            topology = SystemTopologySnapshot(
                prometheusResponded = true,
                replicas = listOf(replica("bot-1", "bot", up[0]), replica("bot-2", "bot", up[1]), replica("control-1", "control", up[2])),
            ),
            readiness = "UP",
        )

        assertEquals(SystemStatusLevelWire.OPERATIVE, ktor(true, true, true).ktorStatus)
        assertEquals(SystemStatusLevelWire.OPERATIVE, ktor(true, true, true).overallStatus)
        assertEquals(SystemStatusLevelWire.DEGRADED, ktor(true, false, true).ktorStatus)
        assertEquals(SystemStatusLevelWire.DEGRADED, ktor(true, false, true).overallStatus)
        assertEquals(SystemStatusLevelWire.DOWN, ktor(false, false, true).ktorStatus)
        assertEquals(SystemStatusLevelWire.DOWN, ktor(false, false, true).overallStatus)

        val degraded = ktor(true, false, true)
        assertEquals(listOf("bot-1", "bot-2", "control-1"), degraded.replicas?.map { it.nombre })
        assertEquals(listOf(true, false, true), degraded.replicas?.map { it.arriba })
        assertEquals(true, degraded.fuente?.prometheusResponde)
    }

    // --- Supabase: la sonda cacheada, no una consulta por apertura ---

    @Test
    fun supabaseComesFromTheCachedReadinessProbeAndOpeningTheScreenCostsNoSupabaseRequest() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        var probes = 0
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient(sent::add) { V1StubResponse(200, "[]") },
            supabaseReadiness = { probes++; "DOWN" },
        )

        val status = gateway.getSystemStatus()

        assertEquals(SystemStatusLevelWire.DOWN, status.supabaseStatus)
        assertEquals(SystemStatusLevelWire.DOWN, status.overallStatus)
        assertEquals(1, probes)
        // Antes: un recuento exacto sobre `especialidades` en cada apertura, pagado en tráfico de salida.
        assertTrue(sent.isEmpty(), "el estado del sistema no debe pedir nada a Supabase: ${sent.map { it.uri() }}")
    }

    @Test
    fun theReadinessAnswerMapsToALevelAndAnythingElseIsUnknown() {
        assertEquals(SystemStatusLevelWire.OPERATIVE, supabaseLevelFromReadiness("UP"))
        assertEquals(SystemStatusLevelWire.DOWN, supabaseLevelFromReadiness("DOWN"))
        assertEquals(SystemStatusLevelWire.UNKNOWN, supabaseLevelFromReadiness("UNKNOWN"))
        assertEquals(SystemStatusLevelWire.UNKNOWN, supabaseLevelFromReadiness(""))
        // Una sonda que lanza tampoco tumba el endpoint ni se lee como «responde».
        assertEquals(SystemStatusLevelWire.UNKNOWN, statusWith(readinessProbe = { error("boom") }).supabaseStatus)
    }

    // --- alertas ---

    @Test
    fun firingAlertsTravelAsTheyAreAndNoAlertsIsAnEmptyListNotAMissingOne() {
        val firing = statusWith(
            topology = SystemTopologySnapshot(
                prometheusResponded = true,
                replicas = allUp,
                alerts = listOf(AlertReading("KtorInstanceDown", "critical", replica = "bot-2"), AlertReading("SupabaseEgressHigh", "warning")),
            ),
        )
        assertEquals(
            listOf(
                AdminSystemAlertDto("KtorInstanceDown", "critical", resumen = null, replica = "bot-2"),
                AdminSystemAlertDto("SupabaseEgressHigh", "warning"),
            ),
            firing.alertas,
        )

        val none = statusWith(topology = SystemTopologySnapshot(prometheusResponded = true, replicas = allUp, alerts = emptyList()))
        assertEquals(emptyList(), none.alertas)
        val wire = backendJson.encodeToString(AdminSystemStatusResponse.serializer(), AdminSystemStatusResponse(none))
        assertTrue("\"alertas\":[]" in wire, "«ninguna alerta» tiene que viajar, no confundirse con «no se pudo leer»: $wire")
    }

    // --- cuota de Supabase y proyección ---

    @Test
    fun withAWeekOfHistoryTheProjectionIsTheDailyAverageOfTheWeekTimesThirty() {
        val projection = projectMonthlySupabaseEgressBytes(bytes24h = 900_000_000, bytes7d = 700_000_000, hasSevenDaysOfHistory = true)

        // 700 MB en 7 días = 100 MB/día = 3 GB al mes. El día de 900 MB no manda: lo absorbe la semana.
        assertEquals(SupabaseEgressProjection(3_000_000_000, SupabaseQuotaWire.BASE_7D), projection)
    }

    @Test
    fun withoutAWeekOfHistoryTheProjectionFallsBackToTheLastDay() {
        // `increase(...[7d])` sobre una serie de dos días devuelve dos días: dividirlo entre 7 infraestimaría.
        assertEquals(
            SupabaseEgressProjection(3_000_000_000, SupabaseQuotaWire.BASE_24H),
            projectMonthlySupabaseEgressBytes(bytes24h = 100_000_000, bytes7d = 180_000_000, hasSevenDaysOfHistory = false),
        )
        assertEquals(
            SupabaseEgressProjection(3_000_000_000, SupabaseQuotaWire.BASE_24H),
            projectMonthlySupabaseEgressBytes(bytes24h = 100_000_000, bytes7d = null, hasSevenDaysOfHistory = true),
        )
    }

    @Test
    fun aMeasuredZeroProjectsToZeroAndNoMeasurementProjectsToNothing() {
        assertEquals(
            SupabaseEgressProjection(0, SupabaseQuotaWire.BASE_24H),
            projectMonthlySupabaseEgressBytes(bytes24h = 0, bytes7d = null, hasSevenDaysOfHistory = false),
        )
        assertNull(projectMonthlySupabaseEgressBytes(bytes24h = null, bytes7d = null, hasSevenDaysOfHistory = false))
        assertNull(projectMonthlySupabaseEgressBytes(bytes24h = null, bytes7d = 5, hasSevenDaysOfHistory = false))
    }

    @Test
    fun theQuotaBlockCarriesBothWindowsTheProjectionTheLimitAndTheBreakdown() {
        val quota = statusWith(
            topology = SystemTopologySnapshot(
                prometheusResponded = true,
                replicas = allUp,
                egress = SupabaseEgressReading(
                    bytes24h = 120_000_000,
                    bytes7d = 700_000_000,
                    byTarget24h = mapOf("rest" to 100_000_000L, "storage" to 20_000_000L),
                    byTarget7d = mapOf("rest" to 600_000_000L, "auth" to 100_000_000L),
                    hasSevenDaysOfHistory = true,
                ),
            ),
        ).cuotaSupabase

        assertEquals(120_000_000, quota?.bytes24h)
        assertEquals(700_000_000, quota?.bytes7d)
        assertEquals(3_000_000_000, quota?.proyeccionMensualBytes)
        assertEquals(SupabaseQuotaWire.BASE_7D, quota?.proyeccionBase)
        assertEquals(250_000_000_000, quota?.limiteMensualBytes)
        assertEquals(
            listOf(
                // Un destino sin lectura en una ventana conserva null en ESA ventana: no es un cero.
                AdminSupabaseQuotaTargetDto("auth", bytes24h = null, bytes7d = 100_000_000),
                AdminSupabaseQuotaTargetDto("rest", bytes24h = 100_000_000, bytes7d = 600_000_000),
                AdminSupabaseQuotaTargetDto("storage", bytes24h = 20_000_000, bytes7d = null),
            ),
            quota?.porDestino,
        )
        // El límite viaja SIEMPRE: lo decide el servidor y el panel no debe suponerlo.
        val wire = backendJson.encodeToString(AdminSupabaseQuotaDto.serializer(), quota!!)
        assertTrue("\"limiteMensualBytes\":250000000000" in wire, wire)
    }

    // --- versión desplegada ---

    @Test
    fun replicasOnTheSameShaAgreeAndThatShaIsTheVersion() {
        assertEquals(AdminSystemVersionDto(sha = "aaaaaaaaaaaa", replicasDifieren = false), deployedVersionOf(allUp))
    }

    @Test
    fun aHalfFinishedDeployIsReportedWithTheMostCommonSha() {
        val halfDeployed = listOf(
            replica("bot-1", "bot", true, sha = "bbbbbbbbbbbb"),
            replica("bot-2", "bot", true, sha = "aaaaaaaaaaaa"),
            replica("control-1", "control", true, sha = "aaaaaaaaaaaa"),
        )

        assertEquals(AdminSystemVersionDto(sha = "aaaaaaaaaaaa", replicasDifieren = true), deployedVersionOf(halfDeployed))
        assertEquals(true, statusWith(topology = SystemTopologySnapshot(true, replicas = halfDeployed)).version?.replicasDifieren)
    }

    @Test
    fun anUpReplicaThatPublishesNoVersionNextToOneThatDoesAlsoDiffers() {
        // Imagen anterior a `build_info` conviviendo con una nueva: también es un despliegue a medias.
        val mixed = listOf(replica("bot-1", "bot", true, sha = "aaaaaaaaaaaa"), replica("bot-2", "bot", true, sha = null))

        assertEquals(AdminSystemVersionDto(sha = "aaaaaaaaaaaa", replicasDifieren = true), deployedVersionOf(mixed))
    }

    @Test
    fun aDownReplicaIsNotADifferentVersionAndNoReadingIsNoVersion() {
        val oneDown = listOf(replica("bot-1", "bot", true, sha = "aaaaaaaaaaaa"), replica("bot-2", "bot", false))

        assertEquals(AdminSystemVersionDto(sha = "aaaaaaaaaaaa", replicasDifieren = false), deployedVersionOf(oneDown))
        assertEquals(AdminSystemVersionDto(sha = null, replicasDifieren = false), deployedVersionOf(listOf(replica("a", null, true))))
        assertNull(deployedVersionOf(null))
    }

    @Test
    fun theStartTimeTravelsAsAnInstantAndAMissingOneStaysMissing() {
        val status = statusWith(
            topology = SystemTopologySnapshot(
                prometheusResponded = true,
                replicas = listOf(
                    ReplicaReading("bot-1", "bot", up = true, startedAtEpochSeconds = 1_760_000_000.5),
                    ReplicaReading("bot-2", "bot", up = true),
                ),
            ),
        )

        assertEquals("2025-10-09T08:53:20.500Z", status.replicas?.get(0)?.arrancadaEn)
        assertNull(status.replicas?.get(1)?.arrancadaEn)
    }

    // --- fuente ---

    @Test
    fun aMetricFromPrometheusAlsoProvesItAnswered() {
        val status = statusWith(
            topology = SystemTopologySnapshot(prometheusResponded = false),
            metrics = SystemMetricsSnapshot(cpuPercent = 12.0, ramPercent = null, apiLatencyMs = null, errors24h = null),
        )

        assertEquals(true, status.fuente?.prometheusResponde)
    }

    @Test
    fun aTopologyProviderThatThrowsIsASilentPrometheusNotA500() {
        val status = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({}) { V1StubResponse(200, "[]") },
            systemTopology = SystemTopologyProvider { error("boom") },
        ).getSystemStatus()

        assertEquals(SystemStatusLevelWire.UNKNOWN, status.ktorStatus)
        assertEquals(false, status.fuente?.prometheusResponde)
    }

    /** Copia literal del `Json` de `Application.kt`. */
    private val backendJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    }

    private fun statusWith(
        topology: SystemTopologySnapshot = SystemTopologySnapshot(prometheusResponded = false),
        readiness: String = "UP",
        metrics: SystemMetricsSnapshot = SystemMetricsSnapshot(null, null, null, null),
        readinessProbe: () -> String = { readiness },
    ): AdminSystemStatusDto = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({}) { V1StubResponse(200, "[]") },
        systemMetrics = SystemMetricsProvider { metrics },
        systemTopology = SystemTopologyProvider { topology },
        supabaseReadiness = readinessProbe,
    ).getSystemStatus()

    /** El cupo de Supabase sale del entorno (plan Pro por defecto); un valor roto nunca deja el cupo sin limite. */
    @Test
    fun egressLimitComesFromTheEnvironmentWithTheProPlanAsDefault() {
        assertEquals(250_000_000_000L, supabaseMonthlyEgressLimitBytes(null))
        assertEquals(250_000_000_000L, supabaseMonthlyEgressLimitBytes(""))
        assertEquals(250_000_000_000L, supabaseMonthlyEgressLimitBytes("abc"))
        assertEquals(250_000_000_000L, supabaseMonthlyEgressLimitBytes("0"))
        assertEquals(250_000_000_000L, supabaseMonthlyEgressLimitBytes("-5"))
        assertEquals(5_000_000_000L, supabaseMonthlyEgressLimitBytes(" 5 "))
        assertEquals(500_000_000_000L, supabaseMonthlyEgressLimitBytes("500"))
    }
}
