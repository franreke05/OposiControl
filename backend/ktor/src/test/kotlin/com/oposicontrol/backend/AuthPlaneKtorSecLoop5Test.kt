package com.oposicontrol.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.response.respond
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LOOP 5, area KTOR_SEC (plano `/v1/auth`):
 * - MASTER5-OBS-001: los errores de dominio que `authEndpoint` responde el mismo (sin pasar por StatusPages) dejan el
 *   codigo, la causa redactada (5xx) y el evento estrategico; 401/403 dejan AUTH_LOGIN_FAILED / AUTHORIZATION_DENIED.
 * - MASTER5-API-007: un cuerpo demasiado grande en `/v1/auth` es SIEMPRE el 400 declarado, tambien por encima del tope
 *   global de 2 MB (antes: 413 no declarado); el resto del backend conserva su 413.
 * - MASTER5-OBS-003: un revoke de la sesion acuñada que falla deja un WARN (antes, silencio).
 */
class AuthPlaneKtorSecLoop5Test {

    private class Capture(vararg names: String) : AutoCloseable {
        private val attached = names.map { LoggerFactory.getLogger(it) as LogbackLogger }
        val appender = ListAppender<ILoggingEvent>().also { a -> a.start(); attached.forEach { it.addAppender(a) } }
        fun lines(): List<String> = appender.list.map { it.formattedMessage }
        fun warnings(): List<String> = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        override fun close() = attached.forEach { it.detachAppender(appender) }
    }

    /** Gateway que responde lo que la prueba pide en `login`; el resto no interviene. */
    private class LoginGateway(var failure: Exception) : OposiBotsAuthGateway by NoAuthGateway {
        override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto = throw failure
    }

    private suspend fun ApplicationTestBuilder.login(): HttpResponse = client.post("/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"person@example.com","password":"safe-password-12"}""")
    }

    private suspend fun HttpResponse.code() = Json.parseToJsonElement(bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

    // ------------------------------------------------------------------ MASTER5-OBS-001

    @Test
    fun anUpstream5xxOnTheAuthPlaneLogsTheCodeTheRedactedCauseAndTheStrategicEvent() {
        Capture("OC-KTOR-AUTH", "OC-KTOR-OBSERVABILITY").use { capture ->
            testApplication {
                val gateway = LoginGateway(
                    CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(
                        cause = IllegalStateException("rpc oposibots_login_gate renamed apikey=sb_secret_abc12345xyz"),
                    ),
                )
                val env = CanonicalTestEnv(gateway = gateway)
                application { env.mount(this) }
                val response = login()
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                val requestId = response.headers["X-Request-Id"]!!
                val lines = capture.lines().filter { requestId in it }
                assertTrue(
                    lines.any { "code=UPSTREAM_UNAVAILABLE" in it && "rpc oposibots_login_gate renamed" in it },
                    "la causa (redactada) llega al log: $lines",
                )
                assertTrue(lines.none { "sb_secret_abc12345xyz" in it }, "la causa se redacta: $lines")
                assertTrue(
                    lines.any { "event=SUPABASE_REQUEST_FAILED" in it && "operation=LOGIN" in it && "error_type=UPSTREAM_UNAVAILABLE" in it },
                    lines.toString(),
                )
            }
        }
    }

    @Test
    fun anInternalDomainFailureIsCountedAsAServerFailure() {
        Capture("OC-KTOR-OBSERVABILITY").use { capture ->
            testApplication {
                val env = CanonicalTestEnv(gateway = LoginGateway(CanonicalErrorCode.INTERNAL.exception()))
                application { env.mount(this) }
                val response = login()
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                val lines = capture.lines().filter { response.headers["X-Request-Id"]!! in it }
                assertTrue(lines.any { "event=UNCAUGHT_EXCEPTION" in it && "error_type=INTERNAL" in it }, lines.toString())
                assertTrue(lines.none { "event=SUPABASE_REQUEST_FAILED" in it }, lines.toString())
            }
        }
    }

    @Test
    fun credentialAndAccountDenialsLeaveTheirEventsWithTheCode() {
        Capture("OC-KTOR-AUTH", "OC-KTOR-OBSERVABILITY").use { capture ->
            testApplication {
                val env = CanonicalTestEnv(gateway = LoginGateway(CanonicalErrorCode.INVALID_CREDENTIALS.exception())).apply {
                    status = "bloqueada"
                    upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson("bloqueada") else "[]" }
                }
                application { env.mount(this) }

                val bad = login()
                assertEquals(HttpStatusCode.Unauthorized, bad.status)
                val badLines = capture.lines().filter { bad.headers["X-Request-Id"]!! in it }
                assertTrue(badLines.any { "event=AUTH_LOGIN_FAILED" in it && "error_type=INVALID_CREDENTIALS" in it }, badLines.toString())
                assertTrue(badLines.any { "status=401" in it && "code=INVALID_CREDENTIALS" in it }, badLines.toString())
                assertTrue(capture.warnings().none { bad.headers["X-Request-Id"]!! in it }, "un 401 no es un WARN")

                val me = client.get("/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN") }
                assertEquals(HttpStatusCode.Forbidden, me.status)
                assertEquals("ACCOUNT_BLOCKED", me.code())
                val meLines = capture.lines().filter { me.headers["X-Request-Id"]!! in it }
                assertTrue(
                    meLines.any { "event=AUTHORIZATION_DENIED" in it && "operation=ME" in it && "error_type=ACCOUNT_BLOCKED" in it && "user_id=$TEST_UID" in it },
                    meLines.toString(),
                )
            }
        }
    }

    @Test
    fun theIpRateLimitLeavesItsCodeOnTheResultLine() {
        Capture("OC-KTOR-AUTH").use { capture ->
            testApplication {
                val env = CanonicalTestEnv(limiter = TestCountingLimiter(ipAllowed = false), gateway = LoginGateway(IllegalStateException("unused")))
                application { env.mount(this) }
                val response = login()
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                assertTrue(capture.lines().any { "status=429" in it && "code=RATE_LIMITED" in it }, capture.lines().toString())
            }
        }
    }

    // ------------------------------------------------------------------ MASTER5-API-007

    @Test
    fun anOversizedAuthBodyIsTheDeclared400AtEverySizeWhileOtherRoutesKeepThe413() = testApplication {
        val gateway = LoginGateway(IllegalStateException("el gateway no debe llamarse"))
        val env = CanonicalTestEnv(gateway = gateway)
        application {
            env.mount(this)
            installRequestBodyLimit()
            routing { post("/echo") { call.respond(call.receiveText().length.toString()) } }
        }
        val justOverAuthCap = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${"a".repeat(17_000)}","password":"safe-password-12"}""")
        }
        val overGlobalCap = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat((REQUEST_BODY_MAX_BYTES + 1).toInt()))
        }
        listOf(justOverAuthCap, overGlobalCap).forEach {
            assertEquals(HttpStatusCode.BadRequest, it.status, it.bodyAsText())
            assertEquals("VALIDATION", it.code())
        }
        val other = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat((REQUEST_BODY_MAX_BYTES + 1).toInt()))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, other.status, "el tope global sigue fuera de /v1/auth")
    }

    @Test
    fun onlyALiteralAuthPathIsExemptFromTheGlobalCap() {
        assertTrue(isAuthPlanePath("/v1/auth/login"))
        assertTrue(isAuthPlanePath("/v1/auth/register/profile"))
        assertFalse(isAuthPlanePath("/v1/auth/../../api/v1/app/eventos"), "un `..` podria normalizarse hacia otra ruta")
        assertFalse(isAuthPlanePath("/v1/auth/./login"))
        assertFalse(isAuthPlanePath("/v1/auth/%2e%2e/api/v1/app/eventos"), "ni escapes")
        assertFalse(isAuthPlanePath("/v1/authx/login"))
        assertFalse(isAuthPlanePath("/api/v1/app/eventos"))
    }

    // ------------------------------------------------------------------ MASTER5-OBS-003

    private fun oauthGateway(logout: () -> Pair<HttpStatusCode, Boolean>): SupabaseOposiBotsAuthGateway {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val uid = "22222222-2222-4222-8222-222222222222"
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(
                    ByteReadChannel("""{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$uid","email":"p@example.com"}}"""),
                    HttpStatusCode.OK,
                    json,
                )
                "/rest/v1/perfiles_usuario" -> respond(
                    ByteReadChannel("""[{"usuario_id":"$uid","estado_cuenta":"bloqueada","onboarding_completado":true}]"""),
                    HttpStatusCode.OK,
                    json,
                )
                "/auth/v1/logout" -> {
                    val (status, throws) = logout()
                    if (throws) error("logout transport failure") else respond(ByteReadChannel(""), status, json)
                }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        return SupabaseOposiBotsAuthGateway(testCanonicalConfiguration(), Json { ignoreUnknownKeys = true }, client)
    }

    private fun idToken(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            encoder.encodeToString("""{"sub":"g-1","exp":4102444800}""".toByteArray()) + ".signature"
    }

    @Test
    fun aFailedRevokeOfTheMintedSessionIsNoLongerSilent() = runTest {
        val context = OposiBotsAuthContext("req-revoke-1", "127.0.0.1")
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            // Revoke que responde 500: la sesion acuñada puede seguir viva -> WARN con scope, estado y requestId.
            val failing = oauthGateway { HttpStatusCode.InternalServerError to false }
            val denied = assertFailsWith<BackendDomainException> { failing.google(OposiBotsGoogleRequest(idToken()), context) }
            assertEquals("ACCOUNT_BLOCKED", denied.errorCode, "el error original no se sustituye")
            val warnings = capture.warnings().filter { "minted-session revoke failed" in it }
            assertEquals(1, warnings.size, capture.warnings().toString())
            assertTrue("scope=global" in warnings.single() && "status=500" in warnings.single() && "requestId=req-revoke-1" in warnings.single(), warnings.toString())
            assertTrue(capture.lines().none { "minted-access" in it }, "nunca el token")
        }
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            // Fallo de transporte: tambien WARN, con la clase.
            val broken = oauthGateway { HttpStatusCode.OK to true }
            assertFailsWith<BackendDomainException> { broken.google(OposiBotsGoogleRequest(idToken()), context) }
            assertTrue(capture.warnings().any { "minted-session revoke failed" in it && "error=OposiBotsAuthProblem" in it }, capture.warnings().toString())
        }
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            // 2xx y 401/404 (la sesion ya no existe) son el resultado buscado: sin WARN de revoke.
            listOf(HttpStatusCode.NoContent, HttpStatusCode.Unauthorized, HttpStatusCode.NotFound).forEach { status ->
                val ok = oauthGateway { status to false }
                assertFailsWith<BackendDomainException> { ok.google(OposiBotsGoogleRequest(idToken()), context) }
            }
            assertTrue(capture.warnings().none { "minted-session revoke failed" in it }, capture.warnings().toString())
        }
    }
}
