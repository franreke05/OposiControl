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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** FA39: the email deep-link code is bound to the device that asked for the email (PKCE-style), enforced before GoTrue spends the token. */
class EmailCodeBindingTest {
    private val context = OposiBotsAuthContext("request-123", "127.0.0.1")
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    private val otherVerifier = "x".repeat(43)

    private class Rig(val gateway: SupabaseOposiBotsAuthGateway, val binding: InMemoryEmailCodeBinding, val requests: MutableList<HttpRequestData>) {
        fun verifies() = requests.count { it.url.encodedPath == "/auth/v1/verify" }
        fun logouts() = requests.count { it.url.encodedPath == "/auth/v1/logout" }
    }

    private fun rig(mode: PkceMode = PkceMode.OPTIONAL, sessionEmail: String = "person@example.com", verifyStatus: HttpStatusCode = HttpStatusCode.OK): Rig {
        val requests = mutableListOf<HttpRequestData>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/signup", "/auth/v1/resend", "/auth/v1/recover" -> respond(ByteReadChannel("""{"user":{"id":"u"}}"""), HttpStatusCode.OK, json)
                "/auth/v1/verify" ->
                    if (verifyStatus == HttpStatusCode.OK) respond(ByteReadChannel(session(sessionEmail)), HttpStatusCode.OK, json)
                    else respond(ByteReadChannel("""{"code":403,"error_code":"otp_expired","msg":"expired"}"""), verifyStatus, json)
                "/auth/v1/user" -> respond(ByteReadChannel("""{"id":"$UID","email":"$sessionEmail"}"""), HttpStatusCode.OK, json)
                "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                "/rest/v1/perfiles_usuario" -> respond(ByteReadChannel("""[{"usuario_id":"$UID","estado_cuenta":"activa","onboarding_completado":true}]"""), HttpStatusCode.OK, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val binding = InMemoryEmailCodeBinding()
        val configuration = OposiBotsAuthConfiguration(
            supabaseUrl = "https://example.supabase.co", publishableKey = "client-publishable", secretKey = "server-secret", appEnvironment = "test",
            passwordRecoveryRedirectUrl = null, appleClientId = null, appleClientSecret = null, captchaRequired = false, trustProxyHeaders = false,
            trustedProxyHosts = emptySet(), forwardClientIpToSupabase = false, rateLimitMode = "memory", rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
            valkeyUrl = null, pkceMode = mode,
        )
        val gateway = SupabaseOposiBotsAuthGateway(configuration, Json { ignoreUnknownKeys = true }, NoAccessRequestsSessionGate, client, null, binding)
        return Rig(gateway, binding, requests)
    }

    private fun session(email: String) = """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$UID","email":"$email"}}"""

    @Test
    fun theChallengeIsTheRfc7636S256VectorAndTheVerifierShapeIsEnforced() {
        assertEquals(challenge, Pkce.challengeOf(verifier))
        assertTrue(Pkce.CHALLENGE.matches(challenge) && !Pkce.CHALLENGE.matches(challenge + "="))
        assertTrue(Pkce.VERIFIER.matches(verifier) && !Pkce.VERIFIER.matches("short") && !Pkce.VERIFIER.matches("a".repeat(129)) && !Pkce.VERIFIER.matches("a".repeat(43) + " "))
    }

    @Test
    fun theEmailRequestsStoreTheChallengeBeforeTheEmailIsSent() = runTest {
        val r = rig()
        r.gateway.register(OposiBotsRegisterRequest(email = "Person@Example.com", password = "safe-password-12", codeChallenge = challenge), context)
        r.gateway.resend(OposiBotsEmailResendRequest("person@example.com", codeChallenge = Pkce.challengeOf(otherVerifier)), context)
        r.gateway.recover(OposiBotsRecoverRequest(email = "person@example.com", codeChallenge = Pkce.challengeOf("y".repeat(43))), context)
        // SEC-V1-08: the resend (sent) superseded the signup binding: one valid binding per (purpose, email)
        assertEquals(2, r.binding.size())
        assertEquals(listOf("/auth/v1/signup", "/auth/v1/resend", "/auth/v1/recover"), r.requests.map { it.url.encodedPath })
        // optional mode: no challenge = legacy behaviour, nothing stored
        r.gateway.recover(OposiBotsRecoverRequest(email = "person@example.com"), context)
        assertEquals(2, r.binding.size())
    }

    @Test
    fun requiredModeDemandsTheChallengeAndTheVerifier() = runTest {
        val r = rig(PkceMode.REQUIRED)
        val noChallenge = assertFailsWith<OposiBotsAuthProblem> { r.gateway.recover(OposiBotsRecoverRequest(email = "person@example.com"), context) }
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_CODE_CHALLENGE), noChallenge.issues)
        val noVerifier = assertFailsWith<OposiBotsAuthProblem> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("hash"), context) }
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_CODE_VERIFIER), noVerifier.issues)
        assertEquals(0, r.requests.size, "nothing reached GoTrue")
    }

    @Test
    fun exchangeWithTheRightVerifierWorksOnceAndTheReplayNeverReachesGoTrue() = runTest {
        val r = rig()
        r.gateway.register(OposiBotsRegisterRequest(email = "person@example.com", password = "safe-password-12", codeChallenge = challenge), context)
        val s = r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("hash", codeVerifier = verifier), context)
        assertEquals("minted-access", s.accessToken)
        assertEquals(1, r.verifies())
        assertEquals(0, r.binding.size())
        val replay = assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("hash", codeVerifier = verifier), context) }
        assertEquals("AUTH_CODE_INVALID", replay.errorCode)
        assertEquals(1, r.verifies(), "the second attempt was refused before GoTrue")
    }

    @Test
    fun aStolenCodeWithoutTheVerifierIsRefusedAndNotSpent() = runTest {
        val r = rig()
        r.gateway.register(OposiBotsRegisterRequest(email = "person@example.com", password = "safe-password-12", codeChallenge = challenge), context)
        val thief = assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("stolen", codeVerifier = otherVerifier), context) }
        assertEquals("AUTH_CODE_INVALID", thief.errorCode)
        assertEquals(401, thief.status.value)
        assertEquals(0, r.verifies(), "GoTrue never saw the code, so the one-time token is intact")
        assertEquals(1, r.binding.size(), "and the legitimate binding is intact")
        // the owner still exchanges
        assertNotNull(r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("stolen", codeVerifier = verifier), context))
    }

    @Test
    fun theRefusalIsIndistinguishableFromAnInvalidCode() = runTest {
        val unbound = assertFailsWith<BackendDomainException> { rig().gateway.exchangeEmail(OposiBotsEmailExchangeRequest("c", codeVerifier = verifier), context) }
        val r = rig(verifyStatus = HttpStatusCode.Forbidden)
        r.gateway.register(OposiBotsRegisterRequest(email = "person@example.com", password = "safe-password-12", codeChallenge = challenge), context)
        val expired = assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("c", codeVerifier = verifier), context) }
        assertEquals(expired.errorCode to expired.status, unbound.errorCode to unbound.status)
        assertEquals(expired.message, unbound.message)
    }

    @Test
    fun aWrongEmailHintNeverSpendsSomebodyElsesBinding() = runTest {
        val r = rig()
        r.gateway.register(OposiBotsRegisterRequest(email = "person@example.com", password = "safe-password-12", codeChallenge = challenge), context)
        assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("c", codeVerifier = verifier, email = "attacker@example.com"), context) }
        assertEquals(1, r.binding.size())
        assertNotNull(r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("c", codeVerifier = verifier, email = " Person@Example.com "), context))
    }

    @Test
    fun aCodeOfAnotherAccountCannotBeRedeemedWithTheAttackersOwnBinding() = runTest {
        // the attacker requested a signup mail for his own address with his own verifier, then presents the VICTIM's stolen code
        val r = rig(sessionEmail = "victim@example.com")
        r.gateway.register(OposiBotsRegisterRequest(email = "attacker@example.com", password = "safe-password-12", codeChallenge = challenge), context)
        val e = assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("victims-code", codeVerifier = verifier), context) }
        assertEquals("AUTH_CODE_INVALID", e.errorCode)
        assertEquals(1, r.logouts(), "the session GoTrue minted for the victim is revoked at once")
    }

    @Test
    fun withoutTheFieldsOptionalModeKeepsTheLegacyFlowAndOffModeIgnoresThem() = runTest {
        val legacy = rig()
        assertNotNull(legacy.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("hash"), context))
        val off = rig(PkceMode.OFF)
        off.gateway.recover(OposiBotsRecoverRequest(email = "person@example.com", codeChallenge = challenge), context)
        assertEquals(0, off.binding.size())
        assertNotNull(off.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("hash", codeVerifier = otherVerifier), context))
    }

    @Test
    fun passwordResetIsBoundTheSameWay() = runTest {
        val r = rig()
        r.gateway.recover(OposiBotsRecoverRequest(email = "person@example.com", codeChallenge = challenge), context)
        val thief = assertFailsWith<OposiBotsAuthProblem> { r.gateway.reset(OposiBotsResetPasswordRequest("stolen", "New-safe-password-12", codeVerifier = otherVerifier), context) }
        assertEquals(HttpStatusCode.Unauthorized, thief.status)
        assertEquals(0, r.verifies())
        // a signup binding does not open a recovery
        r.gateway.register(OposiBotsRegisterRequest(email = "person@example.com", password = "safe-password-12", codeChallenge = Pkce.challengeOf("z".repeat(43))), context)
        assertFailsWith<OposiBotsAuthProblem> { r.gateway.reset(OposiBotsResetPasswordRequest("t", "New-safe-password-12", codeVerifier = "z".repeat(43)), context) }
        assertEquals(0, r.verifies())
        r.gateway.reset(OposiBotsResetPasswordRequest("good", "New-safe-password-12", codeVerifier = verifier, email = "person@example.com"), context)
        assertEquals(1, r.verifies())
        assertTrue(r.requests.any { it.url.encodedPath == "/auth/v1/user" && it.method == HttpMethod.Put })
    }

    @Test
    fun theBindingTableIsReachedOnlyThroughServiceRoleRpcsWithShapesTheDatabaseChecks() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val binding = PostgrestEmailCodeBinding(rpc = { fn, body ->
            sent += fn to body
            when (fn) {
                "auth_code_binding_consume" -> "\"${"a".repeat(64)}\""
                "auth_code_binding_begin" -> "42"
                else -> ""
            }
        })
        assertEquals(42L, binding.begin("signup", "a".repeat(64), challenge))
        binding.activate(42L)
        assertEquals("a".repeat(64), binding.consume("signup", null, challenge))
        assertEquals(listOf("auth_code_binding_begin", "auth_code_binding_activate", "auth_code_binding_consume"), sent.map { it.first })
        assertTrue(sent[0].second.contains("\"p_challenge\":\"$challenge\"") && !sent[0].second.contains("@"), "only hashes travel")
        assertEquals("""{"p_id":42}""", sent[1].second)
        val none = PostgrestEmailCodeBinding(rpc = { _, _ -> "null" })
        assertEquals(null, none.consume("signup", "a".repeat(64), challenge))
        assertEquals(null, none.begin("signup", "a".repeat(64), challenge), "challenge already bound to another email")
        val down = PostgrestEmailCodeBinding(rpc = { _, _ -> error("db down") })
        assertEquals(HttpStatusCode.ServiceUnavailable, assertFailsWith<OposiBotsAuthProblem> { down.consume("signup", null, challenge) }.status, "fail closed")
        assertEquals(HttpStatusCode.ServiceUnavailable, assertFailsWith<OposiBotsAuthProblem> { down.begin("signup", "a".repeat(64), challenge) }.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, assertFailsWith<OposiBotsAuthProblem> { down.activate(42L) }.status)
    }

    private companion object {
        const val UID = "11111111-1111-4111-8111-111111111111"
    }
}
