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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseOposiBotsAuthGatewayTest {
    private val context = OposiBotsAuthContext("request-123", "127.0.0.1")

    @Test
    fun registerUsesSignupNeverAdminCreateAndForwardsSanitizedClientIp() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("""{"user":{"id":"user"}}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(forwardClientIp = true),
            Json { ignoreUnknownKeys = true },
            client,
        )

        gateway.register(
            OposiBotsRegisterRequest("person@example.com", "safe-password"),
            OposiBotsAuthContext("request-123", "203.0.113.10"),
        )

        assertEquals(1, requests.size)
        assertEquals("/auth/v1/signup", requests.single().url.encodedPath)
        assertFalse(requests.single().url.encodedPath.contains("admin/users"))
        assertEquals("server-secret", requests.single().headers["apikey"])
        assertEquals("203.0.113.10", requests.single().headers["Sb-Forwarded-For"])
        assertEquals(null, requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun registerAcceptsTheCanonicalEmailFieldAndTheLegacyIdentifierAlias() = runTest {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            respond(ByteReadChannel("""{"user":{"id":"user"}}"""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val gateway = SupabaseOposiBotsAuthGateway(testAuthConfiguration(), Json { ignoreUnknownKeys = true }, client)

        gateway.register(OposiBotsRegisterRequest(email = "  Canon@Example.com ", password = "safe-password-12"), context)
        gateway.register(OposiBotsRegisterRequest(identifier = "Legacy@Example.com", password = "safe-password-12"), context)
        // Con los dos, gana el canonico.
        gateway.register(OposiBotsRegisterRequest(identifier = "old@example.com", email = "new@example.com", password = "safe-password-12"), context)

        assertTrue(bodies[0].contains("\"email\":\"canon@example.com\""), bodies[0])
        assertTrue(bodies[1].contains("\"email\":\"legacy@example.com\""), bodies[1])
        assertTrue(bodies[2].contains("\"email\":\"new@example.com\""), bodies[2])
    }

    @Test
    fun registerOfAnExistingEmailUnderGoTrueAutoconfirmIsIndistinguishableFromANewSignup() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                ByteReadChannel("""{"code":422,"error_code":"user_already_exists","msg":"User already registered"}"""),
                HttpStatusCode.UnprocessableEntity,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsAuthGateway(testAuthConfiguration(), Json { ignoreUnknownKeys = true }, client)

        gateway.register(OposiBotsRegisterRequest(email = "dup@example.com", password = "safe-password-12"), context)
    }

    @Test
    fun registerStillSurfacesOtherGoTrueFailures() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                ByteReadChannel("""{"code":422,"error_code":"weak_password","msg":"Password is too weak"}"""),
                HttpStatusCode.UnprocessableEntity,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsAuthGateway(testAuthConfiguration(), Json { ignoreUnknownKeys = true }, client)

        assertFailsWith<OposiBotsAuthProblem> {
            gateway.register(OposiBotsRegisterRequest(email = "x@example.com", password = "safe-password-12"), context)
        }
    }

    @Test
    fun loginCommandIsNeverRetriedAfterUpstreamFailure() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond(
                content = ByteReadChannel("""{"message":"unavailable"}"""),
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(),
            Json { ignoreUnknownKeys = true },
            client,
        )

        assertFailsWith<OposiBotsAuthProblem> {
            gateway.login(
                OposiBotsLoginRequest("person@example.com", "safe-password"),
                OposiBotsAuthContext("request-123", "127.0.0.1"),
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun oauthReplayClaimFailureRevokesMintedSession() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(
                    ByteReadChannel(sessionJson()),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                // MASTER-AUTH-011: la puerta de estado corre ANTES del reclamo de la credencial.
                "/rest/v1/perfiles_usuario" -> respond(
                    ByteReadChannel(profileRow("activa")),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(
                    ByteReadChannel("""{"code":"23505"}"""),
                    HttpStatusCode.Conflict,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/auth/v1/logout" -> respond(
                    ByteReadChannel(""),
                    HttpStatusCode.NoContent,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val gateway = SupabaseOposiBotsAuthGateway(
            testAuthConfiguration(),
            Json { ignoreUnknownKeys = true },
            client,
        )

        assertFailsWith<OposiBotsAuthProblem> {
            gateway.google(
                OposiBotsGoogleRequest(testJwt()),
                OposiBotsAuthContext("request-123", "127.0.0.1"),
            )
        }

        assertEquals(
            listOf("/auth/v1/token", "/rest/v1/perfiles_usuario", "/rest/v1/rpc/oposibots_claim_oauth_credential", "/auth/v1/logout"),
            requests.map { it.url.encodedPath },
        )
        assertEquals("Bearer minted-access", requests.last().headers[HttpHeaders.Authorization])
        // MASTER-AUTH-005 (cambio deliberado de la asercion previa `scope=global`): un replay revoca SOLO la sesion
        // acuñada; en global cerraba todas las sesiones del usuario real.
        assertEquals("scope=local", requests.last().url.encodedQuery)
    }

    // ---------------- SLICE 1: puerta de estado de cuenta ----------------

    /** ACTIVE allowed; the session carries the superset fields and the profile flags come from perfiles_usuario. */
    @Test
    fun anActiveAccountGetsTheSupersetSessionWithFlagsFromPerfilesUsuario() = runTest {
        val h = harness(profile = { profileRow("activa", onboarding = true) })
        val session = h.gateway.login(OposiBotsLoginRequest(email = "person@example.com", password = "safe-password-12"), context)

        assertEquals("minted-access", session.accessToken)
        assertEquals(session.expiresAtEpochSeconds, session.expiresAt)
        assertTrue(session.user.profileComplete)
        assertTrue(session.user.onboardingCompleted)
        assertEquals("activa", session.user.accountStatus)
        assertEquals(UID, session.user.id)
        assertEquals(0, h.logouts().size, "una cuenta activa no se revoca")
    }

    /** The gate reads perfiles_usuario with the SERVICE key (never the user JWT) and never touches public.usuario. */
    @Test
    fun theGateReadsPerfilesUsuarioWithTheServiceKeyAndNeverPublicUsuario() = runTest {
        val h = harness(profile = { profileRow("activa", onboarding = false) })
        val session = h.gateway.login(OposiBotsLoginRequest(email = "person@example.com", password = "safe-password-12"), context)

        val read = h.requests.single { it.url.encodedPath == "/rest/v1/perfiles_usuario" }
        assertEquals(HttpMethod.Get, read.method)
        assertEquals("server-secret", read.headers["apikey"])
        assertNull(read.headers[HttpHeaders.Authorization], "el JWT del usuario no viaja a la lectura de perfil")
        assertEquals("eq.$UID", read.url.parameters["usuario_id"])
        assertTrue(h.requests.none { it.url.encodedPath == "/rest/v1/usuario" || it.url.encodedPath.contains("oposibots_upsert_own_profile") })
        assertFalse(session.user.profileComplete)
        assertFalse(session.user.onboardingCompleted)
    }

    @Test
    fun everyNonActiveOrMissingProfileIsDeniedOnEveryTokenMintingOperationAndTheMintedSessionIsRevoked() = runTest {
        val denied = mapOf<String, Pair<String, String>>(
            "bloqueada" to ("ACCOUNT_BLOCKED" to profileRow("bloqueada")),
            "eliminacion_pendiente" to ("ACCOUNT_DELETION_PENDING" to profileRow("eliminacion_pendiente")),
            "DISABLED" to ("FORBIDDEN" to profileRow("DISABLED")),
            "DELETED" to ("FORBIDDEN" to profileRow("DELETED")),
            "ANONYMIZED" to ("FORBIDDEN" to profileRow("ANONYMIZED")),
            "UNKNOWN" to ("FORBIDDEN" to profileRow("otro-estado")),
            "NULL" to ("FORBIDDEN" to """[{"usuario_id":"$UID","estado_cuenta":null,"onboarding_completado":true}]"""),
            "columna ausente" to ("FORBIDDEN" to """[{"usuario_id":"$UID","onboarding_completado":true}]"""),
            "sin perfil" to ("PROFILE_MISSING" to "[]"),
        )
        val operations: Map<String, suspend (SupabaseOposiBotsAuthGateway) -> Unit> = mapOf(
            "login" to { g -> g.login(OposiBotsLoginRequest(email = "person@example.com", password = "safe-password-12"), context); Unit },
            "refresh" to { g -> g.refresh(OposiBotsRefreshRequest("refresh-token"), context); Unit },
            "email/exchange" to { g -> g.exchangeEmail(OposiBotsEmailExchangeRequest("hash"), context); Unit },
            "google" to { g -> g.google(OposiBotsGoogleRequest(testJwt("google-1")), context); Unit },
        )
        operations.forEach { (operation, call) ->
            denied.forEach { (label, expected) ->
                val h = harness(profile = { expected.second })
                val failure = assertFailsWith<BackendDomainException>("$operation/$label") { call(h.gateway) }
                assertEquals(expected.first, failure.errorCode, "$operation/$label")
                assertEquals(HttpStatusCode.Forbidden, failure.status, "$operation/$label")
                val logouts = h.logouts()
                assertEquals(1, logouts.size, "$operation/$label: la sesion acuñada se revoca")
                assertEquals("Bearer minted-access", logouts.single().headers[HttpHeaders.Authorization], "$operation/$label")
                assertEquals("scope=global", logouts.single().url.encodedQuery)
            }
        }
    }

    @Test
    fun appleDeniedAccountsAreRevokedAndNoFirstAuthNameIsPersistedForThem() = runTest {
        mapOf("bloqueada" to "ACCOUNT_BLOCKED", "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING", "DELETED" to "FORBIDDEN").forEach { (estado, code) ->
            val h = harness(profile = { profileRow(estado) }, appleConfigured = true, appleIdToken = testJwt("apple-1"))
            val failure = assertFailsWith<BackendDomainException>(estado) {
                h.gateway.apple(OposiBotsAppleRequest(testJwt("apple-1"), "code", firstAuthGivenName = "Ada"), context)
            }
            assertEquals(code, failure.errorCode, estado)
            assertEquals(1, h.logouts().size, "$estado: sesion acuñada revocada")
            assertTrue(h.requests.none { it.method == HttpMethod.Patch }, "$estado: no se escribe nada en un perfil denegado")
        }
    }

    @Test
    fun appleWithoutServerCredentialsFailsBeforeMintingAnything() = runTest {
        val h = harness()
        assertFailsWith<OposiBotsAuthProblem> { h.gateway.apple(OposiBotsAppleRequest(testJwt("apple-1"), "code"), context) }
        assertTrue(h.requests.none { it.url.encodedPath == "/auth/v1/token" })
    }

    /** A profile-read outage fails CLOSED: the minted session is revoked and the answer is 503. */
    @Test
    fun anUnreadableProfileFailsClosedRevokesTheSessionAndAnswers503() = runTest {
        val h = harness(profileStatus = HttpStatusCode.InternalServerError, profile = { """{"message":"boom"}""" })
        val failure = assertFailsWith<OposiBotsAuthProblem> {
            h.gateway.login(OposiBotsLoginRequest(email = "person@example.com", password = "safe-password-12"), context)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
        assertEquals(1, h.logouts().size)
    }

    // ---------------- SLICE 1: mapeo de errores de GoTrue ----------------

    @Test
    fun goTrueInvalidCredentialsIsInvalidCredentials401AndEmailNotConfirmedIsEmailUnverified403() = runTest {
        val invalid = harness(tokenStatus = HttpStatusCode.BadRequest, tokenBody = """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        val a = assertFailsWith<BackendDomainException> {
            invalid.gateway.login(OposiBotsLoginRequest(email = "a@b.es", password = "safe-password-12"), context)
        }
        assertEquals("INVALID_CREDENTIALS", a.errorCode)
        assertEquals(HttpStatusCode.Unauthorized, a.status)

        val unconfirmed = harness(tokenStatus = HttpStatusCode.BadRequest, tokenBody = """{"code":400,"error_code":"email_not_confirmed","msg":"Email not confirmed"}""")
        val b = assertFailsWith<BackendDomainException> {
            unconfirmed.gateway.login(OposiBotsLoginRequest(email = "a@b.es", password = "safe-password-12"), context)
        }
        assertEquals("EMAIL_UNVERIFIED", b.errorCode)
        assertEquals(HttpStatusCode.Forbidden, b.status)

        // Cualquier otro rechazo de credenciales sigue siendo el 401 generico de siempre.
        val other = harness(tokenStatus = HttpStatusCode.BadRequest, tokenBody = """{"code":400,"error_code":"validation_failed","msg":"x"}""")
        val c = assertFailsWith<OposiBotsAuthProblem> {
            other.gateway.login(OposiBotsLoginRequest(email = "a@b.es", password = "safe-password-12"), context)
        }
        assertEquals(HttpStatusCode.Unauthorized, c.status)
    }

    @Test
    fun aFailedEmailExchangeIsAuthCodeInvalid401AndARefreshFailureStaysGeneric401() = runTest {
        val failing = harness(tokenStatus = HttpStatusCode.BadRequest, verifyStatus = HttpStatusCode.Forbidden, verifyBody = """{"code":403,"error_code":"otp_expired","msg":"expired"}""")
        val exchange = assertFailsWith<BackendDomainException> { failing.gateway.exchangeEmail(OposiBotsEmailExchangeRequest("hash"), context) }
        assertEquals("AUTH_CODE_INVALID", exchange.errorCode)
        assertEquals(HttpStatusCode.Unauthorized, exchange.status)

        val refresh = assertFailsWith<OposiBotsAuthProblem> { failing.gateway.refresh(OposiBotsRefreshRequest("revoked"), context) }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
    }

    // ---------------- SLICE 1: Google / Apple ----------------

    @Test
    fun googleForwardsTheNonceToGoTrueAndReportsIsNewAccountFromOnboarding() = runTest {
        val h = harness(profile = { profileRow("activa", onboarding = false) })
        val response = h.gateway.google(OposiBotsGoogleRequest(testJwt("google-2"), nonce = "nonce-123"), context)

        val token = h.requests.first { it.url.encodedPath == "/auth/v1/token" }
        assertTrue(bodyOf(token).contains("\"nonce\":\"nonce-123\""), bodyOf(token))
        assertTrue(response.isNewAccount)
        assertEquals("activa", response.session.user.accountStatus)
    }

    @Test
    fun googleWithoutNonceSendsNoNonceAndAnOnboardedUserIsNotANewAccount() = runTest {
        val h = harness(profile = { profileRow("activa", onboarding = true) })
        val response = h.gateway.google(OposiBotsGoogleRequest(testJwt("google-3")), context)
        assertFalse(bodyOf(h.requests.first { it.url.encodedPath == "/auth/v1/token" }).contains("nonce"))
        assertFalse(response.isNewAccount)
    }

    @Test
    fun firstAuthNamesFromAppleAreWrittenOnlyWhenEmptyAndOnlyAfterTheGate() = runTest {
        val h = harness(profile = { profileRow("activa", onboarding = false) }, appleConfigured = true, appleIdToken = testJwt("apple-9"))
        val response = h.gateway.apple(
            OposiBotsAppleRequest(testJwt("apple-9"), "auth-code", nonce = "n-1", firstAuthGivenName = "Ada", firstAuthFamilyName = "Lovelace"),
            context,
        )
        assertTrue(response.isNewAccount)
        val patches = h.requests.filter { it.method == HttpMethod.Patch }
        assertEquals(2, patches.size)
        patches.forEach {
            assertEquals("/rest/v1/perfiles_usuario", it.url.encodedPath)
            assertEquals("eq.$UID", it.url.parameters["usuario_id"])
            assertEquals("server-secret", it.headers["apikey"])
        }
        assertEquals("(nombre.is.null,nombre.eq.)", patches[0].url.parameters["or"], "solo si esta vacio")
        assertEquals("(apellidos.is.null,apellidos.eq.)", patches[1].url.parameters["or"])
        assertTrue(bodyOf(patches[0]).contains("\"nombre\":\"Ada\""))
        assertTrue(bodyOf(patches[1]).contains("\"apellidos\":\"Lovelace\""))
        // La puerta de estado va ANTES de escribir nada: lectura de perfil < primer PATCH.
        val order = h.requests.map { it.method.value + " " + it.url.encodedPath }
        assertTrue(order.indexOf("GET /rest/v1/perfiles_usuario") < order.indexOf("PATCH /rest/v1/perfiles_usuario"), order.toString())
        assertTrue(bodyOf(h.requests.first { it.url.encodedPath == "/auth/v1/token" }).contains("\"nonce\":\"n-1\""))
    }

    @Test
    fun theLegacyAppleFullNameAliasIsSplitAndTheLegacyEmailIsIgnored() {
        val request = OposiBotsAppleRequest("t", "c", email = "x@y.es", fullName = "Ada King Lovelace")
        assertEquals("Ada" to "King Lovelace", request.firstAuthNames())
        assertEquals("Grace" to "Hopper", OposiBotsAppleRequest("t", "c", firstAuthGivenName = "Grace", firstAuthFamilyName = "Hopper", fullName = "Ignored Name").firstAuthNames())
        assertEquals(null to null, OposiBotsAppleRequest("t", "c").firstAuthNames())
    }

    @Test
    fun aFailedFirstAuthNameWriteNeverDeniesAccess() = runTest {
        val h = harness(profile = { profileRow("activa", onboarding = false) }, patchStatus = HttpStatusCode.InternalServerError, appleConfigured = true, appleIdToken = testJwt("apple-10"))
        val response = h.gateway.apple(OposiBotsAppleRequest(testJwt("apple-10"), "code", firstAuthGivenName = "Ada"), context)
        assertEquals("minted-access", response.session.accessToken)
    }

    // ---------------- SLICE 1: AuthProviderClient (perfil canonico y borrado de cuenta) ----------------

    @Test
    fun userReadsEmailProvidersAndCreatedAtFromGoTrueWithTheCallerBearer() = runTest {
        val h = harness(userBody = """{"id":"$UID","email":"a@b.es","created_at":"2026-01-02T03:04:05.123456Z","app_metadata":{"providers":["email","google"]},"identities":[{"provider":"google"},{"provider":"github"}]}""")
        val user = h.gateway.user("caller-token-".repeat(4), context)

        assertEquals("a@b.es", user.email)
        assertEquals(listOf("email", "google", "github"), user.providers)
        assertEquals(listOf("email", "google"), user.canonicalProviders(), "un proveedor desconocido se descarta")
        assertTrue(user.hasPassword)
        val request = h.requests.single { it.url.encodedPath == "/auth/v1/user" }
        assertEquals("Bearer ${"caller-token-".repeat(4)}", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun userMapsGoTrueFailuresToCanonicalCodes() = runTest {
        val revoked = harness(userStatus = HttpStatusCode.Unauthorized, userBody = """{"code":401,"error_code":"session_not_found","msg":"x"}""")
        assertEquals("UNAUTHENTICATED", assertFailsWith<BackendDomainException> { revoked.gateway.user("t", context) }.errorCode)
        val down = harness(userStatus = HttpStatusCode.BadGateway, userBody = "{}")
        assertEquals("UPSTREAM_UNAVAILABLE", assertFailsWith<BackendDomainException> { down.gateway.user("t", context) }.errorCode)
    }

    @Test
    fun verifyPasswordSucceedsAndRevokesOnlyTheVerificationSessionLocally() = runTest {
        val h = harness()
        h.gateway.verifyPassword("Person@Example.com", "the-password-123", context)
        val grant = h.requests.first { it.url.encodedPath == "/auth/v1/token" }
        assertEquals("password", grant.url.parameters["grant_type"])
        assertTrue(bodyOf(grant).contains("\"email\":\"person@example.com\""))
        val logout = h.logouts().single()
        assertEquals("scope=local", logout.url.encodedQuery, "el resto de sesiones del usuario no se tocan")
        assertEquals("Bearer minted-access", logout.headers[HttpHeaders.Authorization])
    }

    @Test
    fun verifyPasswordMapsWrongPasswordAndOutagesAndNeverGatesTheAccount() = runTest {
        val wrong = harness(tokenStatus = HttpStatusCode.BadRequest, tokenBody = """{"code":400,"error_code":"invalid_credentials","msg":"x"}""")
        assertEquals("REAUTH_FAILED", assertFailsWith<BackendDomainException> { wrong.gateway.verifyPassword("a@b.es", "bad", context) }.errorCode)
        assertTrue(wrong.requests.none { it.url.encodedPath == "/rest/v1/perfiles_usuario" })

        val down = harness(tokenStatus = HttpStatusCode.ServiceUnavailable, tokenBody = "{}")
        assertEquals("UPSTREAM_UNAVAILABLE", assertFailsWith<BackendDomainException> { down.gateway.verifyPassword("a@b.es", "x", context) }.errorCode)

        val limited = harness(tokenStatus = HttpStatusCode.TooManyRequests, tokenBody = "{}")
        val failure = assertFailsWith<BackendDomainException> { limited.gateway.verifyPassword("a@b.es", "x", context) }
        assertEquals("RATE_LIMITED", failure.errorCode)
        assertEquals(60, failure.retryAfterSeconds)
    }

    // ---------------- arnes ----------------

    private class Harness(val gateway: SupabaseOposiBotsAuthGateway, val requests: MutableList<HttpRequestData>) {
        fun logouts() = requests.filter { it.url.encodedPath == "/auth/v1/logout" }
    }

    private fun harness(
        profile: () -> String = { profileRow("activa") },
        profileStatus: HttpStatusCode = HttpStatusCode.OK,
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        tokenBody: String = sessionJson(),
        verifyStatus: HttpStatusCode = HttpStatusCode.OK,
        verifyBody: String = sessionJson(),
        userStatus: HttpStatusCode = HttpStatusCode.OK,
        userBody: String = """{"id":"$UID","email":"person@example.com"}""",
        patchStatus: HttpStatusCode = HttpStatusCode.NoContent,
        appleConfigured: Boolean = false,
        appleIdToken: String = "",
    ): Harness {
        val requests = mutableListOf<HttpRequestData>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                request.url.host == "appleid.apple.com" -> respond(ByteReadChannel("""{"id_token":"$appleIdToken"}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/token" -> respond(ByteReadChannel(tokenBody), tokenStatus, json)
                path == "/auth/v1/verify" -> respond(ByteReadChannel(verifyBody), verifyStatus, json)
                path == "/auth/v1/user" -> respond(ByteReadChannel(userBody), userStatus, json)
                path == "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                path == "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, json)
                path == "/rest/v1/perfiles_usuario" && request.method == HttpMethod.Get -> respond(ByteReadChannel(profile()), profileStatus, json)
                path == "/rest/v1/perfiles_usuario" && request.method == HttpMethod.Patch -> respond(ByteReadChannel(""), patchStatus, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val configuration = testAuthConfiguration().let {
            if (appleConfigured) it.copy(appleClientId = "apple-client", appleClientSecret = "apple-secret") else it
        }
        return Harness(SupabaseOposiBotsAuthGateway(configuration, Json { ignoreUnknownKeys = true }, client), requests)
    }

    private fun bodyOf(request: HttpRequestData): String = (request.body as io.ktor.http.content.TextContent).text
}

private const val UID = "11111111-1111-4111-8111-111111111111"

private fun sessionJson(id: String = UID) =
    """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$id","email":"person@example.com"}}"""

private fun profileRow(estado: String, onboarding: Boolean = true) =
    """[{"usuario_id":"$UID","estado_cuenta":"$estado","onboarding_completado":$onboarding}]"""

private fun testAuthConfiguration(forwardClientIp: Boolean = false) = OposiBotsAuthConfiguration(
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
    forwardClientIpToSupabase = forwardClientIp,
    rateLimitMode = "memory",
    rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
    valkeyUrl = null,
)

private fun testJwt(subject: String = "oauth-user"): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = encoder.encodeToString("""{"sub":"$subject","exp":4102444800}""".toByteArray())
    return "$header.$payload.signature"
}
