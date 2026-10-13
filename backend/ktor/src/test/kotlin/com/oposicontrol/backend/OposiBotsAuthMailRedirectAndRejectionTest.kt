package com.oposicontrol.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B2 (2026-10-13): `redirect_to` del correo de confirmacion en signup y resend con `OPOSIBOTS_EMAIL_CONFIRM_REDIRECT_URL`,
 * igual que recover con el suyo; sin la variable, la peticion a GoTrue es la de siempre.
 * B4 (2026-10-13): un 4xx de GoTrue distinto de 429 en resend/recover sigue saliendo como exito, pero deja un WARN con el
 * estado y el `error_code`, sin el correo.
 */
class OposiBotsAuthMailRedirectAndRejectionTest {
    private val context = OposiBotsAuthContext("request-mail-1", "127.0.0.1")
    private val email = "person@example.com"
    private val redirect = "oposibots://auth"

    private class Harness(configuration: OposiBotsAuthConfiguration, status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}") {
        val requests = mutableListOf<HttpRequestData>()
        val gateway = authGatewayWithoutAccessRequests(
            configuration,
            testJson,
            HttpClient(MockEngine { request ->
                requests += request
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }),
        )

        fun url(): String = requests.single().url.toString()
        fun body(): String = (requests.single().body as io.ktor.http.content.TextContent).text
    }

    private class Capture(vararg names: String) : AutoCloseable {
        private val attached = names.map { LoggerFactory.getLogger(it) as LogbackLogger }
        private val previous = attached.map { it.level }
        val appender = ListAppender<ILoggingEvent>().also { a -> a.start(); attached.forEach { it.addAppender(a) } }
        init { attached.forEach { it.level = Level.DEBUG } }
        fun events(): List<ILoggingEvent> = appender.list.toList()
        fun lines(): List<String> = events().map { it.formattedMessage }
        override fun close() = attached.forEachIndexed { i, it -> it.detachAppender(appender); it.level = previous[i] }
    }

    private fun configuration(emailConfirm: String? = null, recovery: String? = null) =
        testCanonicalConfiguration().copy(emailConfirmRedirectUrl = emailConfirm, passwordRecoveryRedirectUrl = recovery)

    private suspend fun Harness.register() =
        gateway.register(OposiBotsRegisterRequest(email = email, password = "safe-password-12"), context)

    private suspend fun Harness.resend() =
        gateway.resend(OposiBotsEmailResendRequest(email = email), context)

    private suspend fun Harness.recover() =
        gateway.recover(OposiBotsRecoverRequest(email = email), context)

    // ------------------------------------------------------------------------------------------------ B2

    @Test
    fun withoutTheVariableSignupAndResendGoOutExactlyAsBefore() = runTest {
        val signup = Harness(configuration()).also { it.register() }
        val resend = Harness(configuration()).also { it.resend() }

        assertEquals("https://example.supabase.co/auth/v1/signup", signup.url())
        assertEquals("""{"email":"person@example.com","password":"safe-password-12"}""", signup.body())
        assertEquals("https://example.supabase.co/auth/v1/resend", resend.url())
        assertEquals("""{"type":"signup","email":"person@example.com"}""", resend.body())
    }

    @Test
    fun withTheVariableSignupAndResendCarryRedirectToExactlyLikeRecovery() = runTest {
        val signup = Harness(configuration(emailConfirm = redirect)).also { it.register() }
        val resend = Harness(configuration(emailConfirm = redirect)).also { it.resend() }
        val recover = Harness(configuration(recovery = redirect)).also { it.recover() }

        assertEquals("https://example.supabase.co/auth/v1/recover?redirect_to=oposibots%3A%2F%2Fauth", recover.url())
        assertEquals("https://example.supabase.co/auth/v1/signup?redirect_to=oposibots%3A%2F%2Fauth", signup.url())
        assertEquals("https://example.supabase.co/auth/v1/resend?redirect_to=oposibots%3A%2F%2Fauth", resend.url())
        assertEquals(redirect, signup.requests.single().url.parameters["redirect_to"])
        assertEquals(redirect, resend.requests.single().url.parameters["redirect_to"])
        // El cuerpo no cambia: el destino va solo en la query, como en recover.
        assertEquals("""{"email":"person@example.com","password":"safe-password-12"}""", signup.body())
        assertEquals("""{"type":"signup","email":"person@example.com"}""", resend.body())
    }

    @Test
    fun aRedirectWithQueryCharactersIsEncodedAsOneValue() = runTest {
        val target = "https://app.example.com/auth callback?next=/home&x=1"
        val signup = Harness(configuration(emailConfirm = target)).also { it.register() }

        assertEquals(target, signup.requests.single().url.parameters["redirect_to"])
        assertEquals(setOf("redirect_to"), signup.requests.single().url.parameters.names())
    }

    @Test
    fun theTwoRedirectVariablesDoNotLeakIntoEachOther() = runTest {
        val onlyRecovery = configuration(recovery = "oposibots://auth/reset-password")
        val onlyConfirm = configuration(emailConfirm = redirect)

        assertEquals("https://example.supabase.co/auth/v1/signup", Harness(onlyRecovery).also { it.register() }.url())
        assertEquals("https://example.supabase.co/auth/v1/resend", Harness(onlyRecovery).also { it.resend() }.url())
        assertEquals("https://example.supabase.co/auth/v1/recover", Harness(onlyConfirm).also { it.recover() }.url())
    }

    // ------------------------------------------------------------------------------------------------ B4

    private val captchaRejected = """{"code":400,"error_code":"captcha_failed","msg":"captcha verification failed for person@example.com"}"""

    @Test
    fun aNon429ClientErrorOnResendAndRecoverStaysASuccessButIsLoggedAtWarnWithoutTheEmail() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            Harness(configuration(), HttpStatusCode.BadRequest, captchaRejected).resend()
            Harness(configuration(), HttpStatusCode.BadRequest, captchaRejected).recover()

            val rejected = capture.events().filter { "rejected by upstream" in it.formattedMessage }
            assertEquals(2, rejected.size, capture.lines().toString())
            assertTrue(rejected.all { it.level == Level.WARN })
            val ref = configuration().authLogEmailRef(email)
            assertTrue(ref != adminAccessEmailRef(email) && ref.length == 12, "seudonimo con clave, no el SHA-256 sin clave")
            assertEquals(
                "auth email resend rejected by upstream status=400 error_code=captcha_failed emailRef=$ref " +
                    "requestId=request-mail-1 (indistinguishable 204 returned)",
                rejected[0].formattedMessage,
            )
            assertEquals(
                "auth email recover rejected by upstream status=400 error_code=captcha_failed emailRef=$ref " +
                    "requestId=request-mail-1 (indistinguishable 204 returned)",
                rejected[1].formattedMessage,
            )
            assertFalse(capture.lines().any { email in it }, capture.lines().toString())
        }
    }

    @Test
    fun anErrorWithoutErrorCodeOrWithAHostileOneIsLoggedSafely() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            Harness(configuration(), HttpStatusCode.UnprocessableEntity, "not json").resend()
            Harness(
                configuration(),
                HttpStatusCode.Forbidden,
                // Con letras y digitos NO ASCII (escapes JSON de U+00D1, U+03A9 y el digito arabigo U+0661): solo pasa
                // `[A-Za-z0-9_]`, `isLetterOrDigit` los dejaba entrar en el log.
                """{"code":403,"error_code":"x\n2026 WARN forgedÑΩ١ person@example.com","msg":"m"}""",
            ).recover()

            val rejected = capture.lines().filter { "rejected by upstream" in it }
            assertEquals(2, rejected.size, capture.lines().toString())
            assertTrue("status=422 error_code=- " in rejected[0], rejected[0])
            assertTrue("status=403 error_code=x2026WARNforgedpersonexamplecom " in rejected[1], rejected[1])
            assertFalse(rejected.any { '\n' in it || email in it })
        }
    }

    @Test
    fun successThrottleAndOutageDoNotProduceTheRejectionLine() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            Harness(configuration()).resend()
            Harness(configuration()).recover()
            Harness(configuration(), HttpStatusCode.TooManyRequests, """{"code":429,"error_code":"over_email_send_rate_limit"}""").resend()
            Harness(configuration(), HttpStatusCode.TooManyRequests, """{"code":429,"error_code":"over_email_send_rate_limit"}""").recover()
            val resendOutage = assertFailsWith<OposiBotsAuthProblem> { Harness(configuration(), HttpStatusCode.BadGateway, "{}").resend() }
            val recoverOutage = assertFailsWith<OposiBotsAuthProblem> { Harness(configuration(), HttpStatusCode.BadGateway, "{}").recover() }

            assertEquals(HttpStatusCode.ServiceUnavailable, resendOutage.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, recoverOutage.status)
            assertFalse(capture.lines().any { "rejected by upstream" in it }, capture.lines().toString())
            // El 429 conserva su linea de siempre.
            assertEquals(2, capture.lines().count { it == "auth email throttled by upstream (indistinguishable 204 returned)" })
        }
    }
}
