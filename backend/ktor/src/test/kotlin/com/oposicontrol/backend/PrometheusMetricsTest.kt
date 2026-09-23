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
    val supabaseDiskPercent: Double? = null,
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

private const val SECRET_KEY = "test-secret-no-debe-aparecer-en-ninguna-parte"

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
