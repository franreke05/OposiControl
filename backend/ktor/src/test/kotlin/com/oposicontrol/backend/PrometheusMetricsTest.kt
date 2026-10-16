package com.oposicontrol.backend

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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
import java.util.Base64
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
                prometheusMetricsRoute(registry) { if (it == "APP_ENV") "dev" else null }
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
        // Las dos series de las que sale «versión desplegada» y «arrancada hace…» en el estado del sistema. La segunda la
        // pone Ktor por defecto (UptimeMetrics); si un día deja de hacerlo, PROCESS_START_TIME_QUERY se queda sin serie.
        assertTrue(body.lines().any { it.startsWith("build_info{") && "sha=\"" in it && it.trim().endsWith(" 1") }, body.take(400))
        assertTrue(body.lines().any { it.startsWith("process_start_time_seconds ") })
    }

    // -----------------------------------------------------------------
    // Versión horneada en el jar (`build_info`).
    // -----------------------------------------------------------------

    @Test
    fun theBuildShaComesFromTheBakedResourceAndIsNeverInvented() {
        assertEquals("886dac044b6e", backendBuildSha { "sha=886dac044b6e\n".byteInputStream() })
        assertEquals("886dac044b6e", backendBuildSha { "#comentario\nsha =  886dac044b6e  \n".byteInputStream() })
        // Sin recurso, con el recurso vacío o si leerlo falla: `desconocido`, que es también lo que hornea el build sin git.
        assertEquals(UNKNOWN_BUILD_SHA, backendBuildSha { null })
        assertEquals(UNKNOWN_BUILD_SHA, backendBuildSha { "sha=\n".byteInputStream() })
        assertEquals(UNKNOWN_BUILD_SHA, backendBuildSha { error("boom") })
        // El recurso real que genera `generateBuildInfo` está en el classpath de las pruebas y tiene forma de SHA o es
        // el marcador; nunca vacío.
        assertTrue(Regex("[0-9a-f]{7,40}|$UNKNOWN_BUILD_SHA").matches(backendBuildSha()), backendBuildSha())
    }

    @Test
    fun theBuildInfoGaugeIsAlwaysOneAndCarriesTheShaAsALabel() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        registerBuildInfoGauge(registry, sha = "886dac044b6e")
        registerBuildInfoGauge(registry, sha = "886dac044b6e")

        assertEquals(listOf("build_info{sha=\"886dac044b6e\"} 1"), registry.scrape().lines().filter { it.startsWith("build_info") })
    }

    // -----------------------------------------------------------------
    // Réplicas, alertas y tráfico de Supabase (vectores con etiquetas).
    // -----------------------------------------------------------------

    @Test
    fun theVectorParserKeepsLabelsAndTellsAnEmptyAnswerFromAFailedOne() {
        assertEquals(
            listOf(
                PrometheusSample(mapOf("replica" to "bot-1", "profile" to "bot"), 1.0),
                PrometheusSample(mapOf("replica" to "bot-2", "profile" to "bot"), 0.0),
            ),
            parsePrometheusVector(vectorBody(mapOf("replica" to "bot-1", "profile" to "bot") to "1", mapOf("replica" to "bot-2", "profile" to "bot") to "0")),
        )
        // Vacío = Prometheus contestó y no hay series. No es lo mismo que no haber contestado.
        assertEquals(emptyList(), parsePrometheusVector(vectorBody()))
        assertNull(parsePrometheusVector("""{"status":"error","data":{"resultType":"vector","result":[]}}"""))
        assertNull(parsePrometheusVector("""{"status":"success","data":{"resultType":"matrix","result":[]}}"""))
        assertNull(parsePrometheusVector("not-json"))
        // Una muestra sin valor finito se descarta sola; las demás siguen valiendo.
        assertEquals(
            listOf(PrometheusSample(mapOf("replica" to "bot-1"), 1.0)),
            parsePrometheusVector(vectorBody(mapOf("replica" to "bot-1") to "1", mapOf("replica" to "bot-2") to "NaN")),
        )
    }

    @Test
    fun theTopologyQueriesNameTheSeriesTheBackendReallyExports() {
        // Los nombres salen del código y de las reglas, no de una suposicion: si alguno cambia, cambia aquí también.
        assertEquals("up{job=\"ktor\"}", REPLICAS_UP_QUERY)
        assertEquals(UPSTREAM_SUPABASE_UP_METRIC, REPLICAS_SUPABASE_UP_QUERY)
        assertTrue(BUILD_INFO_QUERY.startsWith(BUILD_INFO_METRIC + "{"))
        // Prometheus también publica su propio `process_start_time_seconds`: sin el filtro saldría como una «réplica».
        assertTrue(PROCESS_START_TIME_QUERY.contains("job=\"ktor\""))
        assertEquals("ALERTS{alertstate=\"firing\"}", FIRING_ALERTS_QUERY)
        // Micrometer exporta el contador con unidad `bytes` como `<nombre>_total` (CompressedUpstreamHttpClientTest).
        val exported = UPSTREAM_WIRE_BYTES_METRIC + "_total"
        listOf(
            SUPABASE_EGRESS_24H_QUERY to "[24h]",
            SUPABASE_EGRESS_7D_QUERY to "[7d]",
            SUPABASE_EGRESS_BY_TARGET_24H_QUERY to "[24h]",
            SUPABASE_EGRESS_BY_TARGET_7D_QUERY to "[7d]",
        ).forEach { (query, window) -> assertTrue("increase($exported$window)" in query, query) }
        assertTrue(SUPABASE_EGRESS_BY_TARGET_24H_QUERY.startsWith("sum by (target)"))
        assertEquals("count($exported offset 7d)", SUPABASE_EGRESS_HISTORY_7D_QUERY)
    }

    /** Un Prometheus falso: contesta cada consulta fija con el vector que le toque, y `null` = 500. */
    private fun withTopology(answers: Map<String, String?>, block: (SystemTopologySnapshot, List<String>) -> Unit) {
        val queries = CopyOnWriteArrayList<String>()
        withPrometheusServer { server ->
            server.createContext("/api/v1/query") { exchange ->
                val query = URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("query=", ""), StandardCharsets.UTF_8)
                queries += query
                val answer = if (query in answers) answers[query] else vectorBody()
                val body = (answer ?: "boom").toByteArray()
                exchange.sendResponseHeaders(if (answer == null) 500 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            block(PrometheusSystemMetricsGateway(server.baseUrl()).topology(), queries)
        }
    }

    private fun replicaLabels(replica: String, profile: String) = mapOf("job" to "ktor", "replica" to replica, "profile" to profile)

    private val healthyTopology = mapOf(
        REPLICAS_UP_QUERY to vectorBody(
            replicaLabels("control-1", "control") to "1",
            replicaLabels("bot-2", "bot") to "1",
            replicaLabels("bot-1", "bot") to "1",
        ),
        REPLICAS_SUPABASE_UP_QUERY to vectorBody(
            replicaLabels("bot-1", "bot") to "1",
            replicaLabels("bot-2", "bot") to "1",
            replicaLabels("control-1", "control") to "1",
        ),
        BUILD_INFO_QUERY to vectorBody(
            (replicaLabels("bot-1", "bot") + ("sha" to "886dac044b6e")) to "1",
            (replicaLabels("bot-2", "bot") + ("sha" to "886dac044b6e")) to "1",
            (replicaLabels("control-1", "control") + ("sha" to "886dac044b6e")) to "1",
        ),
        PROCESS_START_TIME_QUERY to vectorBody(
            replicaLabels("bot-1", "bot") to "1.760000000123E9",
            replicaLabels("bot-2", "bot") to "1.760000100E9",
            replicaLabels("control-1", "control") to "1.760000200E9",
        ),
        FIRING_ALERTS_QUERY to vectorBody(),
        SUPABASE_EGRESS_24H_QUERY to vectorBody(emptyMap<String, String>() to "41234567.8"),
        SUPABASE_EGRESS_7D_QUERY to vectorBody(emptyMap<String, String>() to "2.5e8"),
        SUPABASE_EGRESS_BY_TARGET_24H_QUERY to vectorBody(mapOf("target" to "rest") to "40000000", mapOf("target" to "auth") to "1234567.8"),
        SUPABASE_EGRESS_BY_TARGET_7D_QUERY to vectorBody(mapOf("target" to "rest") to "2.4e8", mapOf("target" to "storage") to "0"),
        SUPABASE_EGRESS_HISTORY_7D_QUERY to vectorBody(emptyMap<String, String>() to "6"),
    )

    @Test
    fun topologyReadsEveryReplicaWithWhatItPublishes() = withTopology(healthyTopology) { topology, queries ->
        assertTrue(topology.prometheusResponded)
        assertEquals(
            listOf(
                ReplicaReading("bot-1", "bot", up = true, supabaseUp = true, sha = "886dac044b6e", startedAtEpochSeconds = 1.760000000123E9),
                ReplicaReading("bot-2", "bot", up = true, supabaseUp = true, sha = "886dac044b6e", startedAtEpochSeconds = 1.7600001E9),
                ReplicaReading("control-1", "control", up = true, supabaseUp = true, sha = "886dac044b6e", startedAtEpochSeconds = 1.7600002E9),
            ),
            topology.replicas,
            "una fila por réplica, en orden estable",
        )
        assertEquals(emptyList(), topology.alerts, "ninguna alerta activa es una lista vacía, no una lectura fallida")
        assertEquals(
            SupabaseEgressReading(
                bytes24h = 41_234_568,
                bytes7d = 250_000_000,
                byTarget24h = mapOf("rest" to 40_000_000L, "auth" to 1_234_568L),
                // Un destino medido a cero se conserva como cero.
                byTarget7d = mapOf("rest" to 240_000_000L, "storage" to 0L),
                hasSevenDaysOfHistory = true,
            ),
            topology.egress,
        )
        // Solo las consultas fijas de este fichero: el panel no puede hacer llegar una suya.
        assertEquals(healthyTopology.keys, queries.toSet())
    }

    @Test
    fun aDownReplicaStaysInTheListWithoutTheDataItCanNoLongerPublish() {
        val answers = healthyTopology + mapOf(
            REPLICAS_UP_QUERY to vectorBody(
                replicaLabels("bot-1", "bot") to "1",
                replicaLabels("bot-2", "bot") to "0",
                replicaLabels("control-1", "control") to "1",
            ),
            // Las series de una réplica caída caducan: Prometheus ya no las devuelve.
            REPLICAS_SUPABASE_UP_QUERY to vectorBody(replicaLabels("bot-1", "bot") to "1", replicaLabels("control-1", "control") to "0"),
            BUILD_INFO_QUERY to vectorBody(
                (replicaLabels("bot-1", "bot") + ("sha" to "886dac044b6e")) to "1",
                (replicaLabels("control-1", "control") + ("sha" to "0123456789ab")) to "1",
            ),
            PROCESS_START_TIME_QUERY to vectorBody(replicaLabels("bot-1", "bot") to "1.76E9"),
            FIRING_ALERTS_QUERY to vectorBody(
                mapOf("alertname" to "KtorBotPoolDegraded", "alertstate" to "firing", "severity" to "warning") to "1",
                mapOf("alertname" to "KtorInstanceDown", "alertstate" to "firing", "severity" to "critical", "replica" to "bot-2", "profile" to "bot") to "1",
            ),
        )
        withTopology(answers) { topology, _ ->
            assertEquals(
                listOf(
                    ReplicaReading("bot-1", "bot", up = true, supabaseUp = true, sha = "886dac044b6e", startedAtEpochSeconds = 1.76E9),
                    ReplicaReading("bot-2", "bot", up = false),
                    // En pie pero sin ver Supabase: es un 0 medido, no una ausencia.
                    ReplicaReading("control-1", "control", up = true, supabaseUp = false, sha = "0123456789ab"),
                ),
                topology.replicas,
            )
            assertEquals(
                listOf(
                    // Las críticas primero. `ALERTS` no trae las anotaciones de la regla: el resumen queda sin dato.
                    AlertReading("KtorInstanceDown", severity = "critical", summary = null, replica = "bot-2"),
                    AlertReading("KtorBotPoolDegraded", severity = "warning"),
                ),
                topology.alerts,
            )
        }
    }

    @Test
    fun aYoungSeriesHasNoWeekOfHistoryAndEachEgressWindowFailsOnItsOwn() {
        val answers = healthyTopology + mapOf(
            SUPABASE_EGRESS_7D_QUERY to null,
            SUPABASE_EGRESS_BY_TARGET_7D_QUERY to vectorBody(),
            SUPABASE_EGRESS_HISTORY_7D_QUERY to vectorBody(),
        )
        withTopology(answers) { topology, _ ->
            assertEquals(41_234_568, topology.egress.bytes24h)
            assertNull(topology.egress.bytes7d, "una consulta fallida no borra la otra ni se convierte en cero")
            assertNull(topology.egress.byTarget7d)
            assertFalse(topology.egress.hasSevenDaysOfHistory)
            assertTrue(topology.prometheusResponded)
        }
    }

    @Test
    fun aPrometheusThatAnswersWithoutSeriesIsNotTheSameAsOneThatDoesNotAnswer() {
        // Contesta, pero no raspa ningún Ktor ni tiene el contador: no hay réplicas que enseñar (null, no «cero
        // réplicas»), sí se sabe que no hay alertas, y no hay bytes (null, no 0).
        withTopology(emptyMap()) { topology, _ ->
            assertTrue(topology.prometheusResponded)
            assertNull(topology.replicas)
            assertEquals(emptyList(), topology.alerts)
            assertEquals(SupabaseEgressReading(), topology.egress)
        }

        val silent = PrometheusSystemMetricsGateway(
            baseUrl = "http://127.0.0.1:1",
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build(),
            requestTimeout = Duration.ofMillis(200),
        ).topology()
        assertEquals(SystemTopologySnapshot(prometheusResponded = false), silent)
        assertEquals(SystemTopologySnapshot(prometheusResponded = false), PrometheusSystemMetricsGateway("not a valid URL").topology())
    }

    @Test
    fun aReplicaPublishingTwoVersionsAtOnceGetsNoneRatherThanARandomOne() {
        val answers = healthyTopology + mapOf(
            BUILD_INFO_QUERY to vectorBody(
                (replicaLabels("bot-1", "bot") + ("sha" to "aaaaaaaaaaaa")) to "1",
                (replicaLabels("bot-1", "bot") + ("sha" to "bbbbbbbbbbbb")) to "1",
                (replicaLabels("bot-2", "bot") + ("sha" to "aaaaaaaaaaaa")) to "1",
            ),
        )
        withTopology(answers) { topology, _ ->
            assertEquals(listOf(null, "aaaaaaaaaaaa", null), topology.replicas?.map { it.sha })
        }
    }

    @Test
    fun theProductionGatewayIsBothProvidersSoStatusUsesOneClient() {
        val gateway: SystemMetricsProvider = PrometheusSystemMetricsGateway("http://127.0.0.1:1")

        assertTrue(gateway is SystemTopologyProvider)
    }

    private fun metricsApp(env: Map<String, String>, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            application {
                routing { prometheusMetricsRoute(registry) { env[it] } }
            }
            block()
        }

    @Test
    fun metricsReturns200WithoutConfiguredTokenAndWithCorrectBearer() {
        metricsApp(mapOf("APP_ENV" to "dev")) {
            assertEquals(HttpStatusCode.OK, client.get("/metrics").status)
        }
        metricsApp(mapOf("METRICS_SCRAPE_TOKEN" to "scrape-secret")) {
            val response = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-secret") }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun metricsReturns401WithWrongOrMissingBearerWhenTokenConfigured() {
        metricsApp(mapOf("METRICS_SCRAPE_TOKEN" to "scrape-secret")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
            val wrong = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer nope") }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertFalse(wrong.bodyAsText().contains("jvm_"))
        }
    }

    /** DEBT-095(b): sin token de scrape, produccion (o APP_ENV ausente) NO sirve /metrics. */
    @Test
    fun metricsIs404WithoutScrapeTokenOutsideDevelopment() {
        listOf(emptyMap(), mapOf("APP_ENV" to "production"), mapOf("APP_ENV" to "staging")).forEach { env ->
            metricsApp(env) {
                val response = client.get("/metrics")
                assertEquals(HttpStatusCode.NotFound, response.status, "$env")
                assertFalse(response.bodyAsText().contains("jvm_"))
            }
        }
        metricsApp(mapOf("APP_ENV" to "production", "METRICS_SCRAPE_TOKEN" to "scrape-secret")) {
            val ok = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-secret") }
            assertEquals(HttpStatusCode.OK, ok.status)
        }
    }

    @Test
    fun metricsReturns404WhenDisabled() {
        metricsApp(mapOf("METRICS_ENABLED" to "false", "METRICS_SCRAPE_TOKEN" to "scrape-secret")) {
            val response = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-secret") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    /** PF3-3-03: `METRICS_ENABLED` se normaliza (trim + sin mayusculas); solo "false" apaga, cualquier otro valor no. */
    @Test
    fun metricsEnabledFlagIsTrimmedAndCaseInsensitive() {
        listOf(" false ", "FALSE", "False\t").forEach { value ->
            metricsApp(mapOf("APP_ENV" to "dev", "METRICS_ENABLED" to value)) {
                assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status, "'$value'")
            }
        }
        listOf("true", "", "0", "no").forEach { value ->
            metricsApp(mapOf("APP_ENV" to "dev", "METRICS_ENABLED" to value)) {
                assertEquals(HttpStatusCode.OK, client.get("/metrics").status, "'$value'")
            }
        }
    }

    /** PF3-3-03: el token se compara ya con trim; uno solo de espacios equivale a NO configurado (documentado, SEC-007). */
    @Test
    fun scrapeTokenIsTrimmedAndABlankTokenMeansNotConfigured() {
        metricsApp(mapOf("METRICS_SCRAPE_TOKEN" to "  scrape-secret  ")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
            val ok = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-secret") }
            assertEquals(HttpStatusCode.OK, ok.status)
            val untrimmed = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer   scrape-secret  ") }
            assertEquals(HttpStatusCode.Unauthorized, untrimmed.status)
        }
        metricsApp(mapOf("APP_ENV" to "dev", "METRICS_SCRAPE_TOKEN" to "   ")) {
            assertEquals(HttpStatusCode.OK, client.get("/metrics").status, "token en blanco = sin token (bind interno)")
        }
    }

    /** PF3-3-02: ni el scrape, ni el 401, ni el 404 son cacheables. */
    @Test
    fun metricsResponsesAreNeverCacheable() {
        metricsApp(mapOf("METRICS_SCRAPE_TOKEN" to "scrape-secret")) {
            assertEquals("no-store", client.get("/metrics").headers[HttpHeaders.CacheControl])
            val ok = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-secret") }
            assertEquals("no-store", ok.headers[HttpHeaders.CacheControl])
        }
        metricsApp(mapOf("METRICS_ENABLED" to "false")) {
            assertEquals("no-store", client.get("/metrics").headers[HttpHeaders.CacheControl])
        }
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
    // Disco de datos de Supabase (endpoint privilegiado, texto plano).
    // -----------------------------------------------------------------

    @Test
    fun diskParserReadsTheDataVolumeAndIgnoresTheOperatingSystemDisk() {
        // Las dos métricas existen también para `mountpoint="/"`, que es el disco
        // del SISTEMA OPERATIVO de la instancia y va por el 75 %. Confundirlos
        // daría un número plausible y falso, que es lo peor que puede pasarle a
        // un panel de estado.
        assertEquals(
            8_350_298_112.0,
            parsePrometheusSampleValue(REAL_METRICS_BODY, NODE_FILESYSTEM_SIZE_BYTES, MOUNTPOINT_LABEL, DATA_MOUNTPOINT),
        )
        assertEquals(
            8_052_756_480.0,
            parsePrometheusSampleValue(REAL_METRICS_BODY, NODE_FILESYSTEM_AVAIL_BYTES, MOUNTPOINT_LABEL, DATA_MOUNTPOINT),
        )
        assertEquals(
            8_589_803_520.0,
            parsePrometheusSampleValue(REAL_METRICS_BODY, NODE_FILESYSTEM_SIZE_BYTES, MOUNTPOINT_LABEL, "/"),
            "El disco del sistema es otra serie, y esta prueba existe para que no se mezclen.",
        )
    }

    @Test
    fun diskPercentUsesTheRealMeasuredSizeAndAvailableBytes() {
        val percent = parseSupabaseDataDiskPercent(REAL_METRICS_BODY)

        // 100 * (1 - 8.05275648e9 / 8.350298112e9) = 3,5632…%
        assertEquals(3.5632, percent!!, 0.0001)
        // Y el disco del sistema, que ronda el 75 %, no se ha colado.
        assertTrue(percent < 10.0)
    }

    @Test
    fun aMissingSeriesIsUnavailableAndNeverAZero() {
        val onlySize = REAL_METRICS_BODY.lineSequence()
            .filterNot { it.startsWith(NODE_FILESYSTEM_AVAIL_BYTES) }
            .joinToString("\n")
        val onlyAvail = REAL_METRICS_BODY.lineSequence()
            .filterNot { it.startsWith(NODE_FILESYSTEM_SIZE_BYTES) }
            .joinToString("\n")
        // El volumen de datos desaparece, pero el del sistema sigue estando: la
        // tentación de "usar la que haya" se corta aquí.
        val onlyRootMountpoint = REAL_METRICS_BODY.lineSequence()
            .filterNot { it.contains("mountpoint=\"/data\"") }
            .joinToString("\n")

        assertNull(parseSupabaseDataDiskPercent(onlySize))
        assertNull(parseSupabaseDataDiskPercent(onlyAvail))
        assertNull(parseSupabaseDataDiskPercent(onlyRootMountpoint))
    }

    @Test
    fun emptyMalformedAndNonFiniteBodiesStayUnavailable() {
        assertNull(parseSupabaseDataDiskPercent(""))
        assertNull(parseSupabaseDataDiskPercent("   \n\n"))
        assertNull(parseSupabaseDataDiskPercent("<html><body>401 Unauthorized</body></html>"))
        assertNull(parseSupabaseDataDiskPercent("""{"message":"Invalid authentication credentials"}"""))
        assertNull(parseSupabaseDataDiskPercent(diskBody(size = "NaN", available = "8.05275648e+09")))
        assertNull(parseSupabaseDataDiskPercent(diskBody(size = "8.350298112e+09", available = "+Inf")))
        assertNull(parseSupabaseDataDiskPercent(diskBody(size = "no-es-un-numero", available = "1")))
        // Un total de cero bytes haría 1 - x/0 = ±Infinity, no un porcentaje.
        assertNull(parseSupabaseDataDiskPercent(diskBody(size = "0", available = "0")))
        // Disponible mayor que el total no describe ningún disco real: recortarlo
        // a 0 % lo presentaría como "medido y vacío".
        assertNull(parseSupabaseDataDiskPercent(diskBody(size = "1000", available = "2000")))
        assertNull(parseSupabaseDataDiskPercent(diskBody(size = "1000", available = "-1")))
        // Dos muestras que se contradicen no son un dato: no se elige una.
        assertNull(
            parseSupabaseDataDiskPercent(
                diskBody(size = "8.350298112e+09", available = "8.05275648e+09") + "\n" +
                    """$NODE_FILESYSTEM_SIZE_BYTES{device="/dev/nvme2n1",mountpoint="/data"} 4.0e+09""",
            ),
        )
    }

    @Test
    fun aFullDiskIsOneHundredAndAnEmptyOneIsAMeasuredZero() {
        assertEquals(100.0, parseSupabaseDataDiskPercent(diskBody(size = "1000", available = "0")))
        assertEquals(
            0.0,
            parseSupabaseDataDiskPercent(diskBody(size = "1000", available = "1000")),
            "Un disco vacío es un cero MEDIDO, distinto de 'no disponible'.",
        )
    }

    @Test
    fun theDiskGatewaySendsServiceRoleBasicAuthAndNeverPutsTheKeyInTheUrl() {
        val paths = CopyOnWriteArrayList<String>()
        val authorizations = CopyOnWriteArrayList<String>()
        withPrometheusServer { supabase ->
            supabase.createContext(PRIVILEGED_METRICS_PATH) { exchange ->
                paths += exchange.requestURI.toString()
                authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
                val body = REAL_METRICS_BODY.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            val percent = SupabaseDiskMetricsGateway(supabase.baseUrl(), SECRET_KEY).diskPercent()

            assertEquals(3.5632, percent!!, 0.0001)
            assertEquals(PRIVILEGED_METRICS_PATH, paths.single())
            assertFalse(paths.single().contains(SECRET_KEY), "La clave NUNCA viaja en la URL.")
            val expected = "Basic " + Base64.getEncoder()
                .encodeToString("$METRICS_BASIC_USER:$SECRET_KEY".toByteArray(StandardCharsets.UTF_8))
            assertEquals(expected, authorizations.single())
            assertEquals("service_role", METRICS_BASIC_USER)
        }
    }

    @Test
    fun anUnauthorizedOrUnreachableEndpointIsUnavailableAndDoesNotBreakTheStatus() {
        withPrometheusServer { supabase ->
            supabase.createContext(PRIVILEGED_METRICS_PATH) { exchange ->
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }

            assertNull(SupabaseDiskMetricsGateway(supabase.baseUrl(), SECRET_KEY).diskPercent())
        }
        assertNull(
            SupabaseDiskMetricsGateway(
                supabaseUrl = "http://127.0.0.1:1",
                secretKey = SECRET_KEY,
                client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build(),
                requestTimeout = Duration.ofMillis(200),
            ).diskPercent(),
        )
        assertNull(SupabaseDiskMetricsGateway("no es una URL válida", SECRET_KEY).diskPercent())
    }

    @Test
    fun anUnconfiguredEnvironmentYieldsUnavailableInsteadOfFailingToStart() {
        assertNull(SupabaseDiskMetricsGateway.fromEnvironment { null }.diskPercent())
        assertNull(
            SupabaseDiskMetricsGateway
                .fromEnvironment { name -> "https://proyecto.supabase.co".takeIf { name == "SUPABASE_URL" } }
                .diskPercent(),
        )
    }

    @Test
    fun adminStatusExposesTheDiskAndDeclaresItUnavailableOnlyWhenItIsMissing() {
        withPrometheusServer { supabase ->
            supabase.createContext("/rest/v1/especialidades") { exchange ->
                exchange.responseHeaders.add("Content-Range", "0-0/1")
                val body = "[]".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            fun statusWith(disk: SupabaseDiskUsageProvider) = AdminV1Gateway.forTesting(
                supabaseUrl = supabase.baseUrl(),
                serviceRoleKey = SECRET_KEY,
                json = Json { ignoreUnknownKeys = true },
                client = HttpClient.newHttpClient(),
                supabaseDisk = disk,
            ).getSystemStatus()

            val measured = statusWith { 3.5632 }
            assertEquals(3.5632, measured.supabaseDiskPercent)
            assertFalse(SystemStatusNotInstrumented.SUPABASE_DISK in measured.notInstrumented)

            val zero = statusWith { 0.0 }
            assertEquals(0.0, zero.supabaseDiskPercent, "Un cero medido se conserva como cero.")
            assertFalse(SystemStatusNotInstrumented.SUPABASE_DISK in zero.notInstrumented)

            val unavailable = statusWith { null }
            assertNull(unavailable.supabaseDiskPercent)
            assertTrue(SystemStatusNotInstrumented.SUPABASE_DISK in unavailable.notInstrumented)

            // Un proveedor que lance, o que devuelva un no finito, tampoco puede
            // romper el endpoint ni colar un número inventado.
            val throwing = statusWith { error("boom") }
            assertNull(throwing.supabaseDiskPercent)
            assertTrue(SystemStatusNotInstrumented.SUPABASE_DISK in throwing.notInstrumented)

            val nonFinite = statusWith { Double.POSITIVE_INFINITY }
            assertNull(nonFinite.supabaseDiskPercent)
            assertTrue(SystemStatusNotInstrumented.SUPABASE_DISK in nonFinite.notInstrumented)
            // Y sobre todo: serializar no lanza. kotlinx.serialization rechaza
            // NaN/Infinity al codificar, así que un no finito habría sido un 500.
            Json.encodeToString(AdminSystemStatusResponse(nonFinite))
        }
    }

    @Test
    fun theDiskFieldTravelsOnTheWireAndAMeasuredZeroSurvivesTheRoundTrip() {
        val backendJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = false
        }
        val desktopJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
            explicitNulls = false
        }
        val cases = listOf(
            "disco medido" to 3.5632,
            "disco vacío medido" to 0.0,
            "disco no disponible" to null,
        )
        for ((label, disk) in cases) {
            val dto = AdminSystemStatusDto(
                overallStatus = SystemStatusLevelWire.OPERATIVE,
                ktorStatus = SystemStatusLevelWire.OPERATIVE,
                supabaseStatus = SystemStatusLevelWire.OPERATIVE,
                workerStatus = SystemStatusLevelWire.UNKNOWN,
                cpuPercent = 2.91,
                ramPercent = 13.5,
                supabaseDiskPercent = disk,
                apiLatencyMs = 3,
                errors24h = 0,
                updatedAt = "2026-09-23T07:22:03Z",
            )

            val wire = backendJson.encodeToString(AdminSystemStatusResponse.serializer(), AdminSystemStatusResponse(dto))
            val decoded = desktopJson.decodeFromString(DesktopSystemStatusResponseMirror.serializer(), wire)

            assertEquals(disk, decoded.data.supabaseDiskPercent, label)
        }
        // El cero medido va explícito en el cable, no omitido.
        assertTrue(
            backendJson.encodeToString(
                AdminSystemStatusResponse.serializer(),
                AdminSystemStatusResponse(
                    AdminSystemStatusDto(
                        overallStatus = SystemStatusLevelWire.OPERATIVE,
                        ktorStatus = SystemStatusLevelWire.OPERATIVE,
                        supabaseStatus = SystemStatusLevelWire.OPERATIVE,
                        workerStatus = SystemStatusLevelWire.UNKNOWN,
                        supabaseDiskPercent = 0.0,
                        updatedAt = "2026-09-23T07:22:03Z",
                    ),
                ),
            ).contains("\"supabaseDiskPercent\":0.0"),
        )
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

        // Los bloques nuevos, con los nombres de campo que el panel declara de verdad.
        val full = AdminSystemStatusDto(
            overallStatus = SystemStatusLevelWire.DEGRADED,
            ktorStatus = SystemStatusLevelWire.DEGRADED,
            supabaseStatus = SystemStatusLevelWire.OPERATIVE,
            updatedAt = "2026-10-13T10:00:00Z",
            replicas = listOf(
                AdminSystemReplicaDto("bot-1", "bot", arriba = true, veSupabase = true, version = "886dac044b6e", arrancadaEn = "2026-10-12T08:00:00Z"),
                AdminSystemReplicaDto("bot-2", "bot", arriba = false),
            ),
            alertas = emptyList(),
            cuotaSupabase = AdminSupabaseQuotaDto(
                bytes24h = 0,
                bytes7d = 700_000_000,
                proyeccionMensualBytes = 3_000_000_000,
                proyeccionBase = SupabaseQuotaWire.BASE_7D,
                limiteMensualBytes = SUPABASE_MONTHLY_EGRESS_LIMIT_BYTES,
                porDestino = listOf(AdminSupabaseQuotaTargetDto("rest", bytes24h = 0, bytes7d = 700_000_000)),
            ),
            version = AdminSystemVersionDto(sha = "886dac044b6e", replicasDifieren = true),
            fuente = AdminSystemSourceDto(prometheusResponde = false),
        )
        val decoded = desktopJson.decodeFromString(
            DesktopSystemStatusResponseMirror.serializer(),
            backendJson.encodeToString(AdminSystemStatusResponse.serializer(), AdminSystemStatusResponse(full)),
        ).data

        assertEquals(listOf("bot-1", "bot-2"), decoded.replicas?.map { it.nombre })
        // `arriba = false` y `prometheusResponde = false` VIAJAN: si se omitieran por ser el valor por defecto, una
        // réplica caída y un Prometheus mudo llegarían al panel como «sin dato».
        assertEquals(listOf(true, false), decoded.replicas?.map { it.arriba })
        assertNull(decoded.replicas?.get(1)?.veSupabase)
        assertEquals("2026-10-12T08:00:00Z", decoded.replicas?.get(0)?.arrancadaEn)
        assertEquals(emptyList(), decoded.alertas, "«ninguna alerta» no se confunde con «no se pudo leer»")
        assertEquals(0L, decoded.cuotaSupabase?.bytes24h, "un cero medido sobrevive al viaje")
        assertEquals(3_000_000_000L, decoded.cuotaSupabase?.proyeccionMensualBytes)
        assertEquals("7d", decoded.cuotaSupabase?.proyeccionBase)
        assertEquals(5_000_000_000L, decoded.cuotaSupabase?.limiteMensualBytes)
        assertEquals("rest", decoded.cuotaSupabase?.porDestino?.single()?.destino)
        assertEquals(true, decoded.version?.replicasDifieren)
        assertEquals("886dac044b6e", decoded.version?.sha)
        assertEquals(false, decoded.fuente?.prometheusResponde)
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
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val updatedAt: String = "",
    val notInstrumented: List<String> = emptyList(),
    val replicas: List<DesktopSystemReplicaDtoMirror>? = null,
    val alertas: List<DesktopSystemAlertDtoMirror>? = null,
    val cuotaSupabase: DesktopSupabaseQuotaDtoMirror? = null,
    val version: DesktopSystemVersionDtoMirror? = null,
    val fuente: DesktopSystemSourceDtoMirror? = null,
)

@kotlinx.serialization.Serializable
private data class DesktopSystemReplicaDtoMirror(
    val nombre: String = "",
    val perfil: String? = null,
    val arriba: Boolean = false,
    val veSupabase: Boolean? = null,
    val version: String? = null,
    val arrancadaEn: String? = null,
)

@kotlinx.serialization.Serializable
private data class DesktopSystemAlertDtoMirror(
    val nombre: String = "",
    val severidad: String? = null,
    val resumen: String? = null,
    val replica: String? = null,
)

@kotlinx.serialization.Serializable
private data class DesktopSupabaseQuotaDtoMirror(
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
    val proyeccionMensualBytes: Long? = null,
    val proyeccionBase: String? = null,
    val limiteMensualBytes: Long? = null,
    val porDestino: List<DesktopSupabaseQuotaTargetDtoMirror> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class DesktopSupabaseQuotaTargetDtoMirror(
    val destino: String = "",
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
)

@kotlinx.serialization.Serializable
private data class DesktopSystemVersionDtoMirror(
    val sha: String? = null,
    val replicasDifieren: Boolean = false,
)

@kotlinx.serialization.Serializable
private data class DesktopSystemSourceDtoMirror(
    val prometheusResponde: Boolean? = null,
)

@kotlinx.serialization.Serializable
private data class DesktopSystemStatusResponseMirror(val data: DesktopSystemStatusDtoMirror)

private fun successBody(value: String): String =
    """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"$value"]}]}}"""

/** Un vector instantáneo con las etiquetas de cada muestra, como lo devuelve `/api/v1/query`. */
private fun vectorBody(vararg samples: Pair<Map<String, String>, String>): String {
    val result = samples.joinToString(",") { (labels, value) ->
        val metric = labels.entries.joinToString(",") { (name, label) -> "\"$name\":\"$label\"" }
        """{"metric":{$metric},"value":[1,"$value"]}"""
    }
    return """{"status":"success","data":{"resultType":"vector","result":[$result]}}"""
}

private const val SECRET_KEY ="test-secret-no-debe-aparecer-en-ninguna-parte"

/**
 * Extracto literal del endpoint privilegiado real, con lo que importa para el
 * parser: comentarios HELP/TYPE, notación exponencial, series de otras métricas
 * por medio y —lo esencial— el `mountpoint="/"` del sistema operativo junto al
 * `mountpoint="/data"` del volumen de Postgres.
 */
private val REAL_METRICS_BODY = """
    # HELP node_filesystem_size_bytes Filesystem size in bytes.
    # TYPE node_filesystem_size_bytes gauge
    node_filesystem_size_bytes{service_type="db",device="/dev/nvme0n1p1",fstype="ext4",mountpoint="/"} 8.58980352e+09
    node_filesystem_size_bytes{service_type="db",device="/dev/nvme1n1",fstype="ext4",mountpoint="/data"} 8.350298112e+09
    # HELP node_filesystem_free_bytes Filesystem free space in bytes.
    # TYPE node_filesystem_free_bytes gauge
    node_filesystem_free_bytes{service_type="db",device="/dev/nvme1n1",fstype="ext4",mountpoint="/data"} 8.45275648e+09
    # HELP node_filesystem_avail_bytes Filesystem space available to non-root users in bytes.
    # TYPE node_filesystem_avail_bytes gauge
    node_filesystem_avail_bytes{service_type="db",device="/dev/nvme0n1p1",fstype="ext4",mountpoint="/"} 2.14745088e+09
    node_filesystem_avail_bytes{service_type="db",device="/dev/nvme1n1",fstype="ext4",mountpoint="/data"} 8.05275648e+09
    # HELP pg_stat_database_num_backends Number of backends currently connected.
    # TYPE pg_stat_database_num_backends gauge
    pg_stat_database_num_backends{service_type="db",datname="postgres"} 7
""".trimIndent()

private fun diskBody(size: String, available: String): String = """
    $NODE_FILESYSTEM_SIZE_BYTES{device="/dev/nvme1n1",fstype="ext4",mountpoint="/data"} $size
    $NODE_FILESYSTEM_AVAIL_BYTES{device="/dev/nvme1n1",fstype="ext4",mountpoint="/data"} $available
""".trimIndent()

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
