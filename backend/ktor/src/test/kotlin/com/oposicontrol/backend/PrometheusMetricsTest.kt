package com.oposicontrol.backend

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrometheusMetricsTest {
    @Test
    fun metricsEndpointExportsKtorHistogramAndJvmMetrics() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        application {
            installBackendPrometheusMetrics(registry)
            routing {
                prometheusMetricsRoute(registry)
                get("/probe") { call.respondText("ok") }
            }
        }

        assertEquals(HttpStatusCode.OK, client.get("/probe").status)
        val response = client.get("/metrics")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"].orEmpty().startsWith("text/plain"))
        assertTrue(body.contains("ktor_http_server_requests_seconds_count"))
        assertTrue(body.contains("ktor_http_server_requests_seconds_bucket"))
        assertTrue(body.contains("route=\"/probe\""))
        assertTrue(body.contains("status=\"200\""))
        assertTrue(body.contains("jvm_memory_used_bytes"))
    }

    @Test
    fun parsesOneFiniteInstantVectorValue() {
        assertEquals(
            12.75,
            parsePrometheusInstantValue(successBody("12.75")),
        )
    }

    @Test
    fun emptyMalformedFailedAndNonFiniteResponsesStayUnavailable() {
        assertNull(parsePrometheusInstantValue(""))
        assertNull(parsePrometheusInstantValue("""{"status":"error"}"""))
        assertNull(parsePrometheusInstantValue("""{"status":"success","data":{"result":[]}}"""))
        assertNull(parsePrometheusInstantValue("""{"status":"success","data":{"resultType":"matrix","result":[]}}"""))
        assertNull(parsePrometheusInstantValue(successBody("NaN")))
        assertNull(parsePrometheusInstantValue(successBody("+Inf")))
    }

    @Test
    fun gatewayQueriesAllPromqlAndConvertsUnitsWithoutInventingValues() {
        assertTrue(API_LATENCY_QUERY.contains("route!=\"/metrics\""))
        assertTrue(ERRORS_24H_QUERY.contains("status=~\"5..\""))
        assertTrue(ERRORS_24H_QUERY.contains("or on() vector(0)"))
        assertTrue(ERRORS_24H_QUERY.contains("and on()"))
        assertTrue(ERRORS_24H_QUERY.contains("offset 24h"))
        val queries = CopyOnWriteArrayList<String>()
        withPrometheusServer { server ->
            server.createContext("/api/v1/query") { exchange ->
                val query = exchange.requestURI.rawQuery
                    .substringAfter("query=", "")
                    .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                queries += query
                val value = when (query) {
                    CPU_QUERY -> "17.25"
                    RAM_QUERY -> "63.5"
                    API_LATENCY_QUERY -> "0.2476"
                    ERRORS_24H_QUERY -> "4"
                    else -> "NaN"
                }
                val body = successBody(value).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            val snapshot = PrometheusSystemMetricsGateway(server.baseUrl()).snapshot()

            assertEquals(17.25, snapshot.cpuPercent)
            assertEquals(63.5, snapshot.ramPercent)
            assertEquals(248, snapshot.apiLatencyMs)
            assertEquals(4, snapshot.errors24h)
            assertEquals(setOf(CPU_QUERY, RAM_QUERY, API_LATENCY_QUERY, ERRORS_24H_QUERY), queries.toSet())
        }
    }

    @Test
    fun oneUnavailableSeriesDoesNotEraseTheOtherRealValues() {
        withPrometheusServer { server ->
            server.createContext("/api/v1/query") { exchange ->
                val query = URLDecoder.decode(
                    exchange.requestURI.rawQuery.substringAfter("query=", ""),
                    StandardCharsets.UTF_8,
                )
                val body = if (query == RAM_QUERY) {
                    """{"status":"success","data":{"resultType":"vector","result":[]}}"""
                } else {
                    successBody("0")
                }.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            val snapshot = PrometheusSystemMetricsGateway(server.baseUrl()).snapshot()

            assertEquals(0.0, snapshot.cpuPercent)
            assertNull(snapshot.ramPercent)
            assertEquals(0, snapshot.apiLatencyMs)
            assertEquals(0, snapshot.errors24h)
        }
    }

    @Test
    fun unavailablePrometheusReturnsAnAllNullSnapshot() {
        val gateway = PrometheusSystemMetricsGateway(
            baseUrl = "http://127.0.0.1:1",
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build(),
            requestTimeout = Duration.ofMillis(200),
        )

        assertEquals(SystemMetricsSnapshot(null, null, null, null), gateway.snapshot())
    }

    @Test
    fun malformedPrometheusUrlAlsoFallsBackInsteadOfBreakingAdminStatus() {
        val gateway = PrometheusSystemMetricsGateway("not a valid URL")

        assertEquals(SystemMetricsSnapshot(null, null, null, null), gateway.snapshot())
    }

    @Test
    fun adminStatusWireContractUsesTypedPrometheusFieldsAndKeepsZero() {
        val dto = AdminSystemStatusDto(
            overallStatus = SystemStatusLevelWire.OPERATIVE,
            ktorStatus = SystemStatusLevelWire.OPERATIVE,
            supabaseStatus = SystemStatusLevelWire.OPERATIVE,
            workerStatus = SystemStatusLevelWire.UNKNOWN,
            cpuPercent = 0.0,
            ramPercent = 63.5,
            apiLatencyMs = 0,
            errors24h = 0,
            updatedAt = "2026-09-22T12:00:00Z",
        )

        val wire = Json.encodeToString(AdminSystemStatusResponse(dto))

        assertTrue(wire.contains("\"cpuPercent\":0.0"))
        assertTrue(wire.contains("\"ramPercent\":63.5"))
        assertTrue(wire.contains("\"apiLatencyMs\":0"))
        assertTrue(wire.contains("\"errors24h\":0"))
        assertTrue(wire.contains("\"updatedAt\":\"2026-09-22T12:00:00Z\""))
    }

    @Test
    fun adminGatewayMergesPrometheusSnapshotAndMarksOnlyMissingSeriesUnavailable() {
        withPrometheusServer { supabase ->
            supabase.createContext("/rest/v1/especialidades") { exchange ->
                exchange.responseHeaders.add("Content-Range", "0-0/1")
                val body = "[]".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            val gateway = AdminV1Gateway.forTesting(
                supabaseUrl = supabase.baseUrl(),
                serviceRoleKey = "test-secret",
                json = Json { ignoreUnknownKeys = true },
                client = HttpClient.newHttpClient(),
                systemMetrics = SystemMetricsProvider {
                    SystemMetricsSnapshot(
                        cpuPercent = 12.5,
                        ramPercent = null,
                        apiLatencyMs = 87,
                        errors24h = 0,
                    )
                },
            )

            val status = gateway.getSystemStatus()

            assertEquals(12.5, status.cpuPercent)
            assertNull(status.ramPercent)
            assertEquals(87, status.apiLatencyMs)
            assertEquals(0, status.errors24h)
            assertFalse(SystemStatusNotInstrumented.CPU in status.notInstrumented)
            assertTrue(SystemStatusNotInstrumented.MEMORY in status.notInstrumented)
            assertFalse(SystemStatusNotInstrumented.API_LATENCY in status.notInstrumented)
            assertFalse(SystemStatusNotInstrumented.API_ERRORS in status.notInstrumented)
        }
    }

    // -----------------------------------------------------------------
    // Contrato "sólo Double finito o null": ver AGENTS.md / incidente
    // JsonConvertException del 2026-09-23 en Sistema → Estado.
    // -----------------------------------------------------------------

    @Test
    fun finiteOrNullKeepsRealMeasurementsButRejectsNaNAndBothInfinities() {
        assertEquals(2.91, 2.91.finiteOrNull())
        assertEquals(0.0, 0.0.finiteOrNull(), "Un cero medido es un dato real, no una ausencia.")
        assertNull((null as Double?).finiteOrNull())
        assertNull(Double.NaN.finiteOrNull())
        assertNull(Double.POSITIVE_INFINITY.finiteOrNull())
        assertNull(Double.NEGATIVE_INFINITY.finiteOrNull())
    }

    @Test
    fun snapshotConvertsNaNAndBothInfinitiesToNullInsteadOfCoercingOrRoundingThem() {
        withPrometheusServer { server ->
            server.createContext("/api/v1/query") { exchange ->
                val query = URLDecoder.decode(
                    exchange.requestURI.rawQuery.substringAfter("query=", ""),
                    StandardCharsets.UTF_8,
                )
                val value = when (query) {
                    CPU_QUERY -> "NaN"
                    RAM_QUERY -> "+Inf"
                    API_LATENCY_QUERY -> "-Inf"
                    else -> "4"
                }
                val body = successBody(value).toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            val snapshot = PrometheusSystemMetricsGateway(server.baseUrl()).snapshot()

            // Sin el finiteOrNull() en snapshot(), coerceIn(0.0, 100.0) deja pasar NaN
            // sin recortarlo y roundToLong() convierte +Infinity/-Infinity en
            // Long.MAX_VALUE/MIN_VALUE: un dato inventado con apariencia de real.
            assertNull(snapshot.cpuPercent, "coerceIn no filtra NaN: debe recortarse antes.")
            assertNull(snapshot.ramPercent, "coerceIn no filtra +Infinity: debe recortarse antes.")
            assertNull(snapshot.apiLatencyMs, "roundToLong(-Infinity) da Long.MIN_VALUE, no ausencia.")
            assertEquals(4, snapshot.errors24h, "Una serie no finita no debe arrastrar a las demás.")
        }
    }

    @Test
    fun adminGatewayNeverLeaksNonFiniteMetricsEvenFromAMisbehavingProvider() {
        withPrometheusServer { supabase ->
            supabase.createContext("/rest/v1/especialidades") { exchange ->
                exchange.responseHeaders.add("Content-Range", "0-0/1")
                val body = "[]".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            // Un SystemMetricsProvider que no pase por PrometheusSystemMetricsGateway
            // (test doble, implementación futura) tampoco puede colar un NaN: la
            // frontera real de serialización es AdminV1Gateway.getSystemStatus().
            val gateway = AdminV1Gateway.forTesting(
                supabaseUrl = supabase.baseUrl(),
                serviceRoleKey = "test-secret",
                json = Json { ignoreUnknownKeys = true },
                client = HttpClient.newHttpClient(),
                systemMetrics = SystemMetricsProvider {
                    SystemMetricsSnapshot(
                        cpuPercent = Double.NaN,
                        ramPercent = Double.POSITIVE_INFINITY,
                        apiLatencyMs = 42,
                        errors24h = 0,
                    )
                },
            )

            val status = gateway.getSystemStatus()

            assertNull(status.cpuPercent)
            assertNull(status.ramPercent)
            assertTrue(SystemStatusNotInstrumented.CPU in status.notInstrumented)
            assertTrue(SystemStatusNotInstrumented.MEMORY in status.notInstrumented)
            // Y sobre todo: codificar la respuesta no lanza. Antes de este fix,
            // kotlinx.serialization rechaza NaN/Infinity al serializar y el
            // `respond` habría fallado con 500 en vez de devolver `null`. Con
            // `explicitNulls = false` (igual que Application.kt) el campo ausente
            // decodifica igualmente a `null`, así que se verifica sobre el
            // round-trip real, no sobre el texto literal del cable.
            val wire = Json.encodeToString(AdminSystemStatusResponse(status))
            val decoded = Json.decodeFromString(AdminSystemStatusResponse.serializer(), wire)
            assertNull(decoded.data.cpuPercent)
            assertNull(decoded.data.ramPercent)
        }
    }

    @Test
    fun adminSystemStatusResponseRoundTripsWithTheExactJsonBackendAndDesktopUse() {
        // Copia literal de Application.kt: el Json que instala el ContentNegotiation
        // del backend real, no una versión de test relajada.
        val backendJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = false
        }
        // Copia literal de core/network/ApiClientFactory.kt: el Json que usa el
        // cliente HTTP de Desktop. Si vuelve a haber un desfase de tipo entre este
        // contrato y el DTO real de Desktop (SystemStatusDto), esta prueba y las de
        // KtorSystemStatusRepositoryImplTest son los dos sitios a actualizar juntos.
        val desktopJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
            explicitNulls = false
        }

        val cases = listOf(
            "cpu normal" to 2.91,
            "cpu cero medido" to 0.0,
            "cpu no disponible" to null,
        )
        for ((label, cpu) in cases) {
            val dto = AdminSystemStatusDto(
                overallStatus = SystemStatusLevelWire.OPERATIVE,
                ktorStatus = SystemStatusLevelWire.OPERATIVE,
                supabaseStatus = SystemStatusLevelWire.OPERATIVE,
                workerStatus = SystemStatusLevelWire.UNKNOWN,
                cpuPercent = cpu,
                ramPercent = 13.023566583584689,
                apiLatencyMs = 3,
                errors24h = null,
                updatedAt = "2026-09-23T07:22:03Z",
            )
            val wire = backendJson.encodeToString(AdminSystemStatusResponse.serializer(), AdminSystemStatusResponse(dto))

            val decoded = desktopJson.decodeFromString(DesktopSystemStatusResponseMirror.serializer(), wire)

            assertEquals(cpu, decoded.data.cpuPercent, label)
            assertEquals(13.023566583584689, decoded.data.ramPercent, label)
            assertEquals(3L, decoded.data.apiLatencyMs, label)
            assertNull(decoded.data.errors24h, label)
        }
    }
}

/**
 * Espejo, campo a campo, de `SystemStatusDto` en
 * `data/.../KtorSystemStatusRepositoryImpl.kt`. Vive aquí porque el módulo
 * `backend` no depende del módulo `data`; si uno cambia, el otro debe
 * actualizarse a la vez (lo cubre [adminSystemStatusResponseRoundTripsWithTheExactJsonBackendAndDesktopUse]).
 */
@kotlinx.serialization.Serializable
private data class DesktopSystemStatusDtoMirror(
    val overallStatus: String = "",
    val ktorStatus: String = "",
    val supabaseStatus: String = "",
    val workerStatus: String = "",
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val mobileCrashes24h: Int? = null,
    val queueDepth: Int? = null,
    val updatedAt: String = "",
    val notInstrumented: List<String> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class DesktopSystemStatusResponseMirror(val data: DesktopSystemStatusDtoMirror)

private fun successBody(value: String): String =
    """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"$value"]}]}}"""

private fun HttpServer.baseUrl(): String = "http://127.0.0.1:${address.port}"

private inline fun withPrometheusServer(block: (HttpServer) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try {
        server.start()
        block(server)
    } finally {
        server.stop(0)
    }
}
