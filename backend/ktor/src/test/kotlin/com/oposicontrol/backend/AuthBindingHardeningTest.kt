package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2 security hardening of the auth plane:
 *  - RB-04 / SEC-V1-04: outside development only AUTH_PKCE_MODE=required starts; required mode refuses exchange/reset without verifier.
 *  - SEC-V1-08: the email code is bound to ONE email request (the last one GoTrue sent), not to the email; the attacker's own challenge
 *    can no longer be paired with the victim's intercepted code.
 *  - SEC-V1-11: with AUTH_OAUTH_NONCE_REQUIRED the Google/Apple ID token must carry nonce = SHA-256(raw nonce).
 *  - Callback: the redirect_to the backend hands GoTrue is allow-listed at startup.
 */
class AuthBindingHardeningTest {
    private val context = OposiBotsAuthContext("request-p2", "127.0.0.1")
    private val json = Json { ignoreUnknownKeys = true }
    private val victimVerifier = "v".repeat(43)
    private val attackerVerifier = "a".repeat(43)

    // ---------------------------------------------------------------- rig

    private class Rig(
        val gateway: SupabaseOposiBotsAuthGateway,
        val binding: InMemoryEmailCodeBinding,
        val requests: MutableList<HttpRequestData>,
        val mailStatuses: ArrayDeque<HttpStatusCode>,
        val configuration: OposiBotsAuthConfiguration,
    ) {
        fun verifies() = requests.count { it.url.encodedPath == "/auth/v1/verify" }
        fun tokens() = requests.count { it.url.encodedPath == "/auth/v1/token" }
    }

    private fun configuration(
        environment: String = "test",
        mode: PkceMode = PkceMode.REQUIRED,
        nonceRequired: Boolean = false,
        recoveryRedirect: String? = null,
        confirmRedirect: String? = null,
        apple: Boolean = false,
    ) = OposiBotsAuthConfiguration(
        supabaseUrl = "https://example.supabase.co", publishableKey = "client-publishable", secretKey = "server-secret", appEnvironment = environment,
        passwordRecoveryRedirectUrl = recoveryRedirect, appleClientId = if (apple) "com.example.app" else null, appleClientSecret = if (apple) "apple-secret" else null,
        captchaRequired = false, trustProxyHeaders = environment != "test", trustedProxyHosts = if (environment != "test") setOf("10.0.0.10") else emptySet(),
        forwardClientIpToSupabase = false, rateLimitMode = if (environment != "test") "valkey" else "memory", rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
        valkeyUrl = if (environment != "test") "redis://valkey:6379" else null, emailConfirmRedirectUrl = confirmRedirect, pkceMode = mode, oauthNonceRequired = nonceRequired,
    )

    private fun rig(configuration: OposiBotsAuthConfiguration = configuration(), sessionEmail: String = VICTIM): Rig {
        val requests = mutableListOf<HttpRequestData>()
        val mailStatuses = ArrayDeque<HttpStatusCode>()
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/signup", "/auth/v1/resend", "/auth/v1/recover" -> {
                    val status = mailStatuses.removeFirstOrNull() ?: HttpStatusCode.OK
                    val body = when (status) {
                        HttpStatusCode.OK -> """{"user":{"id":"u"}}"""
                        HttpStatusCode.TooManyRequests -> """{"code":429,"error_code":"over_email_send_rate_limit","msg":"wait"}"""
                        HttpStatusCode.UnprocessableEntity -> """{"code":422,"error_code":"user_already_exists","msg":"dup"}"""
                        else -> """{"code":${status.value},"error_code":"unexpected_failure","msg":"x"}"""
                    }
                    respond(ByteReadChannel(body), status, headers)
                }
                "/auth/v1/verify", "/auth/v1/token" -> respond(ByteReadChannel(session(sessionEmail)), HttpStatusCode.OK, headers)
                "/auth/v1/user" -> respond(ByteReadChannel("""{"id":"$UID","email":"$sessionEmail"}"""), HttpStatusCode.OK, headers)
                "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, headers)
                "/rest/v1/perfiles_usuario" -> respond(ByteReadChannel("""[{"usuario_id":"$UID","estado_cuenta":"activa","onboarding_completado":true}]"""), HttpStatusCode.OK, headers)
                "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, headers)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val binding = InMemoryEmailCodeBinding()
        val gateway = SupabaseOposiBotsAuthGateway(configuration, json, NoAccessRequestsSessionGate, client, null, binding)
        return Rig(gateway, binding, requests, mailStatuses, configuration)
    }

    private fun session(email: String) = """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$UID","email":"$email"}}"""

    private fun recover(verifier: String) = OposiBotsRecoverRequest(email = VICTIM, codeChallenge = Pkce.challengeOf(verifier))

    private fun reset(code: String, verifier: String?) = OposiBotsResetPasswordRequest(code, "New-safe-password-12", codeVerifier = verifier)

    // ---------------------------------------------------------------- RB-04 / SEC-V1-04: startup

    @Test
    fun outsideDevelopmentOnlyRequiredPkceStarts() {
        with(OposiBotsAuthConfiguration.Companion) {
            for (environment in listOf("production", "staging", "", "dev", "development")) {
                for (mode in listOf(PkceMode.OPTIONAL, PkceMode.OFF)) {
                    val failure = assertFailsWith<IllegalArgumentException>("$environment/$mode must not start") {
                        configuration(environment = environment, mode = mode).requireDeployable()
                    }
                    assertTrue("AUTH_PKCE_MODE=required" in failure.message.orEmpty(), failure.message)
                }
                configuration(environment = environment, mode = PkceMode.REQUIRED).requireDeployable()
            }
            // optional stays usable in local / test
            for (environment in listOf("local", "test")) {
                configuration(environment = environment, mode = PkceMode.OPTIONAL).requireDeployable()
                configuration(environment = environment, mode = PkceMode.OFF).requireDeployable()
            }
        }
        assertEquals(PkceMode.REQUIRED, PkceMode.parse(" Required "))
        assertFailsWith<IllegalStateException> { PkceMode.parse("requird") }
    }

    // ---------------------------------------------------------------- RB-04 / SEC-V1-04: required mode over HTTP

    @Test
    fun requiredModeRefusesExchangeAndResetWithoutVerifierAndMailRequestsWithoutChallenge() = testApplication {
        val r = rig()
        application { mount(r) }
        suspend fun post(path: String, body: String) = client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

        val cases = listOf(
            Triple("/v1/auth/email/exchange", """{"code":"intercepted-token-hash"}""", "codeVerifier"),
            Triple("/v1/auth/reset-password", """{"resetToken":"intercepted-token-hash","newPassword":"New-safe-password-12"}""", "codeVerifier"),
            Triple("/v1/auth/recover", """{"email":"$VICTIM"}""", "codeChallenge"),
            Triple("/v1/auth/register", """{"email":"$VICTIM","password":"safe-password-12"}""", "codeChallenge"),
            Triple("/v1/auth/email/resend", """{"email":"$VICTIM"}""", "codeChallenge"),
        )
        for ((path, body, field) in cases) {
            val response = post(path, body)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertEquals(field, issueField(response.bodyAsText()), path)
        }
        assertEquals(0, r.requests.size, "nothing reached GoTrue: the intercepted token is neither used nor spent")
    }

    @Test
    fun optionalModeStaysUsableInTestWithTheLegacyBody() = testApplication {
        val r = rig(configuration(mode = PkceMode.OPTIONAL))
        application { mount(r) }
        val response = client.post("/v1/auth/email/exchange") { contentType(ContentType.Application.Json); setBody("""{"code":"hash"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ---------------------------------------------------------------- SEC-V1-08: one binding per email request

    @Test
    fun attackerChallengeRegisteredBeforeTheVictimCannotRedeemTheVictimsInterceptedCode() = runTest {
        val r = rig()
        r.gateway.recover(recover(attackerVerifier), context) // attacker asks a recovery mail for the victim with HIS challenge (sent)
        r.gateway.recover(recover(victimVerifier), context)   // later the victim asks; GoTrue replaces the token: only this mail's code is valid
        val takeover = assertFailsWith<OposiBotsAuthProblem> { r.gateway.reset(reset("victim-code", attackerVerifier), context) }
        assertEquals(HttpStatusCode.Unauthorized, takeover.status)
        assertEquals(0, r.verifies(), "refused before GoTrue: the victim's one-time token is intact")
        r.gateway.reset(reset("victim-code", victimVerifier), context)
        assertEquals(1, r.verifies(), "the victim still resets with the code of her own mail")
    }

    @Test
    fun attackerChallengeThrottledAfterTheVictimNeverBecomesValid() = runTest {
        val r = rig()
        r.gateway.recover(recover(victimVerifier), context)
        r.mailStatuses += HttpStatusCode.TooManyRequests // GoTrue throttles the attacker's request: no new token, the victim's stays valid
        r.gateway.recover(recover(attackerVerifier), context) // answered 204 like any other (no oracle)
        val takeover = assertFailsWith<OposiBotsAuthProblem> { r.gateway.reset(reset("victim-code", attackerVerifier), context) }
        assertEquals(HttpStatusCode.Unauthorized, takeover.status)
        assertEquals(0, r.verifies())
        r.gateway.reset(reset("victim-code", victimVerifier), context)
        assertEquals(1, r.verifies())
    }

    @Test
    fun aMailGoTrueRefusedOrFailedLeavesItsBindingPending() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError, HttpStatusCode.UnprocessableEntity)) {
            val r = rig()
            r.mailStatuses += status
            runCatching { r.gateway.recover(recover(attackerVerifier), context) }
            assertEquals(0, r.binding.activeCount(), "$status: not sent = not valid")
            assertFailsWith<OposiBotsAuthProblem>("$status") { r.gateway.reset(reset("code", attackerVerifier), context) }
            assertEquals(0, r.verifies())
        }
        // signup: a duplicate (422 user_already_exists, answered 202) never activates either
        val r = rig()
        r.mailStatuses += HttpStatusCode.UnprocessableEntity
        r.gateway.register(OposiBotsRegisterRequest(email = VICTIM, password = "safe-password-12", codeChallenge = Pkce.challengeOf(attackerVerifier)), context)
        assertEquals(0, r.binding.activeCount())
        assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("victim-code", codeVerifier = attackerVerifier), context) }
        assertEquals(0, r.verifies())
    }

    @Test
    fun signupAndResendFollowTheSameRuleOnlyTheLastSentMailIsValid() = runTest {
        val r = rig()
        r.gateway.resend(OposiBotsEmailResendRequest(VICTIM, codeChallenge = Pkce.challengeOf(attackerVerifier)), context)
        r.gateway.register(OposiBotsRegisterRequest(email = VICTIM, password = "safe-password-12", codeChallenge = Pkce.challengeOf(victimVerifier)), context)
        val takeover = assertFailsWith<BackendDomainException> { r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("victim-code", codeVerifier = attackerVerifier), context) }
        assertEquals("AUTH_CODE_INVALID", takeover.errorCode)
        assertEquals(0, r.verifies())
        assertNotNull(r.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("victim-code", codeVerifier = victimVerifier), context))
    }

    @Test
    fun aChallengeAlreadyBoundToAnotherEmailIsRefusedBeforeAnyMail() = runTest {
        val r = rig()
        r.gateway.recover(OposiBotsRecoverRequest(email = "someone@example.com", codeChallenge = Pkce.challengeOf(attackerVerifier)), context)
        val reuse = assertFailsWith<OposiBotsAuthProblem> { r.gateway.recover(recover(attackerVerifier), context) }
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_CODE_CHALLENGE), reuse.issues)
        assertEquals(1, r.requests.count { it.url.encodedPath == "/auth/v1/recover" }, "no mail for the reused challenge")
    }

    @Test
    fun inMemoryBindingRulesMirrorTheSqlFunctions() = runTest {
        val b = InMemoryEmailCodeBinding()
        val e1 = "1".repeat(64)
        val c1 = Pkce.challengeOf(victimVerifier)
        val c2 = Pkce.challengeOf(attackerVerifier)
        val id1 = assertNotNull(b.begin("recovery", e1, c1))
        assertNull(b.consume("recovery", e1, c1), "pending is not consumable")
        assertEquals(id1, b.begin("recovery", e1, c1), "same challenge + same email = same binding")
        assertNull(b.begin("recovery", "2".repeat(64), c1), "first registration wins")
        b.activate(id1)
        val id2 = assertNotNull(b.begin("recovery", e1, c2))
        assertEquals(1, b.activeCount(), "a pending newer binding does not displace the valid one")
        b.activate(id2)
        assertNull(b.consume("recovery", e1, c1), "superseded")
        assertEquals(e1, b.consume("recovery", null, c2))
        assertNull(b.consume("recovery", null, c2), "single use")
        repeat(8) { b.begin("signup", e1, Pkce.challengeOf("p$it".padEnd(43, 'x'))) }
        assertEquals(5, b.size(), "pending rows bounded per (purpose, email)")
    }

    // ---------------------------------------------------------------- SEC-V1-11: OAuth nonce

    @Test
    fun withTheNonceRequiredAnIdTokenWithoutTheMatchingNonceNeverReachesGoTrue() = runTest {
        val raw = "n".repeat(43)
        val r = rig(configuration(nonceRequired = true))

        val missing = assertFailsWith<OposiBotsAuthProblem> { r.gateway.google(OposiBotsGoogleRequest(idToken(nonce = oauthNonceHash(raw))), context) }
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_CREDENTIAL), missing.issues)
        val noClaim = assertFailsWith<OposiBotsAuthProblem> { r.gateway.google(OposiBotsGoogleRequest(idToken(nonce = null), nonce = raw), context) }
        assertEquals(HttpStatusCode.Unauthorized, noClaim.status)
        val otherNonce = assertFailsWith<OposiBotsAuthProblem> { r.gateway.google(OposiBotsGoogleRequest(idToken(nonce = oauthNonceHash("m".repeat(43))), nonce = raw), context) }
        assertEquals(HttpStatusCode.Unauthorized, otherNonce.status)
        val rawInClaim = assertFailsWith<OposiBotsAuthProblem> { r.gateway.google(OposiBotsGoogleRequest(idToken(nonce = raw), nonce = raw), context) }
        assertEquals(HttpStatusCode.Unauthorized, rawInClaim.status, "the claim must be the SHA-256 the client gave the provider")
        val apple = rig(configuration(nonceRequired = true, apple = true))
        val appleStolen = assertFailsWith<OposiBotsAuthProblem> {
            apple.gateway.apple(OposiBotsAppleRequest(idToken(nonce = null), "auth-code", nonce = raw), context)
        }
        assertEquals(HttpStatusCode.Unauthorized, appleStolen.status)
        assertEquals(0, r.tokens() + apple.tokens(), "GoTrue never minted a session")

        val ok = r.gateway.google(OposiBotsGoogleRequest(idToken(nonce = oauthNonceHash(raw)), nonce = raw), context)
        assertEquals("minted-access", ok.session.accessToken)
        val token = r.requests.single { it.url.encodedPath == "/auth/v1/token" }
        assertTrue((token.body as io.ktor.http.content.TextContent).text.contains("\"nonce\":\"$raw\""))
    }

    @Test
    fun theNonceIsValidatedOnTheRoutesWhenRequiredAndStaysOptionalOtherwise() = testApplication {
        val r = rig(configuration(nonceRequired = true))
        application { mount(r) }
        val response = client.post("/v1/auth/oauth/google") { contentType(ContentType.Application.Json); setBody("""{"idToken":"${idToken(null)}"}""") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("credential", issueField(response.bodyAsText()))
        val short = client.post("/v1/auth/oauth/apple") { contentType(ContentType.Application.Json); setBody("""{"identityToken":"${idToken(null)}","authorizationCode":"c","nonce":"n-1"}""") }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals(0, r.requests.size)
        assertFalse(configuration().oauthNonceRequired, "default off: legacy clients keep working until they ship the nonce")
    }

    // ---------------------------------------------------------------- callback / redirect_to allow-list

    @Test
    fun outsideDevelopmentTheRedirectToIsAllowListed() {
        val allowed = listOf("oposibots://auth", "oposibots://auth/reset-password", "OPOSIBOTS://AUTH/callback", "https://app.example.com/auth/callback")
        val refused = listOf(
            "oposibots://evil", "oposibots://auth.evil.com", "oposibots://user@auth", "oposibots://auth:443/x", "oposibot://auth",
            "oposibots://auth?next=https://evil", "oposibots://auth#frag", "http://app.example.com/cb", "javascript:alert(1)",
            "oposibots:auth", "oposibots://auth/../x", "intent://auth#Intent;end", "https://u:p@app.example.com/cb", "not a url",
        )
        with(OposiBotsAuthConfiguration.Companion) {
            allowed.forEach { assertTrue(isAllowedAuthRedirect(it), it) }
            refused.forEach { assertFalse(isAllowedAuthRedirect(it), it) }
            configuration(environment = "production", recoveryRedirect = "oposibots://auth/reset-password", confirmRedirect = "oposibots://auth").requireDeployable()
            val failure = assertFailsWith<IllegalArgumentException> {
                configuration(environment = "production", recoveryRedirect = "oposibots://auth?next=https://evil").requireDeployable()
            }
            assertTrue("OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL" in failure.message.orEmpty())
            assertFailsWith<IllegalArgumentException> { configuration(environment = "production", confirmRedirect = "https://u:p@evil/").requireDeployable() }
            // development keeps any local value (stub stacks)
            configuration(environment = "local", mode = PkceMode.OPTIONAL, recoveryRedirect = "http://localhost:3000/cb").requireDeployable()
        }
    }

    @Test
    fun theRedirectToSentToGoTrueIsTheConfiguredOneAndNeverComesFromTheClient() = testApplication {
        val r = rig(configuration(recoveryRedirect = "oposibots://auth/reset-password"))
        application { mount(r) }
        val response = client.post("/v1/auth/recover") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$VICTIM","codeChallenge":"${Pkce.challengeOf(victimVerifier)}","redirect_to":"https://evil.example","redirectTo":"https://evil.example"}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        val mail = r.requests.single { it.url.encodedPath == "/auth/v1/recover" }
        assertEquals(listOf("oposibots://auth/reset-password"), mail.url.parameters.getAll("redirect_to"))
    }

    // ---------------------------------------------------------------- helpers

    private fun io.ktor.server.application.Application.mount(r: Rig) {
        install(ContentNegotiation) { json(json) }
        installRequestCorrelation()
        installBackendStatusPages()
        val runtime = OposiBotsAuthRuntime(
            configuration = r.configuration,
            gateway = r.gateway,
            rateLimiter = TestAllowAllLimiter,
            json = json,
            canonical = CanonicalTestEnv().runtime().canonical,
            accessRequestMailGuard = NoAccessRequestsMailGuard,
        )
        routing { oposiBotsAuthRoutes(runtime) }
    }

    private fun issueField(body: String) =
        Json.parseToJsonElement(body).jsonObject["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content

    private fun idToken(nonce: String?): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val claims = buildString {
            append("""{"sub":"provider-user","exp":4102444800""")
            if (nonce != null) append(""","nonce":"$nonce"""")
            append("}")
        }
        return encoder.encodeToString("""{"alg":"RS256"}""".toByteArray()) + "." + encoder.encodeToString(claims.toByteArray()) + ".signature"
    }

    private companion object {
        const val UID = "11111111-1111-4111-8111-111111111111"
        const val VICTIM = "victim@example.com"
    }
}
