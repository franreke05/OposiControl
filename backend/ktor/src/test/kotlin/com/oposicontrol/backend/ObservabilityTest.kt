package com.oposicontrol.backend

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class CapturingAppender : AppenderBase<ILoggingEvent>() {
    val messages = mutableListOf<String>()
    override fun append(event: ILoggingEvent) {
        messages += event.formattedMessage
    }
}

/** Adjunta un appender en memoria al logger real de eventos estrategicos
 * mientras corre [block], y devuelve las lineas capturadas -- verifica el
 * contenido REAL que Logback emitiria, no solo que la funcion no lance. */
private fun captureObservabilityLogs(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as ch.qos.logback.classic.Logger
    val appender = CapturingAppender().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.messages
}

class ObservabilityTest {

    // ----- structured event fields -----

    @Test
    fun logStrategicEvent_emitsAllProvidedFields_inTheStructuredFormat() {
        val lines = captureObservabilityLogs {
            logStrategicEvent(
                StrategicEvent.FEEDBACK_RESOLVED,
                requestId = "req-123",
                operation = "resolver_feedback",
                result = "resuelto",
                durationMs = 42,
                entityType = "feedback_pregunta",
                entityId = "f-1",
                errorType = null,
                userId = "u-9",
                count = 3,
            )
        }

        val line = lines.single()
        assertTrue(line.contains("event=FEEDBACK_RESOLVED"))
        assertTrue(line.contains("request_id=req-123"))
        assertTrue(line.contains("operation=resolver_feedback"))
        assertTrue(line.contains("result=resuelto"))
        assertTrue(line.contains("duration_ms=42"))
        assertTrue(line.contains("entity_type=feedback_pregunta"))
        assertTrue(line.contains("entity_id=f-1"))
        assertTrue(line.contains("user_id=u-9"))
        assertTrue(line.contains("count=3"))
    }

    @Test
    fun logStrategicEvent_omittedFields_renderAsADashNeverAsBlankOrNull() {
        val line = captureObservabilityLogs { logStrategicEvent(StrategicEvent.SLOW_REQUEST) }.single()

        assertFalse(line.contains("null"))
        assertTrue(line.contains("operation=-"))
        assertTrue(line.contains("error_type=-"))
    }

    @Test
    fun logStrategicEvent_includesTheConfiguredEnvironment() {
        val line = captureObservabilityLogs { logStrategicEvent(StrategicEvent.ADMIN_ACTION) }.single()

        assertTrue(line.contains("environment=${ObservabilityConfig.environment}"))
    }

    // ----- redaction / no secret leakage -----

    @Test
    fun redactedMessage_stripsABearerToken() {
        val error = RuntimeException("call failed: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")

        val message = error.redactedMessage("fallback")

        assertFalse(message.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(message.contains("[redacted]"))
    }

    @Test
    fun redactedMessage_stripsAnApikey() {
        val error = RuntimeException("supabase rejected apikey=sb_live_abcdef1234567890")

        val message = error.redactedMessage("fallback")

        assertFalse(message.contains("sb_live_abcdef1234567890"))
    }

    @Test
    fun redactedMessage_fallsBackToTheDefault_whenTheThrowableHasNoMessage() {
        val error = RuntimeException(null as String?)

        assertEquals("fallback", error.redactedMessage("fallback"))
    }

    @Test
    fun looksLikeUpstreamTimeout_recognizesJavaHttpTimeoutClasses() {
        assertTrue(RuntimeException(java.net.http.HttpConnectTimeoutException("connect")).looksLikeUpstreamTimeout())
        assertTrue(RuntimeException("Supabase network error on GET /rest/v1/x: HttpTimeoutException: timeout").looksLikeUpstreamTimeout())
    }

    @Test
    fun looksLikeUpstreamTimeout_falseForAnOrdinaryBusinessError() {
        assertFalse(IllegalStateException("Supabase POST /rest/v1/x -> 409: conflict").looksLikeUpstreamTimeout())
    }

    // ----- P1: HTTP timeouts (gateways sin timeout, ver newUpstreamHttpClient) -----

    @Test
    fun newUpstreamHttpClient_appliesTheConfiguredConnectTimeout() {
        val client = newUpstreamHttpClient()

        assertEquals(
            java.time.Duration.ofMillis(ObservabilityConfig.upstreamConnectTimeoutMs),
            client.connectTimeout().orElse(null),
        )
    }

    @Test
    fun upstreamRequestTimeout_matchesTheConfiguredValue_andIsFinite() {
        assertEquals(java.time.Duration.ofMillis(ObservabilityConfig.upstreamRequestTimeoutMs), UPSTREAM_REQUEST_TIMEOUT)
        assertTrue(UPSTREAM_REQUEST_TIMEOUT.toMillis() > 0)
    }

    @Test
    fun aRequestWithoutAReachableHost_failsWithinTheConfiguredTimeout_neverHangs() {
        // Puerto local sin listener + timeout corto propio (no el de
        // produccion, para que el test no tarde 20s): reproduce el mismo
        // patron que usan las gateways (HttpRequest.Builder.timeout(...))
        // y prueba que efectivamente acota la espera en vez de colgar el
        // hilo -- el riesgo real que motiva este P1.
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(500)).build()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1"))
            .timeout(java.time.Duration.ofSeconds(2))
            .GET()
            .build()

        val elapsed = kotlin.time.TimeSource.Monotonic.markNow()
        val threw = try {
            client.send(request, HttpResponse.BodyHandlers.discarding())
            false
        } catch (_: Exception) {
            true
        }

        assertTrue(threw)
        assertTrue(elapsed.elapsedNow().inWholeSeconds < 5, "no debe tardar mucho mas que el timeout configurado")
    }

    // ----- readiness dependency check -----

    @Test
    fun checkSupabaseReachable_unknown_whenConfigIsMissing() {
        assertEquals("UNKNOWN", checkSupabaseReachable(url = null, apiKey = "key"))
        assertEquals("UNKNOWN", checkSupabaseReachable(url = "https://example.invalid", apiKey = null))
        assertEquals("UNKNOWN", checkSupabaseReachable(url = "", apiKey = ""))
    }

    @Test
    fun checkSupabaseReachable_down_whenTheHostRefusesTheConnection() {
        // Puerto local sin listener: falla rapido (connection refused), sin
        // depender de red externa ni de Supabase real.
        val status = checkSupabaseReachable(url = "http://127.0.0.1:1", apiKey = "fake-key")

        assertEquals("DOWN", status)
    }

    // ----- request correlation: generation + propagation -----

    @Test
    fun requestCorrelation_generatesAnIdAndReturnsItOnTheResponseHeader_whenNoneWasSent() = testApplication {
        application {
            installRequestCorrelation()
            routing { get("/probe") { call.respond(HttpStatusCode.OK) } }
        }

        val response = client.get("/probe")

        val requestId = response.headers["X-Request-Id"]
        assertTrue(requestId != null && requestId.matches(Regex("[A-Za-z0-9._-]{8,128}")))
    }

    @Test
    fun requestCorrelation_echoesBackAValidIncomingRequestId() = testApplication {
        application {
            installRequestCorrelation()
            routing { get("/probe") { call.respond(HttpStatusCode.OK) } }
        }

        val response = client.get("/probe") { header("X-Request-Id", "client-provided-id-001") }

        assertEquals("client-provided-id-001", response.headers["X-Request-Id"])
    }

    @Test
    fun requestCorrelation_ignoresAnInvalidIncomingRequestId_generatesItsOwn() = testApplication {
        application {
            installRequestCorrelation()
            routing { get("/probe") { call.respond(HttpStatusCode.OK) } }
        }

        val response = client.get("/probe") { header("X-Request-Id", "bad id with spaces") }

        assertNotEquals("bad id with spaces", response.headers["X-Request-Id"])
    }

    // ----- error path: request id/duration available to a StatusPages-style handler -----

    @Test
    fun requestCorrelation_exposesRequestIdAndElapsedTime_toTheHandler() = testApplication {
        application {
            installRequestCorrelation()
            routing {
                get("/probe") {
                    val requestId = call.requestIdOrNull()
                    val elapsed = call.elapsedMsOrNull()
                    call.respondText("request_id=$requestId elapsed_not_null=${elapsed != null}")
                }
            }
        }

        val response = client.get("/probe")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("elapsed_not_null=true"))
        assertFalse(body.contains("request_id=null"))
    }

    // ----- health/live, health/ready -----

    @Test
    fun healthLive_alwaysRespondsOk() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { observabilityHealthRoutes() }
        }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun healthLive_andReady_bodiesMatchOpenApiD807D808Enums() = testApplication {
        // openapi/foundation/foundation.yaml LivenessStatus / ReadinessStatus.
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { observabilityHealthRoutes(supabaseCheck = { "UP" }) }
        }

        val live = Json.parseToJsonElement(client.get("/health/live").bodyAsText()).jsonObject
        assertEquals(mapOf("status" to "UP"), live.mapValues { it.value.jsonPrimitive.content })

        val ready = Json.parseToJsonElement(client.get("/health/ready").bodyAsText()).jsonObject
        assertEquals(setOf("status", "checks", "timestamp"), ready.keys)
        assertTrue(ready["status"]!!.jsonPrimitive.content in setOf("UP", "DEGRADED", "DOWN"))
        val checks = ready["checks"]!!.jsonObject
        assertEquals(setOf("ktor", "supabase"), checks.keys)
        assertTrue(checks["ktor"]!!.jsonPrimitive.content in setOf("UP", "DOWN"))
        assertTrue(checks["supabase"]!!.jsonPrimitive.content in setOf("UP", "DOWN", "UNKNOWN"))
    }

    @Test
    fun healthReady_ok_whenSupabaseCheckReportsUp() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { observabilityHealthRoutes(supabaseCheck = { "UP" }) }
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"UP\""))
    }

    @Test
    fun healthReady_serviceUnavailable_whenSupabaseCheckReportsDown() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { observabilityHealthRoutes(supabaseCheck = { "DOWN" }) }
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"DOWN\""))
        assertTrue(response.bodyAsText().contains("\"supabase\":\"DOWN\""))
    }

    @Test
    fun healthReady_serviceUnavailable_whenSupabaseConfigIsMissing_dependencyFailurePath() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { observabilityHealthRoutes(supabaseCheck = { checkSupabaseReachable(url = null, apiKey = null) }) }
        }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
