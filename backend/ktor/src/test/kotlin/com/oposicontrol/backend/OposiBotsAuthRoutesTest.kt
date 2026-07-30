package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.AdminApiJson
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OposiBotsAuthRoutesTest {
    @Test
    fun `login is public and returns only the consumer session token`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application { configureConsumerAuthTestApplication(gateway) }

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    OposiBotsLoginRequest(
                        identifier = "person@example.com",
                        password = "password",
                        keepSignedIn = true,
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            OposiBotsLoginResponse(sessionToken = TEST_SESSION_TOKEN),
            AdminApiJson.format.decodeFromString(response.bodyAsText()),
        )
        assertEquals(1, gateway.loginCalls)
    }

    @Test
    fun `login reports every blank field in one Validation response`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application { configureConsumerAuthTestApplication(gateway) }

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    OposiBotsLoginRequest(
                        identifier = " ",
                        password = "",
                        keepSignedIn = false,
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.domainError()
        assertEquals(OposiBotsDomainErrorCode.VALIDATION, error.error)
        assertEquals(
            setOf(
                OposiBotsValidationIssue.BLANK_IDENTIFIER,
                OposiBotsValidationIssue.BLANK_PASSWORD,
            ),
            error.issues,
        )
        assertEquals(0, gateway.loginCalls)
    }

    @Test
    fun `login failure never distinguishes missing account from wrong password`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway().apply {
            loginFailure = OposiBotsAuthProblem.authenticationRequired()
        }
        application { configureConsumerAuthTestApplication(gateway) }

        val missingAccount = client.login("missing@example.com", "password")
        val wrongPassword = client.login("person@example.com", "wrong")

        assertEquals(HttpStatusCode.Unauthorized, missingAccount.status)
        assertEquals(missingAccount.bodyAsText(), wrongPassword.bodyAsText())
        assertEquals(
            OposiBotsDomainErrorCode.AUTHENTICATION_REQUIRED,
            missingAccount.domainError().error,
        )
    }

    @Test
    fun `register exposes duplicate identifier only as Conflict`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway().apply {
            registerFailure = OposiBotsAuthProblem.conflict()
        }
        application { configureConsumerAuthTestApplication(gateway) }

        val response = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    OposiBotsRegisterRequest(
                        identifier = "duplicate@example.com",
                        password = "password",
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(OposiBotsDomainErrorCode.CONFLICT, response.domainError().error)
    }

    @Test
    fun `profile requires bearer token separate from its DTO`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application { configureConsumerAuthTestApplication(gateway) }
        val body = AdminApiJson.format.encodeToString(
            OposiBotsRegisterAboutYouRequest(
                firstName = "Ana",
                lastName = "Diaz",
                username = null,
                specialty = "Auxiliar Administrativo",
            ),
        )

        val withoutBearer = client.post("/v1/auth/register/profile") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val withBearer = client.post("/v1/auth/register/profile") {
            header(HttpHeaders.Authorization, "Bearer $TEST_SESSION_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.Unauthorized, withoutBearer.status)
        assertEquals(HttpStatusCode.NoContent, withBearer.status)
        assertEquals(TEST_SESSION_TOKEN, gateway.profileAccessToken)
    }

    @Test
    fun `recover collapses account failures to the same no-content response`() = testApplication {
        val failures = ArrayDeque(
            listOf(
                OposiBotsAuthProblem.authenticationRequired(),
                OposiBotsAuthProblem.accessDenied(),
                OposiBotsAuthProblem.notFound(),
            ),
        )
        val gateway = FakeOposiBotsAuthGateway().apply {
            recoverFailureProvider = { failures.removeFirstOrNull() }
        }
        application { configureConsumerAuthTestApplication(gateway) }

        listOf("missing@example.com", "blocked@example.com", "existing@example.com").forEach { identifier ->
            val response = client.post("/v1/auth/recover") {
                contentType(ContentType.Application.Json)
                setBody(
                    AdminApiJson.format.encodeToString(
                        OposiBotsRecoverAccessRequest(identifier),
                    ),
                )
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(response.bodyAsText().isEmpty())
        }
    }

    @Test
    fun `reset rejects missing credential as AuthenticationRequired`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application { configureConsumerAuthTestApplication(gateway) }

        val response = client.post("/v1/auth/reset-password") {
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    OposiBotsResetPasswordRequest(
                        resetToken = "",
                        newPassword = "new-password",
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            OposiBotsDomainErrorCode.AUTHENTICATION_REQUIRED,
            response.domainError().error,
        )
        assertEquals(0, gateway.resetCalls)
    }

    @Test
    fun `empty provider token is integration failure and not Validation`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application { configureConsumerAuthTestApplication(gateway) }

        val response = client.post("/v1/auth/oauth/google") {
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    OposiBotsGoogleSignInRequest(idToken = ""),
                ),
            )
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            OposiBotsDomainErrorCode.TEMPORARILY_UNAVAILABLE,
            response.domainError().error,
        )
        assertEquals(0, gateway.googleCalls)
    }

    @Test
    fun `rate limiting maps to TemporarilyUnavailable without calling gateway`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application {
            configureConsumerAuthTestApplication(
                gateway = gateway,
                rateLimiter = OposiBotsAuthRateLimiter { _, _, _ -> false },
            )
        }

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    OposiBotsLoginRequest(
                        identifier = "person@example.com",
                        password = "password",
                        keepSignedIn = false,
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals(
            OposiBotsDomainErrorCode.TEMPORARILY_UNAVAILABLE,
            response.domainError().error,
        )
        assertEquals(0, gateway.loginCalls)
    }

    @Test
    fun `malformed JSON uses the consumer DomainError envelope`() = testApplication {
        val gateway = FakeOposiBotsAuthGateway()
        application { configureConsumerAuthTestApplication(gateway) }

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(OposiBotsDomainErrorCode.VALIDATION, response.domainError().error)
        assertEquals(0, gateway.loginCalls)
    }

    @Test
    fun `fixed window limiter resets only after its window expires`() {
        var now = 1_000L
        val limiter = InMemoryOposiBotsAuthRateLimiter(
            nowMillis = { now },
            policies = mapOf(
                OposiBotsAuthOperation.LOGIN to OposiBotsAuthRateLimitPolicy(
                    ipLimit = 1,
                    subjectLimit = 1,
                    windowMillis = 100,
                ),
            ),
        )

        assertTrue(limiter.allow(OposiBotsAuthOperation.LOGIN, "127.0.0.1", "person@example.com"))
        assertFalse(limiter.allow(OposiBotsAuthOperation.LOGIN, "127.0.0.1", "person@example.com"))
        now += 100
        assertTrue(limiter.allow(OposiBotsAuthOperation.LOGIN, "127.0.0.1", "person@example.com"))
    }

    private suspend fun io.ktor.client.HttpClient.login(
        identifier: String,
        password: String,
    ) = post("/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(
            AdminApiJson.format.encodeToString(
                OposiBotsLoginRequest(
                    identifier = identifier,
                    password = password,
                    keepSignedIn = false,
                ),
            ),
        )
    }
}

private fun io.ktor.server.application.Application.configureConsumerAuthTestApplication(
    gateway: OposiBotsAuthGateway,
    rateLimiter: OposiBotsAuthRateLimiter = OposiBotsAuthRateLimiter { _, _, _ -> true },
) {
    configureBackendPlatform(AdminTokenAuthenticator { null })
    routing {
        oposiBotsAuthRoutes(gateway, rateLimiter)
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.domainError(): OposiBotsDomainErrorResponse =
    AdminApiJson.format.decodeFromString(bodyAsText())

private class FakeOposiBotsAuthGateway : OposiBotsAuthGateway {
    var loginCalls = 0
    var resetCalls = 0
    var googleCalls = 0
    var profileAccessToken: String? = null
    var loginFailure: OposiBotsAuthProblem? = null
    var registerFailure: OposiBotsAuthProblem? = null
    var recoverFailureProvider: () -> OposiBotsAuthProblem? = { null }

    override suspend fun login(
        request: OposiBotsLoginRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsLoginResponse {
        loginCalls += 1
        loginFailure?.let { throw it }
        return OposiBotsLoginResponse(TEST_SESSION_TOKEN)
    }

    override suspend fun register(
        request: OposiBotsRegisterRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsRegisterResponse {
        registerFailure?.let { throw it }
        return OposiBotsRegisterResponse(TEST_SESSION_TOKEN)
    }

    override suspend fun completeRegistrationProfile(
        accessToken: String,
        request: OposiBotsRegisterAboutYouRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        profileAccessToken = accessToken
    }

    override suspend fun recoverAccess(
        request: OposiBotsRecoverAccessRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        recoverFailureProvider()?.let { throw it }
    }

    override suspend fun resetPassword(
        request: OposiBotsResetPasswordRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        resetCalls += 1
    }

    override suspend fun signInWithGoogle(
        request: OposiBotsGoogleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse {
        googleCalls += 1
        return OposiBotsOAuthSignInResponse(TEST_SESSION_TOKEN, isNewAccount = false)
    }

    override suspend fun signInWithApple(
        request: OposiBotsAppleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse =
        OposiBotsOAuthSignInResponse(TEST_SESSION_TOKEN, isNewAccount = false)
}

private const val TEST_SESSION_TOKEN =
    "test-session-token-that-is-long-enough-for-bearer-validation-0001"
