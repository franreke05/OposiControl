package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El contrato agregado del dashboard, ejercitado contra un upstream simulado.
 *
 * Lo que se prueba aquí no es que los conteos "funcionen" —eso ya lo cubre el
 * contrato de honestidad de la analítica— sino las tres cosas que este endpoint
 * añade y que sólo aquí pueden romperse:
 *
 * 1. Una franja caída no puede llevarse por delante a las otras cinco.
 * 2. Una franja que no se pidió y una franja que falló no pueden llegar iguales.
 * 3. Un hecho de auditoría sin fecha real se descarta, nunca se fecha con `now()`.
 */
class AdminDashboardOverviewTest {

    // -----------------------------------------------------------------
    // Parseo de ?sections=
    // -----------------------------------------------------------------

    @Test
    fun omittingTheSectionsParameterSelectsEveryBlock() {
        val selection = assertIs<DashboardSectionsSelection.Selected>(parseDashboardSections(null))

        assertEquals(DashboardOverviewBlock.ALL, selection.blocks)
    }

    @Test
    fun anEmptySectionsParameterIsTreatedAsOmittedAndNotAsAnEmptySelection() {
        // `?sections=` y `?sections=,,` son formas de no pedir nada concreto. Que
        // significasen "ninguna franja" daría una respuesta vacía con 200, que es
        // el peor de los dos silencios posibles.
        listOf("", "   ", ",", " , , ").forEach { raw ->
            val selection = assertIs<DashboardSectionsSelection.Selected>(parseDashboardSections(raw))
            assertEquals(DashboardOverviewBlock.ALL, selection.blocks, "'$raw' debe seleccionar todas las franjas.")
        }
    }

    @Test
    fun aValidCsvSelectsExactlyThoseBlocksAndIgnoresCaseAndSpacing() {
        val selection = assertIs<DashboardSectionsSelection.Selected>(
            parseDashboardSections(" SYSTEM , attention "),
        )

        assertEquals(
            linkedSetOf(DashboardOverviewBlock.SYSTEM, DashboardOverviewBlock.ATTENTION),
            selection.blocks,
        )
    }

    @Test
    fun anUnknownSectionNameIsRejectedInsteadOfBeingDroppedInSilence() {
        val selection = assertIs<DashboardSectionsSelection.Unknown>(
            parseDashboardSections("system,finanzas"),
        )

        assertEquals(listOf("finanzas"), selection.values)
    }

    /**
     * PC-08 / F-09: el 400 nombra las franjas VÁLIDAS y NUNCA repite lo que mandó el
     * cliente. Antes afirmaba lo contrario —que el cuerpo contuviera el nombre
     * sobrante—, y eso era publicar entrada libre de la URL en la respuesta.
     */
    @Test
    fun anUnknownSectionAnswers400NamingTheValidOnesWithoutEchoingTheInput() = testApplication {
        application { dashboardTestModule(gatewayWith { countResponse("*/0") }) }

        val response = client.get("/api/v1/admin/dashboard/overview?sections=system,finanzas-x-probe-c")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("x-probe-c"), "El 400 repite la entrada del cliente: $body")
        assertFalse(body.contains("finanzas"), "El 400 repite la entrada del cliente: $body")
        DashboardOverviewBlock.ALL.forEach { valid ->
            assertTrue(body.contains(valid), "El 400 debe nombrar la franja válida '$valid': $body")
        }
    }

    @Test
    fun onlyTheRequestedBlocksAreComposed() = testApplication {
        application { dashboardTestModule(gatewayWith { countResponse("0-0/3") }) }

        val body = client.get("/api/v1/admin/dashboard/overview?sections=content").bodyAsText()

        assertTrue(body.contains("\"content\""), "La franja pedida debe venir: $body")
        assertFalse(body.contains("\"economy\""), "Una franja no pedida no viaja: $body")
        assertFalse(body.contains("\"attention\""), "Una franja no pedida no viaja: $body")
    }

    // -----------------------------------------------------------------
    // Degradación por franja
    // -----------------------------------------------------------------

    @Test
    fun aBlockThatCannotBeComposedIsNamedAndTheOtherFiveAreStillServed() {
        // `auditoria_log` es la única lectura de este endpoint que puede lanzar:
        // los conteos ya devuelven null por su cuenta. Con ella caída, ACTIVITY
        // se declara fallida y el resto del panel se pinta igual.
        val gateway = gatewayWith { uri ->
            if (uri.contains("auditoria_log")) StubResponse(503, "upstream down", null) else countResponse("0-0/4")
        }

        val overview = gateway.overviewBlocking()

        assertEquals(listOf(DashboardOverviewBlock.ACTIVITY), overview.failedBlocks)
        assertNull(overview.activity, "Una franja fallida no viaja con contenido inventado.")
        assertNotNull(overview.system)
        assertNotNull(overview.attention)
        assertNotNull(overview.pulse)
        assertNotNull(overview.content)
        assertNotNull(overview.economy)
        assertEquals(4, overview.content.totalExams, "Las demás franjas conservan sus cifras reales.")
    }

    @Test
    fun aBlockThatWasNotRequestedIsNotReportedAsFailed() {
        // La distinción que el contrato existe para mantener: "no lo pediste" y
        // "lo pedí y se rompió" no pueden llegar iguales a la pantalla.
        val gateway = gatewayWith { uri ->
            if (uri.contains("auditoria_log")) StubResponse(503, "down", null) else countResponse("*/0")
        }

        val overview = gateway.overviewBlocking(setOf(DashboardOverviewBlock.CONTENT))

        assertNull(overview.activity)
        assertTrue(
            overview.failedBlocks.isEmpty(),
            "ACTIVITY no se pidió, así que su upstream caído no es un fallo de esta respuesta.",
        )
    }

    @Test
    fun aFailedActivityBlockIsNeverServedAsAnEmptyTimeline() {
        val gateway = gatewayWith { uri ->
            // 200 con cuerpo ilegible: la franja no se puede componer.
            if (uri.contains("auditoria_log")) StubResponse(200, "no-json", null) else countResponse("*/0")
        }

        val overview = gateway.overviewBlocking()

        assertNull(
            overview.activity,
            "Una lista vacía afirmaría que no ha pasado nada, que no es lo mismo que no haber podido mirar.",
        )
        assertTrue(DashboardOverviewBlock.ACTIVITY in overview.failedBlocks)
    }

    // -----------------------------------------------------------------
    // Salud del sistema
    // -----------------------------------------------------------------

    @Test
    fun aCompletedSupabaseProbePublishesItsMeasuredLatencyAsSuchAndNotAsApiLatency() {
        val gateway = gatewayWith { countResponse("0-0/1") }

        val health = gateway.overviewBlocking(setOf(DashboardOverviewBlock.SYSTEM)).system?.health

        assertNotNull(health)
        assertNotNull(
            health.supabaseLatencyMs,
            "La sonda se cronometra igualmente para decidir OPERATIVO/DEGRADADO: descartar esa " +
                "medición ya hecha es perder un dato real.",
        )
        assertTrue(health.supabaseLatencyMs >= 0)
        assertEquals(SystemStatusLevelWire.OPERATIVE, health.supabaseStatus)
        assertTrue(
            SystemStatusNotInstrumented.API_LATENCY in health.notInstrumented,
            "La latencia GENERAL de la API sigue sin fuente; la sonda a Supabase no la sustituye.",
        )
    }

    @Test
    fun aFailedSupabaseProbePublishesNoLatencyAtAll() {
        // Cronometrar una lectura que falló mide cuánto tardó en romperse, no
        // cuánto tarda Supabase en responder. Publicarlo como latencia sería
        // inventar un dato con forma de medición.
        val gateway = gatewayWith { StubResponse(503, "down", null) }

        val health = gateway.overviewBlocking(setOf(DashboardOverviewBlock.SYSTEM)).system?.health

        assertNotNull(health)
        assertNull(health.supabaseLatencyMs)
        assertEquals(SystemStatusLevelWire.DOWN, health.supabaseStatus)
        assertEquals(SystemStatusLevelWire.DOWN, health.overallStatus)
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            health.ktorStatus,
            "Si esta línea se ejecuta, Ktor está sirviendo la petición.",
        )
    }

    // -----------------------------------------------------------------
    // Honestidad de las métricas
    // -----------------------------------------------------------------

    @Test
    fun aRealZeroAndAnUnreadableMetricArriveDifferentInTheSameBlock() {
        val gateway = gatewayWith { uri ->
            when {
                uri.contains("reportes_contenido") -> countResponse("*/0") // cero real
                uri.contains("feedback_pregunta") -> StubResponse(500, "boom", null) // ilegible
                else -> countResponse("0-0/2")
            }
        }

        val attention = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ATTENTION)).attention

        assertEquals(0, attention?.reportsPending)
        assertFalse(DashboardOverviewMetricKey.REPORTS_PENDING in attention?.unavailableMetrics.orEmpty())
        assertNull(attention?.questionFeedbackPending)
        assertTrue(DashboardOverviewMetricKey.QUESTION_FEEDBACK_PENDING in attention?.unavailableMetrics.orEmpty())
        // PC-04: la lectura fallida se nombra ADEMÁS como fallida; el cero real, en ninguna lista.
        assertTrue(DashboardOverviewMetricKey.QUESTION_FEEDBACK_PENDING in attention?.failedMetrics.orEmpty())
        assertFalse(DashboardOverviewMetricKey.REPORTS_PENDING in attention?.failedMetrics.orEmpty())
    }

    /**
     * PC-01. Borrador = activo Y sin publicar, contado con su propio filtro.
     *
     * Los conteos del doble son los de MAIN el día del contrato: 7 exámenes, 1
     * publicado y 0 borradores de verdad (los otros 6 están archivados). La resta
     * anterior —total menos publicados— daba 6 y llamaba «borradores» a los
     * archivados. Si alguien vuelve a restar, este caso da 6 en vez de 0.
     */
    @Test
    fun draftExamsCountsActiveUnpublishedExamsAndNeverTheArchivedOnes() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            when {
                uri.contains("examenes?select=id&activo=eq.true&publicado_en=is.null") -> countResponse("*/0")
                uri.contains("examenes?select=id&publicado_en=not.is.null&activo=eq.true") -> countResponse("0-0/1")
                uri.contains("examenes") -> countResponse("0-0/7")
                else -> countResponse("*/0")
            }
        }

        val attention = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ATTENTION)).attention

        assertEquals(0, attention?.draftExams, "Los archivados no son borradores.")
        assertFalse(DashboardOverviewMetricKey.DRAFT_EXAMS in attention?.unavailableMetrics.orEmpty())
        val examReads = requests.filter { it.uri().rawPath == "/rest/v1/examenes" }.map { it.uri().rawQuery }
        assertEquals(
            listOf("select=id&activo=eq.true&publicado_en=is.null"),
            examReads,
            "ATTENTION hace UNA lectura de exámenes, con el filtro de borrador, y ninguna resta.",
        )
    }

    @Test
    fun draftExamsFailsInsteadOfBecomingZeroWhenItsCountCannotBeRead() {
        val gateway = gatewayWith { uri ->
            if (uri.contains("publicado_en=is.null")) StubResponse(503, "down", null) else countResponse("*/0")
        }

        val attention = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ATTENTION)).attention

        assertNull(attention?.draftExams, "Un conteo que no se pudo leer no es un cero.")
        assertTrue(DashboardOverviewMetricKey.DRAFT_EXAMS in attention?.unavailableMetrics.orEmpty())
        assertTrue(DashboardOverviewMetricKey.DRAFT_EXAMS in attention?.failedMetrics.orEmpty())
    }

    @Test
    fun theSevenDayExpiryWindowIsShorterThanTheThirtyDayOne() {
        // La alerta operativa (7d) y el contexto (30d) son dos consultas
        // distintas. Si alguien copiara la ventana de 30 días en ATTENTION, el
        // panel llamaría "vence esta semana" a algo que vence dentro de un mes.
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.ATTENTION, DashboardOverviewBlock.ECONOMY))

        val windows = requests
            .map { it.uri().toString() }
            .filter { it.contains("suscripciones") && it.contains("fin_en=lt.") }
            .map { it.substringAfter("fin_en=lt.").substringBefore('&') }
            .map(Instant::parse)
        assertEquals(2, windows.size, "Una ventana para la franja de atención y otra para la de economía.")

        val shortest = windows.min()
        val longest = windows.max()
        assertTrue(shortest < longest, "La ventana de 7 días no puede coincidir con la de 30: $windows")
        assertEquals(
            23L,
            ChronoUnit.DAYS.between(shortest, longest),
            "Entre el corte de 7 días y el de 30 hay exactamente 23 días: $windows",
        )
    }

    @Test
    fun bothExpiryWindowsAreBoundedByTheirTwoEndsSoAnOldExpiryIsNotCountedAsImminent() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.ATTENTION, DashboardOverviewBlock.ECONOMY))

        requests
            .map { it.uri().toString() }
            // `fin_en=` y no `fin_en`: la lectura de ACTIVAS (PC-03) también nombra
            // `fin_en`, pero dentro de `or=(…)` y sin ventana, que es lo correcto
            // para ella. Este caso es sobre las dos VENTANAS de vencimiento.
            .filter { it.contains("suscripciones") && it.contains("fin_en=") }
            .forEach { uri ->
                assertTrue(uri.contains("estado=eq.activa"), "Sólo cuentan las activas: $uri")
                assertTrue(uri.contains("fin_en=gte."), "Sin el extremo inferior entrarían caducadas: $uri")
                assertTrue(uri.contains("fin_en=lt."), "Sin el extremo superior entraría todo el futuro: $uri")
            }
    }

    // -----------------------------------------------------------------
    // PC-11 · Economía por RPC
    // -----------------------------------------------------------------

    /** La respuesta REAL de `admin_dashboard_economy_totals` en MAIN el día del contrato. */
    private val economyTotalsLiveBody = """[{"gross_revenue_cents":0,"paid_purchases":0,"tickets_in_circulation":21475}]"""

    private fun economyResponder(totals: StubResponse): (String) -> StubResponse = { uri ->
        if (uri.contains("/rest/v1/rpc/admin_dashboard_economy_totals")) totals else countResponse("0-0/9")
    }

    @Test
    fun theEconomyTotalsComeFromOneRpcCallAndArriveAsNumbersIncludingALegitimateZero() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add, responder = economyResponder(StubResponse(200, economyTotalsLiveBody, null)))

        val economy = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.ECONOMY)).economy)

        assertEquals(0L, economy.grossRevenueCents, "Un 0 leído de la base es un dato, no un hueco.")
        assertEquals(21475L, economy.ticketsInCirculation)
        assertFalse(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS in economy.unavailableMetrics)
        assertFalse(DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION in economy.unavailableMetrics)
        assertTrue(economy.notInstrumented.isEmpty(), "Ya no son huecos declarados: ${economy.notInstrumented}")
        val rpcCalls = requests.filter { it.uri().rawPath == "/rest/v1/rpc/admin_dashboard_economy_totals" }
        assertEquals(1, rpcCalls.size, "Una llamada por franja, no una por métrica.")
        assertEquals("POST", rpcCalls.single().method())
        assertEquals("{}", readRequestBody(rpcCalls.single()))
    }

    /**
     * `gross_revenue_cents` llega `null` A PROPÓSITO cuando la base declara la suma
     * incalculable. Eso es NO_DATA: ausente, en `unavailableMetrics` y **no** en
     * `failedMetrics` —la lectura fue bien— ni en `notInstrumented`. Y nunca 0.
     */
    @Test
    fun aNullRevenueIsNoDataAndNeitherZeroNorAFailedRead() {
        val body = """[{"gross_revenue_cents":null,"paid_purchases":3,"tickets_in_circulation":50}]"""
        val gateway = gatewayWith(responder = economyResponder(StubResponse(200, body, null)))

        val economy = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.ECONOMY)).economy)

        assertNull(economy.grossRevenueCents)
        assertTrue(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS in economy.unavailableMetrics)
        assertFalse(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS in economy.failedMetrics)
        assertTrue(economy.notInstrumented.none { it.key == DashboardOverviewMetricKey.GROSS_REVENUE_CENTS })
        assertEquals(50L, economy.ticketsInCirculation, "Los tickets de la misma fila siguen siendo un dato.")
    }

    /** Si la RPC falla —no existe, sin permiso, caída—, las DOS métricas son FAILED, nunca 0. */
    @Test
    fun aFailedEconomyRpcFailsBothMetricsWithoutFailingTheBlock() {
        val pgrst202 = """{"code":"PGRST202","details":"Searched for the function public.admin_dashboard_economy_totals",""" +
            """"hint":null,"message":"Could not find the function"}"""
        val gateway = gatewayWith(responder = economyResponder(StubResponse(404, pgrst202, null)))

        val overview = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ECONOMY))
        val economy = assertNotNull(overview.economy, "Una RPC caída no tumba la franja.")

        assertTrue(overview.failedBlocks.isEmpty())
        assertEquals(9, economy.subscriptionsActive, "Los conteos de la franja siguen llegando.")
        listOf(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS, DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION).forEach { key ->
            assertTrue(key in economy.unavailableMetrics, "'$key' falló y no está en unavailableMetrics")
            assertTrue(key in economy.failedMetrics, "'$key' falló y no está en failedMetrics")
        }
        assertNull(economy.grossRevenueCents)
        assertNull(economy.ticketsInCirculation)
    }

    /**
     * Una forma de respuesta que no es la del contrato —un objeto en vez de un array,
     * cero filas, o una fila sin tickets— es un contrato roto y cuenta como fallo.
     */
    @Test
    fun anUnexpectedEconomyShapeIsAFailedReadAndNotAGuess() {
        listOf(
            """{"gross_revenue_cents":0,"paid_purchases":0,"tickets_in_circulation":21475}""",
            "[]",
            """[{"gross_revenue_cents":0,"paid_purchases":0}]""",
        ).forEach { body ->
            val gateway = gatewayWith(responder = economyResponder(StubResponse(200, body, null)))

            val economy = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.ECONOMY)).economy)

            assertTrue(DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION in economy.failedMetrics, "cuerpo: $body")
            assertTrue(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS in economy.failedMetrics, "cuerpo: $body")
            assertNull(economy.ticketsInCirculation, "cuerpo: $body")
        }
    }

    // -----------------------------------------------------------------
    // PC-02 · Usuarios activos por RPC
    // -----------------------------------------------------------------

    private fun activeUsersResponder(response: StubResponse): (String) -> StubResponse = { uri ->
        if (uri.contains("/rest/v1/rpc/admin_dashboard_active_users")) response else countResponse("*/0")
    }

    @Test
    fun activeUsersIsTheScalarOfTheRpcCalledWithThirtyDays() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add, responder = activeUsersResponder(StubResponse(200, "12\n", null)))

        val pulse = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.PULSE)).pulse)

        assertEquals(12, pulse.activeUsers30d)
        val call = requests.single { it.uri().rawPath == "/rest/v1/rpc/admin_dashboard_active_users" }
        assertEquals("POST", call.method())
        assertEquals("""{"p_days":30}""", readRequestBody(call), "La ventana viaja literal: 30 días.")
        assertTrue(
            requests.none { it.uri().rawPath.contains("rachas_usuario") },
            "`rachas_usuario` no tiene escritor: leerla era publicar un cero estructural.",
        )
    }

    @Test
    fun aRealZeroActiveUsersIsADatumAndNotAGap() {
        val gateway = gatewayWith(responder = activeUsersResponder(StubResponse(200, "0", null)))

        val pulse = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.PULSE)).pulse)

        assertEquals(0, pulse.activeUsers30d)
        assertFalse(DashboardOverviewMetricKey.ACTIVE_USERS_30D in pulse.unavailableMetrics)
    }

    @Test
    fun anyActiveUsersFailureOrWrongShapeIsAFailedReadAndNeverZero() {
        listOf(
            StubResponse(404, """{"code":"PGRST202","message":"Could not find the function"}""", null),
            StubResponse(401, """{"code":"42501","message":"permission denied for function admin_dashboard_active_users"}""", null),
            StubResponse(200, "[0]", null),
            StubResponse(200, "\"12\"", null),
            StubResponse(200, "null", null),
        ).forEach { response ->
            val gateway = gatewayWith(responder = activeUsersResponder(response))

            val pulse = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.PULSE)).pulse)

            assertNull(pulse.activeUsers30d, "respuesta ${response.status} ${response.body}")
            assertTrue(DashboardOverviewMetricKey.ACTIVE_USERS_30D in pulse.unavailableMetrics, response.body)
            assertTrue(DashboardOverviewMetricKey.ACTIVE_USERS_30D in pulse.failedMetrics, response.body)
        }
    }

    // -----------------------------------------------------------------
    // PC-04 · Denominador cero ≠ lectura fallida
    // -----------------------------------------------------------------

    @Test
    fun anErrorRateWithoutAnswersIsNoDataButNotAFailedRead() {
        val gateway = gatewayWith { countResponse("*/0") }

        val pulse = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.PULSE)).pulse)

        assertNull(pulse.globalErrorRate, "0 % de cero respuestas sería inventar.")
        assertTrue(DashboardOverviewMetricKey.GLOBAL_ERROR_RATE in pulse.unavailableMetrics)
        assertFalse(
            DashboardOverviewMetricKey.GLOBAL_ERROR_RATE in pulse.failedMetrics,
            "Las dos lecturas fueron bien: no hay respuestas todavía, que no es un fallo.",
        )
    }

    @Test
    fun anErrorRateWithAnUnreadableCountIsAFailedRead() {
        val gateway = gatewayWith { uri ->
            if (uri.contains("es_correcta=eq.false")) StubResponse(500, "boom", null) else countResponse("0-0/40")
        }

        val pulse = assertNotNull(gateway.overviewBlocking(setOf(DashboardOverviewBlock.PULSE)).pulse)

        assertNull(pulse.globalErrorRate)
        assertTrue(DashboardOverviewMetricKey.GLOBAL_ERROR_RATE in pulse.failedMetrics)
    }

    @Test
    fun failedMetricsIsAlwaysASubsetOfUnavailableMetrics() {
        val gateway = gatewayWith { StubResponse(503, "down", null) }

        val overview = gateway.overviewBlocking()

        listOf(
            overview.attention?.let { it.failedMetrics to it.unavailableMetrics },
            overview.pulse?.let { it.failedMetrics to it.unavailableMetrics },
            overview.content?.let { it.failedMetrics to it.unavailableMetrics },
            overview.economy?.let { it.failedMetrics to it.unavailableMetrics },
        ).forEach { pair ->
            val (failed, unavailable) = assertNotNull(pair)
            assertTrue(failed.isNotEmpty(), "Con todo caído, cada franja declara lecturas fallidas.")
            assertTrue(
                unavailable.containsAll(failed),
                "Un cliente que no conozca `failedMetrics` tiene que seguir viendo «sin dato»: $failed ⊄ $unavailable",
            )
        }
    }

    // -----------------------------------------------------------------
    // PC-03 · Suscripciones activas = vigentes
    // -----------------------------------------------------------------

    @Test
    fun activeSubscriptionsExcludeTheExpiredOnesWithAnEncodedInstant() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.ECONOMY))

        val active = requests
            .map { it.uri().rawQuery.orEmpty() }
            .single { it.startsWith("select=id&estado=eq.activa&or=") }
        val match = Regex("""^select=id&estado=eq\.activa&or=\(fin_en\.is\.null,fin_en\.gt\.%22(.+)%22\)$""").find(active)
        val instant = assertNotNull(match, "El filtro de activas no es el del contrato: $active").groupValues[1]
        assertFalse(instant.contains("+"), "Un `+` sin codificar llega como espacio y PostgREST da 400: $instant")
        assertTrue(instant.endsWith("Z"), "El instante va en UTC con `Z`: $instant")
        Instant.parse(instant)
    }

    @Test
    fun activeSubscriptionsFilterIsSharedWithTheAnalyticsEconomySection() {
        val now = Instant.parse("2026-09-17T08:15:13.875Z")

        assertEquals(
            "estado=eq.activa&or=(fin_en.is.null,fin_en.gt.%222026-09-17T08:15:13Z%22)",
            activeSubscriptionsFilter(now),
        )
    }

    // -----------------------------------------------------------------
    // PC-05 · Catálogo sin archivados · PC-06 · Columnas de la cronología
    // -----------------------------------------------------------------

    @Test
    fun theCatalogCountsOnlyActiveExamsAndActiveQuestions() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.CONTENT))

        val queries = requests.associate { it.uri().rawPath to it.uri().rawQuery }
        assertEquals("select=id&activo=eq.true", queries["/rest/v1/examenes"], "examenes se filtra por `activo`.")
        assertEquals("select=id&activa=eq.true", queries["/rest/v1/preguntas"], "preguntas se filtra por `activa`.")
    }

    @Test
    fun theTimelineAsksOnlyForTheColumnsItDelivers() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.ACTIVITY))

        assertEquals(
            "select=id,accion,tabla_afectada,registro_id,creado_en&order=creado_en.desc&limit=8",
            requests.single { it.uri().rawPath == "/rest/v1/auditoria_log" }.uri().rawQuery,
            "`select=*` traía antes/despues, ip, user_agent y usuario_id, que la franja no usa.",
        )
    }

    @Test
    fun everyFilteredCountAsksPostgrestForExactlyItsDocumentedFilter() {
        // La red que faltaba. Los tests de esta clase comprobaban las CABECERAS
        // de cada conteo (`Prefer`, `Range`) y el VALOR devuelto, pero no el
        // filtro: quitarle `estado=eq.pendiente` a `reportes_contenido` dejaba la
        // suite en verde y ponía en el panel el total histórico de reportes
        // —resueltos y descartados incluidos— bajo la etiqueta «Reportes
        // pendientes». Un número plausible con la etiqueta correcta al lado es
        // peor que un cero inventado: nadie lo mira dos veces.
        //
        // Se afirma la ruta ENTERA, no un fragmento con `contains`: así un filtro
        // borrado rompe el test aunque el nombre de la tabla siga apareciendo, y
        // `suscripciones?...&estado=eq.activa` no se confunde con la variante que
        // además lleva `fin_en`.
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overviewBlocking()

        val paths = requests.map { it.uri().toString().substringAfter("/rest/v1/") }.toSet()
        listOf(
            // ATTENTION
            "reportes_contenido?select=id&estado=eq.pendiente",
            "feedback_pregunta?select=id&estado=eq.pendiente",
            // PC-01: borrador = activo y sin publicar, con su propio conteo.
            "examenes?select=id&activo=eq.true&publicado_en=is.null",
            // PULSE
            "examenes?select=id&publicado_en=not.is.null&activo=eq.true",
            "respuestas_intento?select=id",
            "respuestas_intento?select=id&es_correcta=eq.false",
            // PC-02: usuarios activos por RPC.
            "rpc/admin_dashboard_active_users",
            // CONTENT · PC-05: sin archivados.
            "examenes?select=id&activo=eq.true",
            "preguntas?select=id&activa=eq.true",
            "recursos_pregunta?select=id&activo=eq.true",
            "lotes_importacion_preguntas?select=id",
            // ACTIVITY · PC-06: columnas explícitas.
            "auditoria_log?select=id,accion,tabla_afectada,registro_id,creado_en&order=creado_en.desc&limit=8",
            // ECONOMY · PC-11: totales por RPC.
            "productos_comerciales?select=id&activo=eq.true",
            "rpc/admin_dashboard_economy_totals",
            // SYSTEM (sonda)
            "especialidades?select=id",
        ).forEach { expected ->
            assertTrue(
                expected in paths,
                "Ninguna lectura pidió exactamente '$expected'. Si le has cambiado o quitado el " +
                    "filtro, el panel publicará un número que no es el que su etiqueta promete.\n" +
                    "Pedido realmente:\n" + paths.sorted().joinToString("\n"),
            )
        }
        // Y lo que YA NO se pide, porque medía otra cosa que su etiqueta.
        listOf(
            "examenes?select=id", // total con archivados (PC-01 / PC-05)
            "preguntas?select=id", // banco con archivadas (PC-05)
            "suscripciones?select=id&estado=eq.activa", // activas vencidas (PC-03)
        ).forEach { gone ->
            assertFalse(gone in paths, "'$gone' volvió a pedirse.\n" + paths.sorted().joinToString("\n"))
        }
        assertTrue(paths.none { it.startsWith("rachas_usuario") }, "`rachas_usuario` no tiene escritor (PC-02).")
        assertTrue(paths.none { it.startsWith("auditoria_log?select=*") }, "La cronología no pide `select=*` (PC-06).")
        assertTrue(
            paths.any { it.startsWith("suscripciones?select=id&estado=eq.activa&or=(fin_en.is.null,fin_en.gt.") },
            "Las suscripciones activas son las vigentes (PC-03).\n" + paths.sorted().joinToString("\n"),
        )
    }

    @Test
    fun theThirtyDayWindowsAreThirtyDaysAndNotSomeOtherNumber() {
        // Las dos ventanas de PULSE se deciden aquí, así que no basta con afirmar
        // que el filtro está: hay que afirmar el número. Cambiar la ventana deja
        // «Usuarios activos · últimos 30 días» enseñando otra cosa con la etiqueta
        // intacta.
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.PULSE))

        val paths = requests.map { it.uri().toString().substringAfter("/rest/v1/") }

        // Usuarios activos (PC-02): la ventana viaja como parámetro de la RPC. La
        // función acota en silencio lo que esté fuera de 1..366, así que un error
        // de este lado no se vería en la base: se fija el literal.
        assertEquals(
            """{"p_days":30}""",
            readRequestBody(requests.single { it.uri().rawPath == "/rest/v1/rpc/admin_dashboard_active_users" }),
        )

        // `intentos_examen`: instante, 30 días exactos hacia atrás.
        val attemptsSince = Instant.parse(
            paths.single { it.startsWith("intentos_examen") }
                .substringAfter("iniciado_en=gte.").substringBefore('&'),
        )
        assertEquals(
            30L,
            ChronoUnit.DAYS.between(attemptsSince, Instant.now()),
            "La ventana de intentos debe ser de 30 días y pidió desde $attemptsSince.",
        )
    }

    @Test
    fun everyCountIsAskedToPostgrestAsAnExactCountWithoutTransferringRows() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overviewBlocking()

        assertTrue(requests.isNotEmpty())
        requests.forEach { request ->
            // Sólo lectura. Las dos RPC van por POST porque así las expone PostgREST,
            // pero son funciones STABLE de sólo lectura; cualquier otro POST, PATCH o
            // DELETE sería una escritura desde un panel de lectura.
            val isReadOnlyRpc = request.uri().rawPath in setOf(
                "/rest/v1/rpc/admin_dashboard_active_users",
                "/rest/v1/rpc/admin_dashboard_economy_totals",
            )
            assertEquals(
                if (isReadOnlyRpc) "POST" else "GET",
                request.method(),
                "El dashboard agregado es sólo lectura: ${request.uri()}",
            )
            assertTrue(
                request.timeout().isPresent,
                "Toda petición al upstream necesita timeout o el hilo se queda colgado: ${request.uri()}",
            )
        }
        requests.filterNot {
            it.uri().toString().contains("auditoria_log") || it.uri().rawPath.startsWith("/rest/v1/rpc/")
        }.forEach { request ->
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
        }
    }

    // -----------------------------------------------------------------
    // Cronología real
    // -----------------------------------------------------------------

    @Test
    fun anActivityRowWithoutAParseableTimestampIsDiscardedAndNeverStampedWithNow() {
        val gateway = gatewayWith { uri ->
            if (uri.contains("auditoria_log")) {
                StubResponse(
                    200,
                    """[
                        {"id":"a1","accion":"examen_publicado","tabla_afectada":"examenes",
                         "registro_id":"e1","creado_en":"2026-09-01T10:15:00Z"},
                        {"id":"a2","accion":"sin_fecha","tabla_afectada":"examenes","registro_id":"e2"},
                        {"id":"a3","accion":"fecha_vacia","creado_en":"   "},
                        {"id":"a4","accion":"fecha_basura","creado_en":"ayer por la tarde"},
                        {"id":"a5","accion":"con_desplazamiento","creado_en":"2026-09-02T08:00:00+00:00"}
                    ]""",
                    null,
                )
            } else {
                countResponse("*/0")
            }
        }

        val entries = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ACTIVITY)).activity?.entries.orEmpty()

        assertEquals(
            listOf("a1", "a5"),
            entries.map { it.id },
            "Las filas sin `creado_en` interpretable se descartan; rellenarlas con la hora de lectura " +
                "es el fallo que este rediseño viene a cerrar.",
        )
        assertEquals("2026-09-01T10:15:00Z", entries.first().occurredAt)
        assertEquals("examen_publicado", entries.first().action)
        assertEquals("examenes", entries.first().targetTable)
        assertEquals("e1", entries.first().targetId)
    }

    @Test
    fun anActivityRowWithoutAnIdIsDiscardedInsteadOfBeingGivenAnEmptyOne() {
        // Una fila sin id es tan inservible como una sin fecha: nadie puede
        // señalarla, deduplicarla ni abrirla. `id.orEmpty()` le fabricaba una
        // cadena vacía, que es el mismo defecto que rellenar un conteo con cero
        // en otro campo.
        val gateway = gatewayWith { uri ->
            if (uri.contains("auditoria_log")) {
                StubResponse(
                    200,
                    """[
                        {"id":"a1","accion":"examen_publicado","creado_en":"2026-09-01T10:15:00Z"},
                        {"accion":"sin_id","creado_en":"2026-09-01T11:00:00Z"},
                        {"id":"","accion":"id_vacio","creado_en":"2026-09-01T11:30:00Z"},
                        {"id":"   ","accion":"id_en_blanco","creado_en":"2026-09-01T12:00:00Z"}
                    ]""",
                    null,
                )
            } else {
                countResponse("*/0")
            }
        }

        val activity = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ACTIVITY)).activity

        assertEquals(listOf("a1"), activity?.entries?.map { it.id })
        assertEquals(3, activity?.droppedEntries)
    }

    @Test
    fun discardedRowsAreCountedSoAnEmptyTimelineCannotBeReadAsNoActivity() {
        // "He leído cuatro filas y ninguna era usable" y "no hay nada en
        // auditoria_log" son afirmaciones distintas, y sin este contador llegan
        // al cliente idénticas: una lista vacía.
        val gateway = gatewayWith { uri ->
            if (uri.contains("auditoria_log")) {
                StubResponse(
                    200,
                    """[
                        {"id":"a1","accion":"sin_fecha"},
                        {"id":"a2","accion":"fecha_basura","creado_en":"ayer"},
                        {"accion":"sin_id","creado_en":"2026-09-01T11:00:00Z"},
                        {"id":"a4","accion":"fecha_vacia","creado_en":"  "}
                    ]""",
                    null,
                )
            } else {
                countResponse("*/0")
            }
        }

        val activity = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ACTIVITY)).activity

        assertEquals(emptyList(), activity?.entries)
        assertEquals(
            4,
            activity?.droppedEntries,
            "Una lista vacía con 4 descartes no es lo mismo que una tabla vacía, y el contrato " +
                "debe poder decir cuál de las dos es.",
        )
    }

    @Test
    fun anEmptyAuditTableReportsNoDropsAtAll() {
        val gateway = gatewayWith { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        val activity = gateway.overviewBlocking(setOf(DashboardOverviewBlock.ACTIVITY)).activity

        assertEquals(emptyList(), activity?.entries)
        assertEquals(0, activity?.droppedEntries, "Sin filas leídas no hay nada que descartar.")
    }

    @Test
    fun theServerLimitLeavesHeadroomAboveWhatTheScreenPaints() {
        // El descarte ocurre DESPUÉS del recorte del servidor, así que pedir
        // exactamente las 5 que el panel pinta dejaría la cronología corta en
        // cuanto una fila fuese ilegible. Los 8 son esa holgura, no desperdicio.
        assertTrue(
            AdminDashboardOverviewGateway.ACTIVITY_LIMIT > 5,
            "ACTIVITY_LIMIT debe dejar margen sobre las 5 filas que pinta el panel: el descarte " +
                "por fecha o id ilegible ocurre después del `limit` de PostgREST.",
        )
    }

    @Test
    fun theTimelineIsOrderedAndLimitedByPostgrestAndNotInKotlin() {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overviewBlocking(setOf(DashboardOverviewBlock.ACTIVITY))

        val uri = requests.map { it.uri().toString() }.single { it.contains("auditoria_log") }
        assertTrue(uri.contains("order=creado_en.desc"), "El orden lo hace la base: $uri")
        assertTrue(
            uri.contains("limit=${AdminDashboardOverviewGateway.ACTIVITY_LIMIT}"),
            "El recorte lo hace la base: $uri",
        )
    }

    @Test
    fun parsingATimestampAcceptsBothPostgrestShapesAndRejectsAnythingElse() {
        assertEquals(Instant.parse("2026-09-01T10:15:00Z"), parseAuditTimestamp("2026-09-01T10:15:00Z"))
        assertEquals(Instant.parse("2026-09-01T10:15:00Z"), parseAuditTimestamp("2026-09-01T12:15:00+02:00"))
        assertNull(parseAuditTimestamp(null))
        assertNull(parseAuditTimestamp(""))
        assertNull(parseAuditTimestamp("2026-09-01"))
        assertNull(parseAuditTimestamp("ayer"))
    }

    // -----------------------------------------------------------------
    // Contrato de claves
    // -----------------------------------------------------------------

    @Test
    fun everyMetricKeyNamesAFieldThatActuallyExistsInItsBlockDto() {
        // La clave viaja al cliente para marcar una tarjeta como NO_DATA. Si se
        // separa del nombre del campo, la UI marca la tarjeta equivocada y nada
        // deja de compilar.
        val fields = listOf(
            AdminDashboardAttentionDto::class,
            AdminDashboardPulseDto::class,
            AdminDashboardContentDto::class,
            AdminDashboardEconomyDto::class,
        ).flatMap { klass -> klass.java.declaredFields.map { it.name } }.toSet()

        val keys = DashboardOverviewMetricKey::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull {
                it.isAccessible = true
                it.get(DashboardOverviewMetricKey) as? String
            }

        assertEquals(17, keys.size, "Las 17 claves del contrato del dashboard.")
        keys.forEach { key ->
            assertTrue(key in fields, "La clave '$key' no corresponde a ningún campo de DTO.")
        }
    }

    // -----------------------------------------------------------------
    // Infraestructura de prueba
    // -----------------------------------------------------------------

    private fun Application.dashboardTestModule(gateway: AdminDashboardOverviewGateway) {
        install(ContentNegotiation) {
            // Misma configuración que `Application.module()`.
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = false })
        }
        routing { adminDashboardOverviewRoutes(gateway) }
    }

    /**
     * `overview` es `suspend` desde que las cinco franjas no-SYSTEM se componen
     * en paralelo. Los casos de este fichero prueban el CONTENIDO del contrato
     * —qué cifras salen, qué se marca NO_DATA, qué franja falla— y no la
     * concurrencia, que tiene su propio fichero
     * (`AdminDashboardOverviewConcurrencyTest`). Un `runBlocking` en un solo
     * sitio los deja leyéndose igual que antes en vez de teñir 19 casos de
     * andamiaje que no es lo que están probando.
     */
    private fun AdminDashboardOverviewGateway.overviewBlocking(
        sections: Set<String> = DashboardOverviewBlock.ALL,
    ): AdminDashboardOverviewDto = runBlocking { overview(sections) }

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> StubResponse,
    ): AdminDashboardOverviewGateway = AdminDashboardOverviewGateway(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = StubHttpClient(record, responder),
    )

    private fun countResponse(contentRange: String) = StubResponse(206, "[]", contentRange)

    internal data class StubResponse(val status: Int, val body: String, val contentRange: String?)

    /**
     * `HttpClient` mínimo de la JDK, igual que el de `AdminAnalyticsGatewayTest`.
     * Se escribe a mano porque lo que hay que observar es exactamente lo que sale
     * por el cable: método, cabeceras `Prefer`/`Range` y timeout.
     *
     * **Los casos que graban usan `CopyOnWriteArrayList` y no `mutableListOf`.**
     * Desde que las cinco franjas no-SYSTEM se componen en paralelo, `record`
     * lo llaman cinco hilos del pool IO a la vez, y `ArrayList.add` no es
     * seguro ahí: perdería peticiones en silencio y los casos que cuentan
     * cuántas salieron pasarían o fallarían según el día. Un test que mide
     * concurrencia con una estructura que no la soporta mide su propia carrera.
     */
    private class StubHttpClient(
        private val record: (HttpRequest) -> Unit,
        private val responder: (String) -> StubResponse,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            record(request)
            return StubHttpResponse(request, responder(request.uri().toString())) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private class StubHttpResponse(
        private val request: HttpRequest,
        private val stub: StubResponse,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = stub.status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders {
            val headers = stub.contentRange
                ?.let { mapOf("content-range" to listOf(it)) }
                ?: emptyMap()
            return HttpHeaders.of(headers) { _, _ -> true }
        }
        override fun body(): String = stub.body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
