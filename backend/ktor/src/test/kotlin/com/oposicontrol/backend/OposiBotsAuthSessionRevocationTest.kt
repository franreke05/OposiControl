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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Audit loop 1 (KTOR_SEC): alcance de la revocacion de sesiones en `/v1/auth`.
 *
 * - MASTER-AUTH-005: un replay/carrera de OAuth revoca SOLO la sesion acuñada (`scope=local`).
 * - MASTER-AUTH-006: reset-password revoca todas las sesiones (`scope=global`) tras cambiar la contraseña.
 * - MASTER-AUTH-010: un fallo TRANSITORIO de la puerta nunca revoca en global (local en login/OAuth, nada en refresh).
 * - MASTER-AUTH-011: la puerta corre antes de consumir la credencial de un solo uso; el reintento tras 503 funciona.
 */
class OposiBotsAuthSessionRevocationTest {
    private val context = OposiBotsAuthContext("request-sec", "127.0.0.1")

    @Test
    fun anOAuthReplayRevokesOnlyTheMintedSessionLocally() = runTest {
        val h = harness(claimStatus = HttpStatusCode.Conflict)
        val failure = assertFailsWith<OposiBotsAuthProblem> { h.gateway.google(OposiBotsGoogleRequest(jwt("g-replay")), context) }
        assertEquals(HttpStatusCode.Unauthorized, failure.status)
        val logout = h.logouts().single()
        assertEquals("scope=local", logout.url.encodedQuery)
        assertEquals("Bearer minted-access", logout.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aTransientGateFailureOnOAuthDoesNotConsumeTheCredentialAndTheRetrySucceeds() = runTest {
        val h = harness(profileStatus = HttpStatusCode.ServiceUnavailable)
        val first = assertFailsWith<OposiBotsAuthProblem> { h.gateway.google(OposiBotsGoogleRequest(jwt("g-1")), context) }
        assertEquals(HttpStatusCode.ServiceUnavailable, first.status)
        assertEquals(0, h.claims(), "la credencial no se consume si la puerta no pudo decidir")
        assertEquals(listOf("scope=local"), h.logouts().map { it.url.encodedQuery }, "nunca global por un fallo transitorio")

        h.profileStatus = HttpStatusCode.OK
        val retry = h.gateway.google(OposiBotsGoogleRequest(jwt("g-1")), context)
        assertEquals("minted-access", retry.session.accessToken)
        assertEquals(1, h.claims())
    }

    @Test
    fun aTransientGateFailureOnAppleNeverBurnsTheSingleUseAuthorizationCode() = runTest {
        val h = harness(profileStatus = HttpStatusCode.ServiceUnavailable, appleIdToken = jwt("a-1"))
        assertFailsWith<OposiBotsAuthProblem> { h.gateway.apple(OposiBotsAppleRequest(jwt("a-1"), "single-use-code"), context) }
        assertEquals(0, h.appleCalls(), "el authorizationCode no se canjea antes de la puerta")
        assertEquals(0, h.claims())

        h.profileStatus = HttpStatusCode.OK
        val retry = h.gateway.apple(OposiBotsAppleRequest(jwt("a-1"), "single-use-code"), context)
        assertEquals("minted-access", retry.session.accessToken)
        assertEquals(1, h.appleCalls())
        assertEquals(1, h.claims())
        val order = h.requests.map { it.url.host + it.url.encodedPath }
        assertTrue(order.lastIndexOf("example.supabase.co/rest/v1/perfiles_usuario") < order.lastIndexOf("appleid.apple.com/auth/token"), order.toString())
        assertTrue(order.lastIndexOf("appleid.apple.com/auth/token") < order.lastIndexOf("example.supabase.co/rest/v1/rpc/oposibots_claim_oauth_credential"), order.toString())
    }

    @Test
    fun aRejectedAppleCodeRevokesOnlyTheMintedSessionAndNeverClaims() = runTest {
        val h = harness(appleIdToken = jwt("otro-sujeto"))
        assertFailsWith<OposiBotsAuthProblem> { h.gateway.apple(OposiBotsAppleRequest(jwt("a-2"), "code"), context) }
        assertEquals(0, h.claims())
        assertEquals(listOf("scope=local"), h.logouts().map { it.url.encodedQuery })
    }

    @Test
    fun aTransientGateFailureOnLoginRevokesOnlyLocallyAndOnRefreshRevokesNothing() = runTest {
        val login = harness(profileStatus = HttpStatusCode.InternalServerError)
        val a = assertFailsWith<OposiBotsAuthProblem> {
            login.gateway.login(OposiBotsLoginRequest(email = "person@example.com", password = "safe-password-12"), context)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, a.status)
        assertEquals(listOf("scope=local"), login.logouts().map { it.url.encodedQuery })

        val refresh = harness(profileStatus = HttpStatusCode.InternalServerError)
        val b = assertFailsWith<OposiBotsAuthProblem> { refresh.gateway.refresh(OposiBotsRefreshRequest("old-refresh"), context) }
        assertEquals(HttpStatusCode.ServiceUnavailable, b.status)
        assertEquals(0, refresh.logouts().size, "el token rotado no se quema por una caida de lectura")
    }

    @Test
    fun aDefinitiveDenialStillRevokesGlobally() = runTest {
        val h = harness(profile = profileRow("bloqueada"))
        val failure = assertFailsWith<BackendDomainException> { h.gateway.refresh(OposiBotsRefreshRequest("r"), context) }
        assertEquals("ACCOUNT_BLOCKED", failure.errorCode)
        assertEquals(listOf("scope=global"), h.logouts().map { it.url.encodedQuery })
    }

    @Test
    fun aSuccessfulPasswordResetRevokesEverySessionWithTheRecoveryToken() = runTest {
        val h = harness(verifyBody = session(access = "recovery-access"))
        h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)

        val paths = h.requests.map { it.method.value + " " + it.url.encodedPath }
        assertEquals(listOf("POST /auth/v1/verify", "PUT /auth/v1/user", "POST /auth/v1/logout"), paths)
        val logout = h.logouts().single()
        assertEquals("scope=global", logout.url.encodedQuery)
        assertEquals("Bearer recovery-access", logout.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aFailedGlobalLogoutAfterTheResetNeverTurnsTheResetIntoAnError() = runTest {
        val h = harness(verifyBody = session(access = "recovery-access"), logoutStatus = HttpStatusCode.InternalServerError)
        h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
        assertEquals(1, h.logouts().size)

        val broken = harness(verifyBody = session(access = "recovery-access"), logoutThrows = true)
        broken.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
    }

    @Test
    fun aFailedPasswordUpdateRevokesOnlyTheRecoverySession() = runTest {
        val h = harness(verifyBody = session(access = "recovery-access"), userPutStatus = HttpStatusCode.UnprocessableEntity)
        assertFailsWith<OposiBotsAuthProblem> {
            h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
        }
        val logout = h.logouts().single()
        assertEquals("scope=local", logout.url.encodedQuery)
        assertEquals("Bearer recovery-access", logout.headers[HttpHeaders.Authorization])
    }

    /** MASTER7-AUTH-001: un PUT con resultado desconocido pudo cambiar la contraseña -> revoke GLOBAL y la peticion falla. */
    @Test
    fun aPasswordUpdateWithUnknownOutcomeRevokesEverySession() = runTest {
        val h = harness(verifyBody = session(access = "recovery-access"), userPutThrows = true)
        val failure = assertFailsWith<OposiBotsAuthProblem> {
            h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status, "el error original (unavailable) se propaga sin remapear")
        val logout = h.logouts().single()
        assertEquals("scope=global", logout.url.encodedQuery)
        assertEquals("Bearer recovery-access", logout.headers[HttpHeaders.Authorization])
    }

    /**
     * RC-09 (MASTERF1-AUTH-001 / MASTERF2-AUTH-002): un 5xx/408 del PUT /auth/v1/user es resultado DESCONOCIDO (la
     * contraseña pudo cambiar tras un 502/504 de proxy) -> revoke GLOBAL, y el error original sigue propagandose.
     */
    @Test
    fun aPasswordUpdateAnsweredWithGateway5xxOrTimeoutRevokesEverySession() = runTest {
        for (status in listOf(HttpStatusCode.BadGateway, HttpStatusCode.GatewayTimeout, HttpStatusCode.InternalServerError)) {
            val h = harness(verifyBody = session(access = "recovery-access"), userPutStatus = status)
            val failure = assertFailsWith<OposiBotsAuthProblem>("PUT -> $status") {
                h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, failure.status, "PUT -> $status: error original (unavailable) propagado")
            assertEquals(listOf("scope=global"), h.logouts().map { it.url.encodedQuery }, "PUT -> $status")
            assertEquals("Bearer recovery-access", h.logouts().single().headers[HttpHeaders.Authorization])
        }
        val timeout = harness(verifyBody = session(access = "recovery-access"), userPutStatus = HttpStatusCode.RequestTimeout)
        assertFails { timeout.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context) }
        assertEquals(listOf("scope=global"), timeout.logouts().map { it.url.encodedQuery }, "PUT -> 408")
    }

    /** RC-09: un 4xx definitivo (weak_password) sigue revocando SOLO la sesion de recuperacion. */
    @Test
    fun aDefinitiveWeakPasswordRejectionKeepsTheLocalRevoke() = runTest {
        val h = harness(
            verifyBody = session(access = "recovery-access"),
            userPutStatus = HttpStatusCode.BadRequest,
            userPutBody = """{"error_code":"weak_password","msg":"Password should be at least 12 characters"}""",
        )
        assertFails { h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context) }
        assertEquals(listOf("scope=local"), h.logouts().map { it.url.encodedQuery })
    }

    /** MASTER7-AUTH-001: si ademas falla el revoke global, el reset sigue FALLANDO con el error original (nunca exito). */
    @Test
    fun aFailedGlobalRevokeAfterAnUnknownPasswordUpdateStillFailsTheReset() = runTest {
        val h = harness(verifyBody = session(access = "recovery-access"), userPutThrows = true, logoutThrows = true)
        val failure = assertFailsWith<OposiBotsAuthProblem> {
            h.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
        assertEquals(listOf("scope=global"), h.logouts().map { it.url.encodedQuery })

        val rejected = harness(verifyBody = session(access = "recovery-access"), userPutThrows = true, logoutStatus = HttpStatusCode.InternalServerError)
        val second = assertFailsWith<OposiBotsAuthProblem> {
            rejected.gateway.reset(OposiBotsResetPasswordRequest("token-hash", "new-password-123"), context)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
        assertEquals(listOf("scope=global"), rejected.logouts().map { it.url.encodedQuery })
    }

    // ---------------- arnes ----------------

    private class Harness(val requests: MutableList<HttpRequestData>) {
        lateinit var gateway: SupabaseOposiBotsAuthGateway
        var profileStatus: HttpStatusCode = HttpStatusCode.OK
        fun logouts() = requests.filter { it.url.encodedPath == "/auth/v1/logout" }
        fun claims() = requests.count { it.url.encodedPath == "/rest/v1/rpc/oposibots_claim_oauth_credential" }
        fun appleCalls() = requests.count { it.url.host == "appleid.apple.com" }
    }

    private fun harness(
        profile: String = profileRow("activa"),
        profileStatus: HttpStatusCode = HttpStatusCode.OK,
        claimStatus: HttpStatusCode = HttpStatusCode.NoContent,
        verifyBody: String = session(),
        userPutStatus: HttpStatusCode = HttpStatusCode.OK,
        userPutThrows: Boolean = false,
        userPutBody: String = "{}",
        logoutStatus: HttpStatusCode = HttpStatusCode.NoContent,
        logoutThrows: Boolean = false,
        appleIdToken: String = "",
    ): Harness {
        val h = Harness(mutableListOf())
        h.profileStatus = profileStatus
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            h.requests += request
            val path = request.url.encodedPath
            when {
                request.url.host == "appleid.apple.com" -> respond(ByteReadChannel("""{"id_token":"$appleIdToken"}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/token" -> respond(ByteReadChannel(session()), HttpStatusCode.OK, json)
                path == "/auth/v1/verify" -> respond(ByteReadChannel(verifyBody), HttpStatusCode.OK, json)
                path == "/auth/v1/user" && request.method == HttpMethod.Put -> if (userPutThrows) error("put transport failure") else respond(ByteReadChannel(userPutBody), userPutStatus, json)
                path == "/auth/v1/logout" -> if (logoutThrows) error("logout transport failure") else respond(ByteReadChannel(""), logoutStatus, json)
                path == "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), claimStatus, json)
                path == "/rest/v1/perfiles_usuario" -> respond(
                    ByteReadChannel(if (h.profileStatus == HttpStatusCode.OK) profile else """{"message":"down"}"""),
                    h.profileStatus,
                    json,
                )
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val configuration = OposiBotsAuthConfiguration(
            supabaseUrl = "https://example.supabase.co",
            publishableKey = "client-publishable",
            secretKey = "server-secret",
            appEnvironment = "test",
            passwordRecoveryRedirectUrl = null,
            appleClientId = "apple-client",
            appleClientSecret = "apple-secret",
            captchaRequired = false,
            trustProxyHeaders = false,
            trustedProxyHosts = emptySet(),
            forwardClientIpToSupabase = false,
            rateLimitMode = "memory",
            rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
            valkeyUrl = null,
        )
        h.gateway = authGatewayWithoutAccessRequests(configuration, Json { ignoreUnknownKeys = true }, client)
        return h
    }

    private companion object {
        const val SEC_UID = "22222222-2222-4222-8222-222222222222"

        fun session(access: String = "minted-access") =
            """{"access_token":"$access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$SEC_UID","email":"person@example.com"}}"""

        fun profileRow(estado: String) =
            """[{"usuario_id":"$SEC_UID","estado_cuenta":"$estado","onboarding_completado":true}]"""

        fun jwt(subject: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = encoder.encodeToString("""{"sub":"$subject","exp":4102444800}""".toByteArray())
            return "$header.$payload.signature"
        }
    }
}
