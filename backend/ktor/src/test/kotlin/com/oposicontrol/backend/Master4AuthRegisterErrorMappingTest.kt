package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * MASTER4-API-003: `validation_failed` (generico de GoTrue) se etiquetaba siempre INVALID_PASSWORD aunque fuera un
 * email mal formado, y el resto de 400/422 de register (captcha_failed, signup_disabled...) caia en 401
 * AUTHENTICATION_REQUIRED, que D1-01 no declara. D1-01 declara solo VALIDATION (400), RATE_LIMITED (429) y
 * UPSTREAM_UNAVAILABLE (503).
 */
class Master4AuthRegisterErrorMappingTest {
    private val context = OposiBotsAuthContext("request-m4", "127.0.0.1")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val register = OposiBotsRegisterRequest(email = "a@example.com", password = "safe-password-12")

    private suspend fun registerFailingWith(status: HttpStatusCode, code: String, msg: String): OposiBotsAuthProblem {
        val gateway = authGatewayWithoutAccessRequests(
            m4Config(), Json { ignoreUnknownKeys = true },
            HttpClient(MockEngine { respond(ByteReadChannel("""{"code":${status.value},"error_code":"$code","msg":"$msg"}"""), status, jsonHeaders) }),
        )
        return assertFailsWith<OposiBotsAuthProblem>("$status $code") { gateway.register(register, context) }
    }


    @Test
    fun aMalformedEmailRejectedWithTheGenericValidationCodeIsAnIdentifierIssueNotAPasswordIssue() = runTest {
        val problem = registerFailingWith(HttpStatusCode.BadRequest, "validation_failed", "Unable to validate email address: invalid format")
        assertEquals(HttpStatusCode.BadRequest, problem.status)
        assertEquals(CanonicalErrorCode.VALIDATION, problem.canonicalCode())
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_IDENTIFIER), problem.issues)
    }

    @Test
    fun theGenericValidationCodeNamingThePasswordIsStillAPasswordIssueAndOtherwiseCarriesNoField() = runTest {
        assertEquals(
            setOf(OposiBotsValidationIssue.INVALID_PASSWORD),
            registerFailingWith(HttpStatusCode.UnprocessableEntity, "validation_failed", "Password cannot be longer than 72 characters").issues,
        )
        val generic = registerFailingWith(HttpStatusCode.BadRequest, "validation_failed", "Invalid request body")
        assertEquals(HttpStatusCode.BadRequest, generic.status)
        assertEquals(CanonicalErrorCode.VALIDATION, generic.canonicalCode())
        assertEquals(emptySet(), generic.issues, "sin campo: nunca se inventa INVALID_PASSWORD")
    }

    @Test
    fun aRejectedCaptchaIsAValidationIssueOnTheCaptchaField() = runTest {
        val problem = registerFailingWith(HttpStatusCode.BadRequest, "captcha_failed", "captcha protection: request disallowed")
        assertEquals(HttpStatusCode.BadRequest, problem.status)
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_CAPTCHA), problem.issues)
    }

    @Test
    fun serviceConfigurationRejectionsAreUpstreamUnavailableNever401() = runTest {
        listOf(
            HttpStatusCode.UnprocessableEntity to "signup_disabled",
            HttpStatusCode.BadRequest to "email_provider_disabled",
            HttpStatusCode.BadRequest to "email_address_not_authorized",
        ).forEach { (status, code) ->
            val problem = registerFailingWith(status, code, "x")
            assertEquals(HttpStatusCode.ServiceUnavailable, problem.status, code)
            assertEquals(CanonicalErrorCode.UPSTREAM_UNAVAILABLE, problem.canonicalCode(), code)
        }
    }

    @Test
    fun registerNeverAnswersWithAnUndeclared401WhateverGoTrueReturns() = runTest {
        val declared = setOf(CanonicalErrorCode.VALIDATION, CanonicalErrorCode.RATE_LIMITED, CanonicalErrorCode.UPSTREAM_UNAVAILABLE)
        listOf(
            HttpStatusCode.BadRequest to "something_new",
            HttpStatusCode.UnprocessableEntity to "something_new",
            HttpStatusCode.Unauthorized to "no_authorization",
            HttpStatusCode.Forbidden to "not_admin",
            HttpStatusCode.NotFound to "not_found",
        ).forEach { (status, code) ->
            val problem = registerFailingWith(status, code, "x")
            assertEquals(true, problem.canonicalCode() in declared, "$status $code -> ${problem.canonicalCode()}")
        }
    }

    @Test
    fun aDuplicateSignupIsAcceptedLikeANewOneWhateverTheClientErrorStatus() = runTest {
        listOf(HttpStatusCode.UnprocessableEntity, HttpStatusCode.BadRequest).forEach { status ->
            listOf("user_already_exists", "email_exists").forEach { code ->
                val gateway = authGatewayWithoutAccessRequests(
                    m4Config(), Json { ignoreUnknownKeys = true },
                    HttpClient(MockEngine { respond(ByteReadChannel("""{"code":${status.value},"error_code":"$code","msg":"x"}"""), status, jsonHeaders) }),
                )
                // Sin excepcion: la misma respuesta (202) que un alta nueva; nunca un 400 que revele que el email existe.
                gateway.register(register, context)
            }
        }
    }

    @Test
    fun theResetPasswordStepAppliesTheSameClassification() = runTest {
        val session =
            """{"access_token":"recovery-access","refresh_token":"recovery-refresh","expires_in":3600,"user":{"id":"11111111-1111-4111-8111-111111111111"}}"""
        val gateway = authGatewayWithoutAccessRequests(
            m4Config(), Json { ignoreUnknownKeys = true },
            HttpClient(MockEngine { request ->
                when {
                    request.url.encodedPath == "/auth/v1/verify" -> respond(ByteReadChannel(session), HttpStatusCode.OK, jsonHeaders)
                    request.url.encodedPath == "/auth/v1/user" && request.method == HttpMethod.Put -> respond(
                        ByteReadChannel("""{"code":422,"error_code":"validation_failed","msg":"Password cannot be longer than 72 characters"}"""),
                        HttpStatusCode.UnprocessableEntity,
                        jsonHeaders,
                    )
                    else -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, jsonHeaders)
                }
            }),
        )
        val problem = assertFailsWith<OposiBotsAuthProblem> {
            gateway.reset(OposiBotsResetPasswordRequest("token-hash", "safe-password-12"), context)
        }
        assertEquals(HttpStatusCode.BadRequest, problem.status)
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_PASSWORD), problem.issues)
    }
}

private fun m4Config() = OposiBotsAuthConfiguration(
    supabaseUrl = "https://example.supabase.co",
    publishableKey = "client-publishable",
    secretKey = "server-secret",
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
