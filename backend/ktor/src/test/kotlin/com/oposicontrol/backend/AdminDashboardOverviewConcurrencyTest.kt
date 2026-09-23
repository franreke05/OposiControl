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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cómo se **ejecuta** el dashboard agregado: qué corre a la vez, qué no puede
 * correr a la vez, y qué sigue siendo verdad cuando algo se rompe a mitad.
 *
 * Vive aparte de `AdminDashboardOverviewTest` —que prueba el CONTENIDO del
 * contrato: qué cifras salen y cuáles se marcan NO_DATA— porque estos casos
 * necesitan puntos de cita y esperas, y mezclarlos haría ilegibles los dos.
 *
 * ### Cómo se observa la concurrencia sin medir tiempos
 *
 * Un test que afirmara "tardó menos de X" mediría la máquina de CI, no el
 * código. Aquí se usa un **punto de cita**: el doble de upstream retiene una
 * petición de cada franja hasta que han llegado las cinco. Si las franjas se
 * ejecutaran en serie, la primera esperaría a cuatro compañeras que no pueden
 * llegar —porque están detrás de ella— y el punto de cita expiraría. La
 * afirmación es entonces binaria y no depende de ningún umbral: o llegaron las
 * cinco a la vez, o no se están ejecutando a la vez.
 */
class AdminDashboardOverviewConcurrencyTest {

    /**
     * Cuánto espera una franja en el punto de cita antes de declarar que las
     * demás no van a venir. Generoso: sólo se agota cuando el código está mal,
     * y entonces lo paga una vez (ver [Rendezvous.arrive]).
     */
    private val gateTimeoutSeconds = 10L

    /** Lo que tarda la sonda de SYSTEM en el doble. Ventana amplia y a propósito:
     * si otra franja se hubiera lanzado con ella, le da tiempo de sobra a colarse. */
    private val systemProbeMillis = 200L

    /** Una tabla ÚNICA por franja, para reconocer "esta franja ya llegó aquí". */
    private val blockMarkers = mapOf(
        DashboardOverviewBlock.ATTENTION to "/rest/v1/reportes_contenido?",
        DashboardOverviewBlock.PULSE to "/rest/v1/intentos_examen?",
        DashboardOverviewBlock.CONTENT to "/rest/v1/preguntas?",
        DashboardOverviewBlock.ACTIVITY to "/rest/v1/auditoria_log?",
        DashboardOverviewBlock.ECONOMY to "/rest/v1/productos_comerciales?",
    )

    private val systemProbeMarker = "/rest/v1/especialidades?"

    // -----------------------------------------------------------------
    // Las cinco franjas no-SYSTEM corren a la vez
    // -----------------------------------------------------------------

    @Test
    fun theFiveNonSystemBlocksAreComposedConcurrently() {
        val rendezvous = Rendezvous(expected = 5)
        val gateway = gatewayWith { uri ->
            blockMarkers.entries.firstOrNull { uri.contains(it.value) }?.let { rendezvous.arrive(it.key) }
            stubResponseFor(uri)
        }

        val overview = runBlocking { gateway.overview() }

        assertEquals(
            blockMarkers.keys,
            rendezvous.arrived(),
            "no llegaron al punto de cita las cinco franjas no-SYSTEM",
        )
        assertFalse(
            rendezvous.expired(),
            "una franja llegó al punto de cita y las otras cuatro no aparecieron en " +
                "${gateTimeoutSeconds}s: se están componiendo EN SERIE. Paralelizarlas es lo que baja " +
                "el peor caso de ~380s de hilo a ~100s.",
        )
        assertTrue(overview.failedBlocks.isEmpty(), "ninguna franja debía fallar: ${overview.failedBlocks}")
    }

    // -----------------------------------------------------------------
    // SYSTEM va sola
    // -----------------------------------------------------------------

    /**
     * **El punto fino de todo el contrato.** SYSTEM no se serializa por
     * prudencia: es que su latencia medida ES el dato que publica. Lanzada con
     * otras 18 peticiones en vuelo contra el mismo `HttpClient`, esa cifra deja
     * de medir «cuánto tarda Supabase» y pasa a medir «cuánto tarda Supabase
     * con 18 peticiones encima» — una métrica que dice una cosa y mide otra.
     */
    @Test
    fun theSystemProbeIsMeasuredAloneAndNeverOverlapsWithAnotherBlock() {
        val systemProbeInFlight = AtomicBoolean(false)
        val overlapped = CopyOnWriteArrayList<String>()
        val recorded = CopyOnWriteArrayList<HttpRequest>()

        val gateway = gatewayWith(record = recorded::add) { uri ->
            if (uri.contains(systemProbeMarker)) {
                systemProbeInFlight.set(true)
                Thread.sleep(systemProbeMillis)
                systemProbeInFlight.set(false)
            } else if (systemProbeInFlight.get()) {
                overlapped += uri
            }
            stubResponseFor(uri)
        }

        val overview = runBlocking { gateway.overview() }

        // Control: sin esto, "no hubo solape" pasaría también si no hubiera
        // corrido nada más que la sonda.
        //
        // Es `== 19` y no `>= 15`. El umbral flojo anterior dejaba pasar en
        // silencio hasta cuatro peticiones perdidas: con 15 de 19 el caso seguía
        // verde, y "no hubo solape" habría sido cierto por no haber corrido lo
        // que tenía que correr. 19 es la cifra exacta del abanico declarado
        // (1 SYSTEM + 4 ATTENTION + 5 PULSE + 4 CONTENT + 1 ACTIVITY + 4 ECONOMY,
        // con las dos RPC de PC-02 y PC-11 incluidas).
        assertEquals(
            19,
            recorded.size,
            "el control falla: salieron ${recorded.size} peticiones y el abanico declarado son 19, " +
                "así que la ausencia de solape no demuestra nada. Salieron: ${recorded.map { it.uri() }}",
        )
        assertTrue(
            overlapped.isEmpty(),
            "hubo ${overlapped.size} peticiones de otras franjas en vuelo mientras se cronometraba la " +
                "sonda de SYSTEM: su latencia ya no mide lo que dice medir. Solapadas: $overlapped",
        )

        // Y la consecuencia: la latencia publicada es la de ESA sonda.
        val latency = assertNotNull(overview.system?.health?.supabaseLatencyMs)
        assertTrue(
            latency >= systemProbeMillis,
            "la latencia publicada ($latency ms) no corresponde a la sonda cronometrada",
        )
    }

    @Test
    fun askingOnlyForSystemMakesExactlyOneUpstreamRequest() {
        val recorded = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = recorded::add) { stubResponseFor(it) }

        val overview = runBlocking { gateway.overview(setOf(DashboardOverviewBlock.SYSTEM)) }

        // Es el refresco de 60s del panel (`?sections=system`). Una petición y
        // ninguna más: si alguna vez saliera una segunda, la cifra de latencia
        // dejaría de ser la de una sonda aislada.
        assertEquals(1, recorded.size, "salieron ${recorded.map { it.uri() }}")
        assertNotNull(overview.system)
        assertNull(overview.attention)
        assertNull(overview.economy)
    }

    // -----------------------------------------------------------------
    // Degradación independiente, ahora que corren a la vez
    // -----------------------------------------------------------------

    /**
     * La garantía de §4.1 traducida al mundo concurrente: una franja que lanza
     * **no puede cancelar a sus hermanas**. Es el modo de fallo propio de
     * `coroutineScope` —una hija que falla cancela a las demás— y por eso el
     * fallo se captura DENTRO de cada rama y no fuera.
     *
     * Cómo se demuestra: ACTIVITY falla de inmediato mientras las otras cuatro
     * están retenidas en el punto de cita. Si el fallo las cancelara, no
     * llegarían a soltarse y llegarían nulas.
     */
    @Test
    fun aBlockThatFailsDoesNotCancelTheFourThatAreStillInFlight() {
        val rendezvous = Rendezvous(expected = 4)
        val gateway = gatewayWith { uri ->
            if (uri.contains("/rest/v1/auditoria_log")) {
                V1StubResponse(503, "upstream down")
            } else {
                blockMarkers.entries
                    .firstOrNull { it.key != DashboardOverviewBlock.ACTIVITY && uri.contains(it.value) }
                    ?.let { rendezvous.arrive(it.key) }
                stubResponseFor(uri)
            }
        }

        val overview = runBlocking { gateway.overview() }

        assertFalse(rendezvous.expired(), "el fallo de ACTIVITY dejó a las otras cuatro sin terminar")
        assertEquals(
            listOf(DashboardOverviewBlock.ACTIVITY),
            overview.failedBlocks,
            "sólo la franja que falló se nombra, y se nombra siempre",
        )
        assertNull(overview.activity, "una franja fallida no viaja con contenido inventado")
        assertNotNull(overview.system)
        assertNotNull(overview.attention)
        assertNotNull(overview.pulse)
        assertNotNull(overview.content)
        assertNotNull(overview.economy)
    }

    // -----------------------------------------------------------------
    // `failedBlocks`: quién puede escribir ahí, y desde cuántos hilos
    // -----------------------------------------------------------------

    /**
     * **Guardarraíl de alcanzabilidad, y la razón de que no haya un caso de
     * "dos franjas fallan a la vez".**
     *
     * `failedBlocks` se acumula en un `ConcurrentHashMap.newKeySet()` porque
     * cinco franjas corren a la vez. Pero hoy **sólo una de las cinco puede
     * llegar a escribir ahí**: las cuatro franjas de métrica canalizan TODAS sus
     * lecturas por `countOrNull`, que devuelve `null` en vez de lanzar, y SYSTEM
     * envuelve su sonda en `runCatching`. La única lectura sin guardar del
     * fichero es la cronología de ACTIVITY, y eso es deliberado: una lista vacía
     * afirmaría que no ha pasado nada, que no es lo mismo que "no he podido
     * mirar".
     *
     * Consecuencia honesta: un caso que hiciera fallar dos franjas a la vez **no
     * se puede escribir contra este gateway** sin cambiar antes el código de
     * producción, y cambiarlo para poder probarlo sería exactamente al revés. Lo
     * que sí se puede hacer es fijar la premisa: si algún día una segunda franja
     * gana una lectura sin guardar, este caso se pone rojo, y ése es el día en
     * que el caso de "dos a la vez" pasa a ser escribible y obligatorio.
     *
     * La cobertura del set concurrente que SÍ se puede tener hoy —dos escritores
     * de verdad, en dos hilos de verdad— la da el caso de abajo.
     */
    @Test
    fun withTheWholeUpstreamDownExactlyOneBlockIsNamedAsFailed() {
        val gateway = gatewayWith { V1StubResponse(503, "upstream down") }

        val overview = runBlocking { gateway.overview() }

        assertEquals(
            listOf(DashboardOverviewBlock.ACTIVITY),
            overview.failedBlocks,
            "con TODO el upstream caído sólo ACTIVITY puede declararse fallida, porque es la única " +
                "franja con una lectura sin guardar. Si esta lista ha crecido, hay una franja nueva " +
                "capaz de fallar: hace falta un caso que haga fallar DOS a la vez, que es lo que da " +
                "cobertura real al set concurrente de `failedBlocks`",
        )
        // Y la otra mitad: una franja que no puede fallar tampoco puede
        // desaparecer. Las cuatro llegan, vacías de cifras pero presentes.
        assertNotNull(overview.system)
        assertNotNull(overview.attention)
        assertNotNull(overview.pulse)
        assertNotNull(overview.content)
        assertNotNull(overview.economy)
    }

    /**
     * Dos refrescos a la vez sobre el MISMO gateway: cada uno se queda con su
     * propio `failedBlocks`.
     *
     * El gateway es singleton de proceso (`Application.kt` lo construye una vez),
     * así que dos administradores refrescando —o el auto-refresco de 60 s
     * solapado con uno manual— ejecutan `overview()` concurrentemente sobre la
     * misma instancia. Que `failed` sea un `val` LOCAL de la función es lo que
     * impide que el fallo de un refresco aparezca en la respuesta del otro; si
     * alguien lo subiera a campo de la clase "para no reservarlo en cada
     * llamada", este caso se pone rojo y el de arriba no.
     *
     * El punto de cita sobre `auditoria_log` garantiza que los dos refrescos
     * están marcando su franja fallida **a la vez** y no uno detrás de otro: sin
     * él, el caso pasaría también con una ejecución perfectamente secuencial.
     */
    @Test
    fun twoOverviewsAtOnceOnTheSameGatewayEachKeepTheirOwnFailedBlocks() {
        val rendezvous = Rendezvous(expected = 2)
        val gateway = gatewayWith { uri ->
            if (uri.contains("/rest/v1/auditoria_log")) {
                rendezvous.arrive(DashboardOverviewBlock.ACTIVITY)
                V1StubResponse(503, "upstream down")
            } else {
                stubResponseFor(uri)
            }
        }

        val overviews = runBlocking {
            listOf(async { gateway.overview() }, async { gateway.overview() }).awaitAll()
        }

        assertFalse(
            rendezvous.expired(),
            "los dos refrescos no llegaron a estar en vuelo a la vez, así que este caso no dice " +
                "nada sobre la concurrencia entre peticiones",
        )
        overviews.forEachIndexed { index, overview ->
            assertEquals(
                listOf(DashboardOverviewBlock.ACTIVITY),
                overview.failedBlocks,
                "el refresco #${index + 1} no se quedó con su propio failedBlocks",
            )
            assertNull(overview.activity, "el refresco #${index + 1} viajó con una franja fallida rellena")
            assertNotNull(overview.attention, "el refresco #${index + 1} perdió ATTENTION")
            assertNotNull(overview.pulse, "el refresco #${index + 1} perdió PULSE")
            assertNotNull(overview.content, "el refresco #${index + 1} perdió CONTENT")
            assertNotNull(overview.economy, "el refresco #${index + 1} perdió ECONOMY")
        }
    }

    // -----------------------------------------------------------------
    // Ninguna lectura fallida se convierte en un cero
    // -----------------------------------------------------------------

    /**
     * La regla que sostiene todo el panel: **un fallo de lectura nunca es un
     * `0`**. Es `null` + su clave en `unavailableMetrics` —y, desde PC-04, también
     * en `failedMetrics`—, para que la tarjeta se pinte como «no se pudo leer» y no
     * como "hay cero reportes pendientes".
     *
     * Se comprueban las 17 cifras de las cuatro franjas de métrica, con TODO el
     * upstream caído — incluidas la derivada, que es donde más fácil se cuela un
     * cero (una división con un operando ausente no da un número, da una mentira), y
     * las tres que salen de RPC (PC-02 y PC-11), que antes no se leían.
     */
    @Test
    fun noFailedReadEverArrivesAsAZeroInAnyBlock() {
        val gateway = gatewayWith { V1StubResponse(503, "upstream down") }

        val overview = runBlocking { gateway.overview() }

        val attention = assertNotNull(overview.attention)
        val pulse = assertNotNull(overview.pulse)
        val content = assertNotNull(overview.content)
        val economy = assertNotNull(overview.economy)

        val readings = listOf(
            Reading(DashboardOverviewMetricKey.REPORTS_PENDING, attention.reportsPending, attention.unavailableMetrics),
            Reading(
                DashboardOverviewMetricKey.QUESTION_FEEDBACK_PENDING,
                attention.questionFeedbackPending,
                attention.unavailableMetrics,
            ),
            // PC-01: conteo propio de borradores (ya no una resta).
            Reading(DashboardOverviewMetricKey.DRAFT_EXAMS, attention.draftExams, attention.unavailableMetrics),
            Reading(
                DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_7D,
                attention.subscriptionsExpiring7d,
                attention.unavailableMetrics,
            ),
            Reading(DashboardOverviewMetricKey.ACTIVE_USERS_30D, pulse.activeUsers30d, pulse.unavailableMetrics),
            Reading(DashboardOverviewMetricKey.ATTEMPTS_LAST_30D, pulse.attemptsLast30d, pulse.unavailableMetrics),
            Reading(DashboardOverviewMetricKey.PUBLISHED_EXAMS, pulse.publishedExams, pulse.unavailableMetrics),
            // Derivada: `wrongAnswers / totalAnswers`, los dos ausentes.
            Reading(DashboardOverviewMetricKey.GLOBAL_ERROR_RATE, pulse.globalErrorRate, pulse.unavailableMetrics),
            Reading(DashboardOverviewMetricKey.TOTAL_EXAMS, content.totalExams, content.unavailableMetrics),
            Reading(DashboardOverviewMetricKey.TOTAL_QUESTIONS, content.totalQuestions, content.unavailableMetrics),
            Reading(DashboardOverviewMetricKey.TOTAL_RESOURCES, content.totalResources, content.unavailableMetrics),
            Reading(DashboardOverviewMetricKey.IMPORT_BATCHES, content.importBatches, content.unavailableMetrics),
            Reading(
                DashboardOverviewMetricKey.SUBSCRIPTIONS_ACTIVE,
                economy.subscriptionsActive,
                economy.unavailableMetrics,
            ),
            Reading(
                DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_30D,
                economy.subscriptionsExpiring30d,
                economy.unavailableMetrics,
            ),
            Reading(
                DashboardOverviewMetricKey.COMMERCIAL_PRODUCTS_ACTIVE,
                economy.commercialProductsActive,
                economy.unavailableMetrics,
            ),
            // PC-11: las dos sumas de la RPC de totales, caída con todo lo demás.
            Reading(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS, economy.grossRevenueCents, economy.unavailableMetrics),
            Reading(
                DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION,
                economy.ticketsInCirculation,
                economy.unavailableMetrics,
            ),
        )
        val failed = (attention.failedMetrics + pulse.failedMetrics + content.failedMetrics + economy.failedMetrics).toSet()

        // Este cierre NO es `assertEquals(15, readings.size)`.
        //
        // Aquella era una tautología: contaba los elementos de una lista literal
        // escrita tres líneas más arriba, así que no podía fallar salvo que
        // alguien editara esa misma lista. En particular NO protegía contra lo
        // único que de verdad importa aquí: que el contrato gane una 16.ª métrica
        // leída y que esa métrica no tenga camino NO_DATA. La lista literal
        // seguiría teniendo 15 elementos y el caso seguiría verde.
        //
        // Se compara contra lo que dicen las PROPIAS franjas. Con todo el
        // upstream caído, `unavailableMetrics` de cada franja contiene sus
        // lecturas fallidas (NO_DATA) más sus huecos estructurales
        // (NOT_INSTRUMENTED, que no son lecturas y viajan aparte en
        // `notInstrumented`). Quitando los segundos queda exactamente el conjunto
        // de cifras que este caso tiene que cubrir — derivado del contrato, no
        // transcrito de él.
        val structuralGaps = (
            attention.notInstrumented + pulse.notInstrumented +
                content.notInstrumented + economy.notInstrumented
            ).map { it.key }.toSet()
        val declaredNoData = (
            attention.unavailableMetrics + pulse.unavailableMetrics +
                content.unavailableMetrics + economy.unavailableMetrics
            ).toSet() - structuralGaps

        assertEquals(
            declaredNoData,
            readings.map { it.key }.toSet(),
            "las cifras que este caso cubre y las que las franjas declaran NO_DATA con todo el " +
                "upstream caído han dejado de coincidir: o el contrato ganó una métrica leída sin " +
                "camino NO_DATA, o este caso se quedó atrás",
        )
        readings.forEach { (key, value, unavailable) ->
            assertNull(value, "'$key' llegó con valor ($value) tras una lectura fallida")
            assertTrue(
                key in unavailable,
                "'$key' no se pudo leer y NO está en unavailableMetrics: el cliente pintaría la " +
                    "tarjeta como si el dato fuera bueno",
            )
            assertTrue(
                key in failed,
                "'$key' no se pudo leer y NO está en failedMetrics: la pantalla diría «sin dato» " +
                    "donde tiene que decir «no se pudo leer»",
            )
        }
    }

    private data class Reading(val key: String, val value: Any?, val unavailable: List<String>)

    // -----------------------------------------------------------------
    // ?sections= sigue siendo el mismo contrato
    // -----------------------------------------------------------------

    @Test
    fun theSectionsParameterStillSelectsBlocksAfterParallelising() = testApplication {
        application { dashboardTestModule(gatewayWith { stubResponseFor(it) }) }

        val body = client.get("/api/v1/admin/dashboard/overview?sections=attention,pulse").bodyAsText()

        assertTrue(body.contains("\"attention\":"), body)
        assertTrue(body.contains("\"pulse\":"), body)
        assertFalse(body.contains("\"economy\":"), "una franja no pedida no viaja: $body")
        assertFalse(body.contains("\"system\":"), "una franja no pedida no viaja: $body")
    }

    /** PC-08: nombra las válidas y NO repite la entrada del cliente. */
    @Test
    fun anUnknownSectionStillAnswers400NamingTheValidOnes() = testApplication {
        application { dashboardTestModule(gatewayWith { stubResponseFor(it) }) }

        val response = client.get("/api/v1/admin/dashboard/overview?sections=pulse,tesoreria")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("tesoreria"), "el 400 repite la entrada del cliente: $body")
        DashboardOverviewBlock.ALL.forEach { valid ->
            assertTrue(body.contains(valid), "el 400 debe nombrar la franja válida '$valid': $body")
        }
    }

    // -----------------------------------------------------------------
    // Infraestructura
    // -----------------------------------------------------------------

    /**
     * Punto de cita: cada franja avisa de que llegó y espera a las demás.
     *
     * El timeout lo paga **una sola** franja: en cuanto una expira, se marca
     * [expired] y las siguientes pasan de largo. Sin eso, un fallo costaría
     * cinco timeouts seguidos y el test tardaría un minuto en decir lo que
     * puede decir en diez segundos.
     */
    private inner class Rendezvous(expected: Int) {
        private val latch = CountDownLatch(expected)
        private val arrivals = CopyOnWriteArrayList<String>()
        private val expired = AtomicBoolean(false)

        fun arrive(block: String) {
            arrivals += block
            latch.countDown()
            if (expired.get()) return
            if (!latch.await(gateTimeoutSeconds, TimeUnit.SECONDS)) expired.set(true)
        }

        fun arrived(): Set<String> = arrivals.toSet()

        fun expired(): Boolean = expired.get()
    }

    /**
     * Conteos con total 0, una cronología vacía pero legible y las dos RPC con su
     * forma real: nada falla salvo lo que el caso rompa.
     */
    private fun stubResponseFor(uri: String): V1StubResponse = when {
        uri.contains("/rest/v1/auditoria_log") -> V1StubResponse(200, "[]")
        uri.contains("/rest/v1/rpc/admin_dashboard_active_users") -> V1StubResponse(200, "0")
        uri.contains("/rest/v1/rpc/admin_dashboard_economy_totals") -> V1StubResponse(
            200,
            """[{"gross_revenue_cents":0,"paid_purchases":0,"tickets_in_circulation":0}]""",
        )
        else -> V1StubResponse(206, "[]", contentRange = "*/0")
    }

    private fun Application.dashboardTestModule(gateway: AdminDashboardOverviewGateway) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = false })
        }
        routing { adminDashboardOverviewRoutes(gateway) }
    }

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): AdminDashboardOverviewGateway = AdminDashboardOverviewGateway(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
    )
}
