package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
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

/** MASTER3-AUTH-002: GoTrue 400/422 por la contraseña nueva de register/reset es 400 VALIDATION, nunca 401. */
class Master3AuthPasswordRejectionTest {
    private val context = OposiBotsAuthContext("request-123", "127.0.0.1")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun session() =
        """{"access_token":"recovery-access","refresh_token":"recovery-refresh","expires_in":3600,"user":{"id":"11111111-1111-4111-8111-111111111111"}}"""

    private fun gateway(requests: MutableList<HttpRequestData>, putStatus: HttpStatusCode, putBody: String) =
        authGatewayWithoutAccessRequests(
            m3Config(),
            Json { ignoreUnknownKeys = true },
            HttpClient(MockEngine { request ->
                requests += request
                when {
                    request.url.encodedPath == "/auth/v1/verify" -> respond(ByteReadChannel(session()), HttpStatusCode.OK, jsonHeaders)
                    request.url.encodedPath == "/auth/v1/user" && request.method == HttpMethod.Put ->
                        respond(ByteReadChannel(putBody), putStatus, jsonHeaders)
                    else -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, jsonHeaders)
                }
            }),
        )

    @Test
    fun resetWithARejectedPasswordIsValidationAndOnlyTheRecoverySessionIsRevoked() = runTest {
        listOf(
            HttpStatusCode.UnprocessableEntity to "weak_password",
            HttpStatusCode.UnprocessableEntity to "same_password",
            // MASTER4-API-003: `validation_failed` ya no es rechazo de contraseña por si solo (ver Master4AuthRegisterErrorMappingTest).
        ).forEach { (status, code) ->
            val requests = mutableListOf<HttpRequestData>()
            val gateway = gateway(requests, status, """{"code":${status.value},"error_code":"$code","msg":"rejected"}""")
            val problem = assertFailsWith<OposiBotsAuthProblem>(code) {
                gateway.reset(OposiBotsResetPasswordRequest("token-hash", "safe-password-12"), context)
            }
            assertEquals(HttpStatusCode.BadRequest, problem.status, code)
            assertEquals(CanonicalErrorCode.VALIDATION, problem.canonicalCode(), code)
            assertEquals(setOf(OposiBotsValidationIssue.INVALID_PASSWORD), problem.issues, code)
            val logout = requests.single { it.url.encodedPath == "/auth/v1/logout" }
            assertEquals("local", logout.url.parameters["scope"], "$code: la contraseña no cambio -> solo la sesion de recuperacion")
        }
    }

    @Test
    fun registerWithAWeakPasswordOrInvalidEmailIsValidation() = runTest {
        val weak = authGatewayWithoutAccessRequests(
            m3Config(), Json { ignoreUnknownKeys = true },
            HttpClient(MockEngine { respond(ByteReadChannel("""{"code":422,"error_code":"weak_password","msg":"x"}"""), HttpStatusCode.UnprocessableEntity, jsonHeaders) }),
        )
        assertEquals(
            setOf(OposiBotsValidationIssue.INVALID_PASSWORD),
            assertFailsWith<OposiBotsAuthProblem> { weak.register(OposiBotsRegisterRequest(email = "a@example.com", password = "safe-password-12"), context) }.issues,
        )
        val badEmail = authGatewayWithoutAccessRequests(
            m3Config(), Json { ignoreUnknownKeys = true },
            HttpClient(MockEngine { respond(ByteReadChannel("""{"code":422,"error_code":"email_address_invalid","msg":"x"}"""), HttpStatusCode.UnprocessableEntity, jsonHeaders) }),
        )
        val problem = assertFailsWith<OposiBotsAuthProblem> { badEmail.register(OposiBotsRegisterRequest(email = "a@example.com", password = "safe-password-12"), context) }
        assertEquals(HttpStatusCode.BadRequest, problem.status)
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_IDENTIFIER), problem.issues)
    }

    @Test
    fun otherResetFailuresAndPasswordCodesOutsideRegisterResetKeepTheirMapping() = runTest {
        // MASTER4-API-003 (cambio deliberado): un 422 de otro tipo en el PUT de reset era 401, pero no es un fallo del
        // token (ya verificado): es 400 VALIDATION sin campo. Un 401 del PUT (sesion de recuperacion invalida) sigue
        // siendo 401 (declarado en D1-10). weak_password en LOGIN no se reinterpreta como validacion.
        val other = gateway(mutableListOf(), HttpStatusCode.UnprocessableEntity, """{"code":422,"error_code":"something_else","msg":"x"}""")
        val otherProblem = assertFailsWith<OposiBotsAuthProblem> { other.reset(OposiBotsResetPasswordRequest("t", "safe-password-12"), context) }
        assertEquals(HttpStatusCode.BadRequest, otherProblem.status)
        assertEquals(CanonicalErrorCode.VALIDATION, otherProblem.canonicalCode())
        assertEquals(emptySet(), otherProblem.issues)
        val unauthorized = gateway(mutableListOf(), HttpStatusCode.Unauthorized, """{"code":401,"error_code":"bad_jwt","msg":"x"}""")
        assertEquals(
            HttpStatusCode.Unauthorized,
            assertFailsWith<OposiBotsAuthProblem> { unauthorized.reset(OposiBotsResetPasswordRequest("t", "safe-password-12"), context) }.status,
        )
        val login = authGatewayWithoutAccessRequests(
            m3Config(), Json { ignoreUnknownKeys = true },
            HttpClient(MockEngine { respond(ByteReadChannel("""{"code":422,"error_code":"weak_password","msg":"x"}"""), HttpStatusCode.UnprocessableEntity, jsonHeaders) }),
        )
        assertFailsWith<Exception> { login.login(OposiBotsLoginRequest("a@example.com", "safe-password-12"), context) }.let {
            assertEquals(false, (it as? OposiBotsAuthProblem)?.code == OposiBotsErrorCode.Validation)
        }
    }
}

private fun m3Config() = OposiBotsAuthConfiguration(
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
