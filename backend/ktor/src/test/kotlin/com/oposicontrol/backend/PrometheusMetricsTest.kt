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
}

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
