package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OposiBotsAuthRoutesTest {
    private val testJson = Json { ignoreUnknownKeys = true }

    @Test
    fun registerReturnsAcceptedAndNeverReturnsSessionBeforeVerification() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }

        val response = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"person@example.com","password":"safe-password"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(testJson.decodeFromString<OposiBotsRegisterResponse>(response.bodyAsText()).verificationRequired)
        assertEquals("person@example.com", fake.registerRequest?.identifier)
        assertFalse(response.bodyAsText().contains("accessToken"))
    }

    @Test
    fun authRejectsNonJsonAndOversizedBodiesBeforeGateway() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }

        val wrongType = client.post("/v1/auth/login") {
            contentType(ContentType.Text.Plain)
            setBody("{}")
        }
        val oversized = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"${"a".repeat(17_000)}","password":"safe-password"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
        assertEquals(HttpStatusCode.BadRequest, oversized.status)
        assertEquals(0, fake.loginCalls)
    }

    @Test
    fun rejectedRateLimitReturns429AndRetryAfter() = testApplication {
        val fake = FakeGateway()
        application {
            authTestModule(
                fake,
                rateLimiter = RejectingRateLimiter,
            )
        }

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"person@example.com","password":"safe-password"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        assertEquals(0, fake.loginCalls)
    }

    @Test
    fun logoutRequiresBearerSeparateFromBody() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }

        val missing = client.post("/v1/auth/logout")
        val accepted = client.post("/v1/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer ${"x".repeat(80)}")
        }

        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(HttpStatusCode.NoContent, accepted.status)
        assertEquals("x".repeat(80), fake.logoutToken)
    }

    @Test
    fun localAdminBypassRejectsPublicPeersAndProduction() {
        assertTrue(isLocalDevelopmentPeer("dev", "127.0.0.1"))
        assertTrue(isLocalDevelopmentPeer("test", "192.168.1.20"))
        assertFalse(isLocalDevelopmentPeer("dev", "8.8.8.8"))
        assertFalse(isLocalDevelopmentPeer("production", "127.0.0.1"))
    }

    @Test
    fun emailPasswordAndProductionCaptchaAreEnforcedBeforeGateway() = testApplication {
        val fake = FakeGateway()
        application {
            authTestModule(
                gateway = fake,
                configuration = testConfiguration().copy(
                    appEnvironment = "production",
                    captchaRequired = true,
                ),
            )
        }

        val phone = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"+34600000000","password":"strong-password"}""")
        }
        val shortPassword = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"person@example.com","password":"short123"}""")
        }
        val missingCaptcha = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"person@example.com","password":"strong-password"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, phone.status)
        assertEquals(HttpStatusCode.BadRequest, shortPassword.status)
        assertEquals(HttpStatusCode.BadRequest, missingCaptcha.status)
        assertEquals(0, fake.loginCalls)
        assertEquals(null, fake.registerRequest)
    }

    @Test
    fun forwardedForIsIgnoredUnlessImmediatePeerIsExplicitlyTrusted() {
        val configuration = testConfiguration().copy(
            trustProxyHeaders = true,
            trustedProxyHosts = setOf("10.0.0.10"),
        )

        assertEquals(
            "203.0.113.50",
            resolveTrustedClientIp(configuration, "203.0.113.50", "198.51.100.20"),
        )
        assertEquals(
            "198.51.100.20",
            resolveTrustedClientIp(configuration, "10.0.0.10", "192.0.2.99, 198.51.100.20"),
        )
        assertEquals(
            "10.0.0.10",
            resolveTrustedClientIp(configuration, "10.0.0.10", "spoofed.example"),
        )
    }

    @Test
    fun consumerProfileExposesOnlyLiveHealthAlias() = testApplication {
        application {
            configureConsumerAuthOnly(
                OposiBotsAuthRuntime(
                    configuration = testConfiguration(),
                    gateway = FakeGateway(),
                    rateLimiter = AllowingRateLimiter,
                    json = testJson,
                ),
            )
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    private fun io.ktor.server.application.Application.authTestModule(
        gateway: OposiBotsAuthGateway,
        rateLimiter: OposiBotsAuthRateLimiter = AllowingRateLimiter,
        configuration: OposiBotsAuthConfiguration = testConfiguration(),
    ) {
        install(ContentNegotiation) { json(testJson) }
        routing {
            oposiBotsAuthRoutes(
                OposiBotsAuthRuntime(
                    configuration = configuration,
                    gateway = gateway,
                    rateLimiter = rateLimiter,
                    json = testJson,
                ),
            )
        }
    }
}

private object AllowingRateLimiter : OposiBotsAuthRateLimiter {
    override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) =
        OposiBotsRateLimitDecision(true)

    override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String) =
        OposiBotsRateLimitDecision(true)
}

private object RejectingRateLimiter : OposiBotsAuthRateLimiter {
    override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) =
        OposiBotsRateLimitDecision(false, 42)

    override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String) =
        OposiBotsRateLimitDecision(false, 42)
}

private class FakeGateway : OposiBotsAuthGateway {
    var registerRequest: OposiBotsRegisterRequest? = null
    var loginCalls = 0
    var logoutToken: String? = null

    override suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext) =
        OposiBotsRegisterResponse().also { registerRequest = request }

    override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) = Unit
    override suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext) = session()
    override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext) =
        session().also { loginCalls++ }

    override suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext) = session()
    override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) {
        logoutToken = accessToken
    }

    override suspend fun me(accessToken: String, context: OposiBotsAuthContext) =
        OposiBotsMeResponse(session().user)

    override suspend fun saveProfile(
        accessToken: String,
        request: OposiBotsProfileRequest,
        context: OposiBotsAuthContext,
    ) = Unit

    override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) = Unit
    override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) = Unit
    override suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext) =
        OposiBotsOAuthResponse(session(), false)

    override suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext) =
        OposiBotsOAuthResponse(session(), false)

    private fun session() = OposiBotsAuthSessionDto(
        accessToken = "access",
        refreshToken = "refresh",
        expiresAtEpochSeconds = 2_000_000_000,
        user = OposiBotsAuthUserDto("user-id", "person@example.com", false),
    )
}

private fun testConfiguration() = OposiBotsAuthConfiguration(
    supabaseUrl = "https://example.supabase.co",
    publishableKey = "publishable",
    secretKey = "secret",
    appEnvironment = "test",
    passwordRecoveryRedirectUrl = null,
    appleClientId = null,
    appleClientSecret = null,
    captchaRequired = false,
    trustProxyHeaders = false,
    trustedProxyHosts = emptySet(),
    forwardClientIpToSupabase = false,
    rateLimitMode = "memory",
    rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
    valkeyUrl = null,
)
