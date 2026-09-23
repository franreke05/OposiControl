package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El contrato de honestidad, ejercitado de punta a punta contra un upstream
 * simulado.
 *
 * Los tests puros de `AdminAnalyticsSupportTest` prueban las piezas; aquí se
 * prueba lo que realmente le importa a quien mira el panel: que **en la misma
 * respuesta** convivan un cero real y una métrica ilegible sin parecerse, y que
 * los conteos no descarguen filas.
 */
class AdminAnalyticsGatewayTest {

    // -----------------------------------------------------------------
    // Honestidad de las métricas
    // -----------------------------------------------------------------

    @Test
    fun aRealZeroAndAnUnreadableMetricArriveDifferentInTheSameResponse() {
        val gateway = gatewayWith { uri ->
            when {
                // perfiles_usuario: 3 filas en total, ninguna invitada -> cero real.
                uri.contains("perfiles_usuario") && uri.contains("es_invitado") -> countResponse("*/0")
                uri.contains("perfiles_usuario") && uri.contains("onboarding_completado") -> countResponse("*/0")
                uri.contains("perfiles_usuario") -> countResponse("0-0/3")
                // eventos_app total: el upstream falla.
                uri.contains("eventos_app") && !uri.contains("creado_en") -> V1StubResponse(500, "boom", null)
                uri.contains("eventos_app") -> countResponse("*/0")
                uri.contains("sesiones_estudio") && uri.contains("finalizada_en") -> countResponse("0-0/6")
                uri.contains("sesiones_estudio") && uri.contains("iniciada_en") -> countResponse("0-0/4")
                uri.contains("sesiones_estudio") -> countResponse("0-0/10")
                else -> countResponse("*/0")
            }
        }

        val producto = gateway.producto()

        assertEquals(3, producto.totalUsers)
        // Cero real: llega como 0 y NO se anuncia como no disponible.
        assertEquals(0, producto.guestUsers)
        assertTrue(AnalyticsMetricKey.GUEST_USERS !in producto.unavailableMetrics)
        // Fallo de lectura: llega como null y SÍ se anuncia.
        assertNull(producto.totalAppEvents)
        assertTrue(AnalyticsMetricKey.TOTAL_APP_EVENTS in producto.unavailableMetrics)
        // Y en la misma respuesta, otra métrica de la misma tabla con cero real.
        assertEquals(0, producto.appEventsLast30d)
        assertTrue(AnalyticsMetricKey.APP_EVENTS_LAST_30D !in producto.unavailableMetrics)
    }

    @Test
    fun aDerivedMetricIsPublishedOnlyWhenEverySourceCountIsReadable() {
        val gateway = gatewayWith { uri ->
            when {
                uri.contains("sesiones_estudio") && uri.contains("finalizada_en") -> countResponse("0-0/6")
                uri.contains("sesiones_estudio") && uri.contains("iniciada_en") -> countResponse("0-0/4")
                uri.contains("sesiones_estudio") -> countResponse("0-0/10")
                uri.contains("perfiles_usuario") && uri.contains("onboarding_completado") -> countResponse("0-0/1")
                uri.contains("perfiles_usuario") -> countResponse("0-0/4")
                else -> countResponse("*/0")
            }
        }

        val producto = gateway.producto()

        assertEquals(4, producto.abandonedStudySessions, "10 sesiones menos 6 terminadas = 4 abandonadas.")
        assertEquals(60.0, producto.studySessionCompletionRate)
        assertEquals(25.0, producto.onboardingCompletionRate)
        assertTrue(AnalyticsMetricKey.ABANDONED_STUDY_SESSIONS !in producto.unavailableMetrics)
    }

    @Test
    fun aDerivedMetricDisappearsWhenOneOfItsSourcesCannotBeRead() {
        val gateway = gatewayWith { uri ->
            when {
                // El total de sesiones falla; las terminadas se leen bien.
                uri.contains("sesiones_estudio") && uri.contains("finalizada_en") -> countResponse("0-0/6")
                uri.contains("sesiones_estudio") && uri.contains("iniciada_en") -> countResponse("0-0/4")
                uri.contains("sesiones_estudio") -> V1StubResponse(503, "upstream down", null)
                else -> countResponse("*/0")
            }
        }

        val producto = gateway.producto()

        assertNull(producto.totalStudySessions)
        assertNull(
            producto.abandonedStudySessions,
            "Restar contra un total desconocido daría un número inventado, no una derivada.",
        )
        assertNull(producto.studySessionCompletionRate)
        assertTrue(AnalyticsMetricKey.ABANDONED_STUDY_SESSIONS in producto.unavailableMetrics)
        assertTrue(AnalyticsMetricKey.STUDY_SESSION_COMPLETION_RATE in producto.unavailableMetrics)
    }

    @Test
    fun aTotalOutageLeavesEveryMetricNullAndEveryKeyNamed() {
        val gateway = gatewayWith { V1StubResponse(500, "down", null) }

        val producto = gateway.producto()

        assertNull(producto.totalUsers)
        assertNull(producto.guestUsers)
        assertNull(producto.onboardedUsers)
        assertNull(producto.totalStudySessions)
        assertNull(producto.onboardingCompletionRate)
        listOf(
            AnalyticsMetricKey.TOTAL_USERS,
            AnalyticsMetricKey.GUEST_USERS,
            AnalyticsMetricKey.ONBOARDED_USERS,
            AnalyticsMetricKey.ONBOARDING_COMPLETION_RATE,
            AnalyticsMetricKey.TOTAL_APP_EVENTS,
            AnalyticsMetricKey.APP_EVENTS_LAST_30D,
            AnalyticsMetricKey.TOTAL_STUDY_SESSIONS,
            AnalyticsMetricKey.STUDY_SESSIONS_LAST_30D,
            AnalyticsMetricKey.FINISHED_STUDY_SESSIONS,
            AnalyticsMetricKey.ABANDONED_STUDY_SESSIONS,
            AnalyticsMetricKey.STUDY_SESSION_COMPLETION_RATE,
        ).forEach { key ->
            assertTrue(key in producto.unavailableMetrics, "Con el upstream caído, '$key' debe declararse no disponible.")
        }
    }

    @Test
    fun aCountThatComesBackWithoutAUsableTotalIsNotTreatedAsZero() {
        // 200 OK pero sin `Content-Range` aprovechable (por ejemplo si alguien
        // quita el `Prefer: count=exact`): el total es desconocido, no cero.
        val gateway = gatewayWith { V1StubResponse(200, "[]", "0-24/*") }

        val producto = gateway.producto()

        assertNull(producto.totalUsers)
        assertTrue(AnalyticsMetricKey.TOTAL_USERS in producto.unavailableMetrics)
    }

    // -----------------------------------------------------------------
    // Reglas de consulta
    // -----------------------------------------------------------------

    @Test
    fun everyCountIsAskedToPostgrestAsAnExactCountWithoutTransferringRows() {
        // La regla que el dashboard violaba: contar trayendo filas. PostgREST
        // recorta en su `max-rows`, así que ese número es falso al crecer la
        // tabla. Se comprueba en el cable, no sólo en el comentario.
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.producto()
        gateway.comunidad()
        gateway.marketing()

        assertTrue(requests.isNotEmpty())
        requests.forEach { request ->
            assertEquals("GET", request.method(), "La analítica es sólo lectura: ${request.uri()}")
            assertEquals(
                "count=exact",
                request.headers().firstValue("Prefer").orElse(null),
                "Sin `Prefer: count=exact` PostgREST no devuelve total exacto: ${request.uri()}",
            )
            assertEquals(
                "0-0",
                request.headers().firstValue("Range").orElse(null),
                "Sin `Range: 0-0` el conteo descargaría filas: ${request.uri()}",
            )
            assertTrue(
                request.timeout().isPresent,
                "Toda petición al upstream necesita timeout o el hilo se queda colgado: ${request.uri()}",
            )
        }
    }

    @Test
    fun theExamModeBreakdownOnlyQueriesCanonicalVocabularyAndKeepsTheRestAsOther() {
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            when {
                uri.contains("tipo_examen_snapshot=eq.rafaga") -> countResponse("0-0/7")
                uri.contains("tipo_examen_snapshot=eq.razonado") -> countResponse("0-0/2")
                uri.contains("tipo_examen_snapshot=eq.inmersivo") -> countResponse("0-0/1")
                uri.contains("intentos_examen") && !uri.contains("=") -> countResponse("0-0/12")
                uri.contains("intentos_examen?select=id") && uri.count { it == '&' } == 0 -> countResponse("0-0/12")
                uri.contains("intentos_examen") -> countResponse("*/0")
                else -> countResponse("*/0")
            }
        }

        val estudio = gateway.estudio()

        assertEquals(7, estudio.attemptsRafaga)
        assertEquals(2, estudio.attemptsRazonado)
        assertEquals(1, estudio.attemptsInmersivo)
        // 12 intentos totales menos los 10 canónicos: 2 filas con vocabulario
        // legacy siguen visibles en vez de desaparecer del reparto.
        assertEquals(2, estudio.attemptsOtherMode)

        val queried = requests.map { it.uri().toString() }
        assertTrue(
            queried.none { it.contains("guiado") || it.contains("guiada") },
            "No se consulta vocabulario legacy: las filas legacy caen en attemptsOtherMode.",
        )
    }

    @Test
    fun theHardestQuestionsListIsOrderedAndLimitedByPostgrestAndNotInKotlin() {
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("v_preguntas_dificultad_global")) {
                V1StubResponse(
                    200,
                    """[{"pregunta_id":"p1","titulo":"Difícil","porcentaje_fallo":91.5,"total_respuestas":40}]""",
                    null,
                )
            } else {
                countResponse("*/0")
            }
        }

        val estudio = gateway.estudio()

        val question = estudio.hardestQuestions?.single()
        assertEquals("p1", question?.questionId)
        assertEquals(91.5, question?.errorRate)
        assertEquals(40, question?.totalAnswers)
        // Nulos de la vista (aún sin respuestas registradas) se conservan como
        // nulos: no se rellenan con 0.
        assertNull(question?.difficulty1To10)

        val viewRequest = requests.map { it.uri().toString() }.single { it.contains("v_preguntas_dificultad_global") }
        assertTrue(viewRequest.contains("order=porcentaje_fallo.desc"), "El orden lo hace la base: $viewRequest")
        assertTrue(viewRequest.contains("limit=10"), "El recorte lo hace la base: $viewRequest")
    }

    @Test
    fun theLeagueDistributionCountsEachLeagueExactlyAndKeepsAFailedBucketAsUnknown() {
        val gateway = gatewayWith { uri ->
            when {
                uri.contains("ligas_nivel") -> V1StubResponse(
                    200,
                    """[
                        {"id":"l1","codigo":"liga_50","nombre":"Liga 50","preguntas_minimas":50,"ventana_calculo":50},
                        {"id":"l2","codigo":"liga_200","nombre":"Liga 200","preguntas_minimas":200,"ventana_calculo":100}
                    ]""",
                    null,
                )
                uri.contains("liga_nivel_id=eq.l1") -> countResponse("0-0/12")
                uri.contains("liga_nivel_id=eq.l2") -> V1StubResponse(500, "boom", null)
                else -> countResponse("*/0")
            }
        }

        val retencion = gateway.retencion()

        val buckets = retencion.leagueDistribution
        assertEquals(2, buckets?.size)
        assertEquals(12, buckets?.first()?.users)
        assertNull(buckets?.last()?.users, "Una liga que no se pudo contar queda desconocida, no en cero.")
        assertEquals(50, buckets?.first()?.minimumQuestions)
    }

    @Test
    fun activeUsersAreCountedOnTheOneRowPerUserTableSoNoDistinctIsNeeded() {
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("ultima_actividad_fecha")) countResponse("0-0/9") else countResponse("*/0")
        }

        val retencion = gateway.retencion()

        assertEquals(9, retencion.activeUsers7d)
        val activeQueries = requests.map { it.uri().toString() }.filter { it.contains("ultima_actividad_fecha") }
        assertEquals(3, activeQueries.size, "Una consulta por ventana: 1d, 7d y 30d.")
        activeQueries.forEach {
            assertTrue(it.contains("rachas_usuario"), "El activo se cuenta sobre rachas_usuario (PK por usuario): $it")
        }
    }

    // -----------------------------------------------------------------
    // Huecos estructurales
    // -----------------------------------------------------------------

    @Test
    fun everyStructuralGapExplainsWhichReadModelItNeeds() {
        val gateway = gatewayWith { countResponse("*/0") }

        val reports = listOf(
            gateway.producto().notInstrumented to gateway.producto().unavailableMetrics,
            gateway.estudio().notInstrumented to gateway.estudio().unavailableMetrics,
            gateway.retencion().notInstrumented to gateway.retencion().unavailableMetrics,
            gateway.comunidad().notInstrumented to gateway.comunidad().unavailableMetrics,
            gateway.economia().notInstrumented to gateway.economia().unavailableMetrics,
            gateway.marketing().notInstrumented to gateway.marketing().unavailableMetrics,
        )

        reports.forEach { (gaps, unavailable) ->
            assertTrue(gaps.isNotEmpty(), "Cada sección tiene al menos un agregado que PostgREST no puede calcular.")
            gaps.forEach { gap ->
                assertTrue(
                    gap.reason.length > 30,
                    "'${gap.key}' debe decir qué vista o RPC hace falta, no sólo que falta: '${gap.reason}'",
                )
                assertTrue(
                    gap.key in unavailable,
                    "'${gap.key}' es un hueco pero no está en unavailableMetrics: un cliente antiguo pintaría su valor.",
                )
            }
        }
    }

    @Test
    fun moneyMetricsAreDeclaredMissingInsteadOfBeingApproximatedFromCounts() {
        val gateway = gatewayWith { countResponse("0-0/16") }

        val economia = gateway.economia()

        // Los conteos sí existen...
        assertEquals(16, economia.walletTransactions)
        // ...pero ninguna suma de dinero se publica sin SUM real.
        assertNull(economia.grossRevenueCents)
        assertNull(economia.avgOrderValueCents)
        assertNull(economia.ticketsInCirculation)
        val gapKeys = economia.notInstrumented.map { it.key }
        assertTrue(AnalyticsMetricKey.GROSS_REVENUE_CENTS in gapKeys)
        assertTrue(AnalyticsMetricKey.AVG_ORDER_VALUE_CENTS in gapKeys)
        assertTrue(AnalyticsMetricKey.TICKETS_IN_CIRCULATION in gapKeys)
    }

    // -----------------------------------------------------------------
    // Upstream simulado
    // -----------------------------------------------------------------

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): AdminAnalyticsGateway = AdminAnalyticsGateway(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
    )

    private fun countResponse(contentRange: String) = V1StubResponse(206, "[]", contentRange)
}
