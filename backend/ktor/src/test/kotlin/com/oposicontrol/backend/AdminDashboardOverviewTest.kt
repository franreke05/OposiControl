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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.concurrent.CompletableFuture
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

    @Test
    fun anUnknownSectionAnswers400NamingTheValidOnes() = testApplication {
        application { dashboardTestModule(gatewayWith { countResponse("*/0") }) }

        val response = client.get("/api/v1/admin/dashboard/overview?sections=system,finanzas")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("finanzas"), "El 400 debe decir qué nombre sobra: $body")
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

        val overview = gateway.overview()

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

        val overview = gateway.overview(setOf(DashboardOverviewBlock.CONTENT))

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

        val overview = gateway.overview()

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

        val health = gateway.overview(setOf(DashboardOverviewBlock.SYSTEM)).system?.health

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

        val health = gateway.overview(setOf(DashboardOverviewBlock.SYSTEM)).system?.health

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

        val attention = gateway.overview(setOf(DashboardOverviewBlock.ATTENTION)).attention

        assertEquals(0, attention?.reportsPending)
        assertFalse(DashboardOverviewMetricKey.REPORTS_PENDING in attention?.unavailableMetrics.orEmpty())
        assertNull(attention?.questionFeedbackPending)
        assertTrue(DashboardOverviewMetricKey.QUESTION_FEEDBACK_PENDING in attention?.unavailableMetrics.orEmpty())
    }

    @Test
    fun draftExamsDisappearsWhenEitherOfItsTwoCountsCannotBeRead() {
        val gateway = gatewayWith { uri ->
            when {
                // El total de exámenes falla; los publicados se leen bien.
                uri.contains("examenes") && uri.contains("publicado_en") -> countResponse("0-0/3")
                uri.contains("examenes") -> StubResponse(503, "down", null)
                else -> countResponse("*/0")
            }
        }

        val attention = gateway.overview(setOf(DashboardOverviewBlock.ATTENTION)).attention

        assertNull(
            attention?.draftExams,
            "Restar contra un total desconocido daría un número inventado, no una derivada.",
        )
        assertTrue(DashboardOverviewMetricKey.DRAFT_EXAMS in attention?.unavailableMetrics.orEmpty())
    }

    @Test
    fun draftExamsIsTotalMinusPublishedWhenBothCountsAreReadable() {
        val gateway = gatewayWith { uri ->
            when {
                uri.contains("examenes") && uri.contains("publicado_en") -> countResponse("0-0/3")
                uri.contains("examenes") -> countResponse("0-0/11")
                else -> countResponse("*/0")
            }
        }

        val attention = gateway.overview(setOf(DashboardOverviewBlock.ATTENTION)).attention

        assertEquals(8, attention?.draftExams)
    }

    @Test
    fun theSevenDayExpiryWindowIsShorterThanTheThirtyDayOne() {
        // La alerta operativa (7d) y el contexto (30d) son dos consultas
        // distintas. Si alguien copiara la ventana de 30 días en ATTENTION, el
        // panel llamaría "vence esta semana" a algo que vence dentro de un mes.
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overview(setOf(DashboardOverviewBlock.ATTENTION, DashboardOverviewBlock.ECONOMY))

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
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overview(setOf(DashboardOverviewBlock.ATTENTION, DashboardOverviewBlock.ECONOMY))

        requests
            .map { it.uri().toString() }
            .filter { it.contains("suscripciones") && it.contains("fin_en") }
            .forEach { uri ->
                assertTrue(uri.contains("estado=eq.activa"), "Sólo cuentan las activas: $uri")
                assertTrue(uri.contains("fin_en=gte."), "Sin el extremo inferior entrarían caducadas: $uri")
                assertTrue(uri.contains("fin_en=lt."), "Sin el extremo superior entraría todo el futuro: $uri")
            }
    }

    @Test
    fun moneyMetricsAreDeclaredNotInstrumentedInsteadOfBeingApproximated() {
        val gateway = gatewayWith { countResponse("0-0/9") }

        val economy = gateway.overview(setOf(DashboardOverviewBlock.ECONOMY)).economy

        // Los conteos que sí se pueden hacer llegan...
        assertEquals(9, economy?.subscriptionsActive)
        // ...y ninguna suma de dinero se publica sin SUM real.
        assertNull(economy?.grossRevenueCents)
        assertNull(economy?.ticketsInCirculation)

        val gaps = economy?.notInstrumented.orEmpty().associate { it.key to it.reason }
        listOf(
            DashboardOverviewMetricKey.GROSS_REVENUE_CENTS,
            DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION,
        ).forEach { key ->
            val reason = gaps[key]
            assertNotNull(reason, "'$key' debe viajar declarada como hueco, no omitida.")
            assertTrue(reason.length > 30, "'$key' debe decir qué vista o RPC hace falta: '$reason'")
            assertTrue(
                key in economy?.unavailableMetrics.orEmpty(),
                "'$key' es un hueco pero no está en unavailableMetrics: un cliente pintaría su valor.",
            )
        }
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
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overview()

        val paths = requests.map { it.uri().toString().substringAfter("/rest/v1/") }.toSet()
        listOf(
            // ATTENTION
            "reportes_contenido?select=id&estado=eq.pendiente",
            "feedback_pregunta?select=id&estado=eq.pendiente",
            "examenes?select=id",
            "examenes?select=id&publicado_en=not.is.null&activo=eq.true",
            // PULSE
            "respuestas_intento?select=id",
            "respuestas_intento?select=id&es_correcta=eq.false",
            // CONTENT
            "preguntas?select=id",
            "recursos_pregunta?select=id&activo=eq.true",
            "lotes_importacion_preguntas?select=id",
            // ECONOMY
            "suscripciones?select=id&estado=eq.activa",
            "productos_comerciales?select=id&activo=eq.true",
            // SYSTEM (sonda)
            "especialidades?select=id",
        ).forEach { expected ->
            assertTrue(
                expected in paths,
                "Ningún conteo pidió exactamente '$expected'. Si le has cambiado o quitado el " +
                    "filtro, el panel publicará un número que no es el que su etiqueta promete.\n" +
                    "Pedido realmente:\n" + paths.sorted().joinToString("\n"),
            )
        }
    }

    @Test
    fun theThirtyDayWindowsAreThirtyDaysAndNotSomeOtherNumber() {
        // Las dos ventanas de PULSE se calculan con aritmética de fechas, así que
        // no basta con afirmar que el filtro está: hay que afirmar el número.
        // Cambiar `minusDays(29)` por `minusDays(6)` deja «Usuarios activos ·
        // últimos 30 días» enseñando los de 7 días, con la etiqueta intacta.
        val beforeCall = LocalDate.now(ZoneOffset.UTC)
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { countResponse("*/0") }

        gateway.overview(setOf(DashboardOverviewBlock.PULSE))

        val afterCall = LocalDate.now(ZoneOffset.UTC)
        val paths = requests.map { it.uri().toString().substringAfter("/rest/v1/") }

        // `rachas_usuario`: una fila por usuario, ventana por fecha UTC. 29 días
        // atrás porque hoy cuenta: hoy + los 29 anteriores = 30 días.
        val activeSince = LocalDate.parse(
            paths.single { it.startsWith("rachas_usuario") }
                .substringAfter("ultima_actividad_fecha=gte.").substringBefore('&'),
        )
        assertTrue(
            activeSince in setOf(beforeCall.minusDays(29), afterCall.minusDays(29)),
            "La ventana de usuarios activos debe abarcar 30 días (hoy menos 29), y pidió " +
                "desde $activeSince con hoy = $afterCall.",
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
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overview()

        assertTrue(requests.isNotEmpty())
        requests.forEach { request ->
            assertEquals("GET", request.method(), "El dashboard agregado es sólo lectura: ${request.uri()}")
            assertTrue(
                request.timeout().isPresent,
                "Toda petición al upstream necesita timeout o el hilo se queda colgado: ${request.uri()}",
            )
        }
        requests.filterNot { it.uri().toString().contains("auditoria_log") }.forEach { request ->
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

        val entries = gateway.overview(setOf(DashboardOverviewBlock.ACTIVITY)).activity?.entries.orEmpty()

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

        val activity = gateway.overview(setOf(DashboardOverviewBlock.ACTIVITY)).activity

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

        val activity = gateway.overview(setOf(DashboardOverviewBlock.ACTIVITY)).activity

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

        val activity = gateway.overview(setOf(DashboardOverviewBlock.ACTIVITY)).activity

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
        val requests = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = requests::add) { uri ->
            if (uri.contains("auditoria_log")) StubResponse(200, "[]", null) else countResponse("*/0")
        }

        gateway.overview(setOf(DashboardOverviewBlock.ACTIVITY))

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
