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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // MASTER-API-001: el cuerpo CRUDO lleva verificationRequired (antes se decodificaba con el Json que rellena el
        // valor por defecto y el `{}` real de produccion pasaba desapercibido).
        assertEquals(true, Json.parseToJsonElement(response.bodyAsText()).jsonObject["verificationRequired"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertEquals("person@example.com", fake.registerRequest?.identifier)
        assertFalse(response.bodyAsText().contains("accessToken"))
    }

    // --- FA39: binding of the email-link code (PKCE-style) ---

    @Test
    fun theCodeChallengeAndVerifierAreValidatedByShapeBeforeAnyWork() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }

        suspend fun post(path: String, body: String) = client.post(path) { contentType(ContentType.Application.Json); setBody(body) }
        fun issueField(body: String) = Json.parseToJsonElement(body).jsonObject["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content

        val badChallenge = post("/v1/auth/recover", """{"email":"a@b.es","codeChallenge":"too-short"}""")
        assertEquals(HttpStatusCode.BadRequest, badChallenge.status)
        assertEquals("codeChallenge", issueField(badChallenge.bodyAsText()))
        val badVerifier = post("/v1/auth/email/exchange", """{"code":"hash","codeVerifier":"short"}""")
        assertEquals(HttpStatusCode.BadRequest, badVerifier.status)
        assertEquals("codeVerifier", issueField(badVerifier.bodyAsText()))
        val badEmail = post("/v1/auth/reset-password", """{"resetToken":"t","newPassword":"safe-password-12","codeVerifier":"vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv","email":"not-an-email"}""")
        assertEquals(HttpStatusCode.BadRequest, badEmail.status)

        val ok = post("/v1/auth/recover", """{"email":"a@b.es","codeChallenge":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}""")
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", fake.recoverRequest?.codeChallenge)
    }

    @Test
    fun theLegacyBodiesWithoutTheNewFieldsStillWork() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }

        val response = client.post("/v1/auth/email/exchange") { contentType(ContentType.Application.Json); setBody("""{"code":"hash"}""") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(null, fake.exchangeRequest?.codeVerifier)
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

    // --- SLICE 0: sobre canonico en /v1/auth, con los campos legacy de OB ---

    @Test
    fun authValidationErrorUsesCanonicalEnvelopeAndKeepsLegacyFields() = testApplication {
        application { authTestModule(FakeGateway()) }

        val response = client.post("/v1/auth/login") {
            header("X-Request-Id", "auth-envelope-0001")
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"","password":"safe-password"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("VALIDATION", body["code"]!!.jsonPrimitive.content)
        assertEquals("auth-envelope-0001", body["requestId"]!!.jsonPrimitive.content)
        assertEquals("auth-envelope-0001", response.headers["X-Request-Id"])
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
        // Legacy OB (AuthErrorWire): `error` = nombre del enum + `issues`.
        assertEquals("Validation", body["error"]!!.jsonPrimitive.content)
        assertEquals(listOf("BLANK_IDENTIFIER"), body["issues"]!!.jsonArray.map { it.jsonPrimitive.content })
        val issue = body["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject
        assertEquals("identifier", issue["field"]!!.jsonPrimitive.content)
        assertEquals("BLANK_IDENTIFIER", issue["issue"]!!.jsonPrimitive.content)
    }

    @Test
    fun authMissingBearerIs401UnauthenticatedWithWwwAuthenticate() = testApplication {
        application { authTestModule(FakeGateway()) }

        val response = client.get("/v1/auth/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UNAUTHENTICATED", body["code"]!!.jsonPrimitive.content)
        assertEquals("AuthenticationRequired", body["error"]!!.jsonPrimitive.content)
        assertEquals(response.headers["X-Request-Id"], body["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun authRateLimitIsRateLimitedCodeWithRetryAfter() = testApplication {
        application { authTestModule(FakeGateway(), rateLimiter = RejectingRateLimiter) }

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"a@b.es","password":"safe-password"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("RATE_LIMITED", body["code"]!!.jsonPrimitive.content)
        assertEquals("TemporarilyUnavailable", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun authProblemCanonicalCodesFollowTheFrozenMapping() {
        assertEquals(CanonicalErrorCode.VALIDATION, OposiBotsAuthProblem.validation().canonicalCode())
        assertEquals(CanonicalErrorCode.UNAUTHENTICATED, OposiBotsAuthProblem.authentication().canonicalCode())
        assertEquals(CanonicalErrorCode.FORBIDDEN, OposiBotsAuthProblem.accessDenied().canonicalCode())
        assertEquals(CanonicalErrorCode.CONFLICT, OposiBotsAuthProblem.conflict().canonicalCode())
        assertEquals(CanonicalErrorCode.UPSTREAM_UNAVAILABLE, OposiBotsAuthProblem.unavailable().canonicalCode())
        assertEquals(CanonicalErrorCode.RATE_LIMITED, OposiBotsAuthProblem.unavailable(5).canonicalCode())
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
        assertTrue(isLocalDevelopmentPeer("dev", "::1"))
        assertFalse(isLocalDevelopmentPeer("dev", "8.8.8.8"))
        assertFalse(isLocalDevelopmentPeer("production", "127.0.0.1"))

        // Cambio deliberado: antes se aceptaba toda la RFC1918, así que cualquier
        // equipo de la red llegaba a /admin/roles o /admin/backend/restart con un
        // token que además viaja compilado en la app Android. "Desarrollo local"
        // es esta máquina, no la red entera.
        assertFalse(isLocalDevelopmentPeer("test", "192.168.1.20"))
        assertFalse(isLocalDevelopmentPeer("dev", "10.0.0.5"))
        assertFalse(isLocalDevelopmentPeer("dev", "fd00::1"))

        // Y nunca un nombre de host: `remoteHost` lo deriva por DNS inverso, que
        // controla quien conecta. Un PTR propio hacia un dominio con A 127.0.0.1
        // hacía pasar por loopback a una petición remota.
        assertFalse(isLocalDevelopmentPeer("dev", "localhost"))
        assertFalse(isLocalDevelopmentPeer("dev", "attacker-controlled.example.com"))
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
                    canonical = CanonicalTestEnv().runtime().canonical,
                    accessRequestMailGuard = NoAccessRequestsMailGuard,
                ),
            )
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    // ---------------- SLICE 1: superconjunto canonico + legacy en /v1/auth ----------------

    private fun bodyOf(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun registerAcceptsTheCanonicalEmailAndTheLegacyIdentifierAndEitherAbsentIs400() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }
        suspend fun register(body: String) = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val canonical = register("""{"email":"canon@example.com","password":"safe-password-12"}""")
        assertEquals(HttpStatusCode.Accepted, canonical.status)
        assertEquals("canon@example.com", fake.registerRequest?.resolvedIdentifier())

        val legacy = register("""{"identifier":"legacy@example.com","password":"safe-password-12"}""")
        assertEquals(HttpStatusCode.Accepted, legacy.status)
        assertEquals("legacy@example.com", fake.registerRequest?.resolvedIdentifier())

        // Enumeracion segura: un email repetido tambien es 202 (el gateway responde igual).
        assertEquals(HttpStatusCode.Accepted, register("""{"email":"canon@example.com","password":"safe-password-12"}""").status)

        val neither = register("""{"password":"safe-password-12"}""")
        assertEquals(HttpStatusCode.BadRequest, neither.status)
        val body = bodyOf(neither.bodyAsText())
        assertEquals("VALIDATION", body["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("BLANK_IDENTIFIER"), body["issues"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** E2-V1-S02: un caracter de control en el correo (BEL, NUL, DEL) es un 400 en register, login, resend y recover; antes llegaba a la guarda (503) o enviaba correo. */
    @Test
    fun controlCharactersInTheEmailAreAnHttp400OnEveryEmailEntryPoint() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }
        val bad = listOf("a\\u0007b@example.com", "\\u0000@example.com", "ab\\u007f@example.com", "a@exam\\u001bple.com")
        for (email in bad) {
            for ((path, body) in listOf(
                "/v1/auth/register" to """{"email":"$email","password":"safe-password-12"}""",
                "/v1/auth/login" to """{"email":"$email","password":"safe-password-12"}""",
                "/v1/auth/email/resend" to """{"email":"$email"}""",
                "/v1/auth/recover" to """{"email":"$email"}""",
            )) {
                val response = client.post(path) { contentType(ContentType.Application.Json); setBody(body) }
                assertEquals(HttpStatusCode.BadRequest, response.status, "$path $email -> ${response.bodyAsText()}")
            }
        }
        assertEquals(null, fake.registerRequest, "ninguna peticion de registro llego al gateway")
        // un correo normal sigue valiendo
        val ok = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody("""{"email":"ok@example.com","password":"safe-password-12"}""") }
        assertEquals(HttpStatusCode.Accepted, ok.status)
    }

    @Test
    fun loginAndRecoverAcceptBothWiresAndTolerateLegacyExtras() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }

        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"canon@example.com","password":"safe-password-12","keepSignedIn":false,"algoNuevo":1}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals("canon@example.com", fake.loginRequest?.resolvedIdentifier())

        val legacy = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"legacy@example.com","password":"safe-password-12","keepSignedIn":true}""")
        }
        assertEquals(HttpStatusCode.OK, legacy.status)
        assertEquals("legacy@example.com", fake.loginRequest?.resolvedIdentifier())

        val recoverCanonical = client.post("/v1/auth/recover") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"canon@example.com"}""")
        }
        assertEquals(HttpStatusCode.NoContent, recoverCanonical.status)
        assertEquals("canon@example.com", fake.recoverRequest?.resolvedIdentifier())
        val recoverLegacy = client.post("/v1/auth/recover") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"legacy@example.com"}""")
        }
        assertEquals(HttpStatusCode.NoContent, recoverLegacy.status)
        assertEquals("legacy@example.com", fake.recoverRequest?.resolvedIdentifier())
    }

    @Test
    fun theSessionIsTheSupersetOfTheCanonicalAndTheLegacyWire() = testApplication {
        application { authTestModule(FakeGateway()) }
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.es","password":"safe-password-12"}""")
        }
        val session = bodyOf(response.bodyAsText())
        assertEquals("access", session["accessToken"]!!.jsonPrimitive.content)
        assertEquals("2000000000", session["expiresAt"]!!.jsonPrimitive.content, "canonico")
        assertEquals("2000000000", session["expiresAtEpochSeconds"]!!.jsonPrimitive.content, "legacy")
        val user = session["user"]!!.jsonObject
        assertEquals("user-id", user["id"]!!.jsonPrimitive.content)
        assertEquals("false", user["onboardingCompleted"]!!.jsonPrimitive.content, "canonico")
        assertEquals("false", user["profileComplete"]!!.jsonPrimitive.content, "legacy")
        assertEquals("activa", user["accountStatus"]!!.jsonPrimitive.content)
    }

    /** Cada denegacion de la puerta de estado / GoTrue sale con SU codigo canonico y el `error` legacy de OB, y con X-Request-Id. */
    @Test
    fun accountStateAndCredentialFailuresAreCanonicalCodesWithTheLegacyErrorNameOnEveryTokenMintingRoute() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }
        val cases = listOf(
            CanonicalErrorCode.ACCOUNT_BLOCKED to ("AccessDenied" to 403),
            CanonicalErrorCode.ACCOUNT_DELETION_PENDING to ("AccessDenied" to 403),
            CanonicalErrorCode.FORBIDDEN to ("AccessDenied" to 403),
            CanonicalErrorCode.PROFILE_MISSING to ("AccessDenied" to 403),
            CanonicalErrorCode.EMAIL_UNVERIFIED to ("AccessDenied" to 403),
            CanonicalErrorCode.INVALID_CREDENTIALS to ("AuthenticationRequired" to 401),
            CanonicalErrorCode.AUTH_CODE_INVALID to ("AuthenticationRequired" to 401),
        )
        val routes = mapOf(
            "/v1/auth/login" to """{"email":"a@b.es","password":"safe-password-12"}""",
            "/v1/auth/refresh" to """{"refreshToken":"refresh-token"}""",
            "/v1/auth/email/exchange" to """{"code":"hash"}""",
            "/v1/auth/oauth/google" to """{"idToken":"id-token"}""",
            "/v1/auth/oauth/apple" to """{"identityToken":"id-token","authorizationCode":"code"}""",
        )
        cases.forEach { (code, expected) ->
            fake.failure = code.exception()
            routes.forEach { (path, body) ->
                val response = client.post(path) {
                    header("X-Request-Id", "req-slice1-auth-1")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(expected.second, response.status.value, "$path $code")
                val json = bodyOf(response.bodyAsText())
                assertEquals(code.name, json["code"]!!.jsonPrimitive.content, "$path $code")
                assertEquals(expected.first, json["error"]!!.jsonPrimitive.content, "$path $code")
                assertEquals("req-slice1-auth-1", json["requestId"]!!.jsonPrimitive.content)
                assertEquals("req-slice1-auth-1", response.headers["X-Request-Id"])
                if (expected.second == 401) assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            }
        }
    }

    @Test
    fun invalidAndRevokedCredentialsAreGeneric401AndAnUpstreamOutageIs503() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }
        fake.failure = OposiBotsAuthProblem.authentication()
        val refresh = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"revoked"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
        assertEquals("UNAUTHENTICATED", bodyOf(refresh.bodyAsText())["code"]!!.jsonPrimitive.content)

        fake.failure = OposiBotsAuthProblem.unavailable()
        val down = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.es","password":"safe-password-12"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertEquals("UPSTREAM_UNAVAILABLE", bodyOf(down.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun googleAndAppleForwardTheNonceAndTheFirstAuthFieldsAndTolerateTheLegacyAppleFields() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }
        val google = client.post("/v1/auth/oauth/google") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"id-token","nonce":"n-1"}""")
        }
        assertEquals(HttpStatusCode.OK, google.status)
        assertEquals("n-1", fake.googleRequest?.nonce)
        assertEquals(false, bodyOf(google.bodyAsText())["isNewAccount"]!!.jsonPrimitive.content.toBoolean())

        val apple = client.post("/v1/auth/oauth/apple") {
            contentType(ContentType.Application.Json)
            setBody("""{"identityToken":"id-token","authorizationCode":"code","nonce":"n-2","firstAuthGivenName":"Ada","firstAuthFamilyName":"Lovelace","firstAuthEmail":"ada@example.com","email":"legacy@example.com","fullName":"Ignorado Legacy"}""")
        }
        assertEquals(HttpStatusCode.OK, apple.status)
        assertEquals("n-2", fake.appleRequest?.nonce)
        assertEquals("Ada" to "Lovelace", fake.appleRequest?.firstAuthNames(), "los canonicos ganan al fullName legacy")
    }

    @Test
    fun resendIsAlways204AndIsRateLimitedWith429AndRetryAfter() = testApplication {
        val fake = FakeGateway()
        application { authTestModule(fake) }
        val ok = client.post("/v1/auth/email/resend") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.es"}""")
        }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertEquals(1, fake.resendCalls)

    }

    @Test
    fun resendIsRateLimitedWith429AndRetryAfter() = testApplication {
        application { authTestModule(FakeGateway(), rateLimiter = RejectingRateLimiter) }
        val response = client.post("/v1/auth/email/resend") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.es"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun resetPasswordReplayIs401AndLogoutNeverRunsTheAccountGate() = testApplication {
        val fake = FakeGateway()
        // El perfil canonico esta BLOQUEADO: logout tiene que seguir funcionando (no consulta la puerta de estado).
        val canonical = CanonicalTestEnv().apply { status = "bloqueada" }
        application { authTestModule(fake, canonical = canonical) }

        fake.failure = OposiBotsAuthProblem.authentication()
        val replay = client.post("/v1/auth/reset-password") {
            contentType(ContentType.Application.Json)
            setBody("""{"resetToken":"used-token","newPassword":"safe-password-12"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)

        val logout = client.post("/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN") }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(TEST_LONG_TOKEN, fake.logoutToken)
        assertTrue(canonical.upstream.log.isEmpty(), "logout no lee el perfil")

        canonical.status = "eliminacion_pendiente"
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN") }.status)
    }

    // ---------------- SLICE 1: adaptadores legacy /v1/auth/me y /v1/auth/register/profile ----------------

    private fun counterOf(endpoint: String): Double =
        BackendPrometheusMetrics.registry.find(LEGACY_ROUTE_REQUESTS_METRIC).tag("endpoint", endpoint).counter()?.count() ?: 0.0

    @Test
    fun legacyMeReadsPerfilesUsuarioReturnsTheSupersetAndIsMarkedDeprecatedWithTheCanonicalSuccessor() = testApplication {
        val canonical = CanonicalTestEnv().apply { upstream.onGet = { profileRowJson(onboarding = true) } }
        val limiter = TestCountingLimiter()
        application { authTestModule(FakeGateway(), rateLimiter = limiter, canonical = canonical) }
        val before = counterOf("EP-124")

        val response = client.get("/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN") }

        assertEquals(HttpStatusCode.OK, response.status)
        val user = bodyOf(response.bodyAsText())["user"]!!.jsonObject
        assertEquals(TEST_UID, user["id"]!!.jsonPrimitive.content)
        assertEquals("true", user["profileComplete"]!!.jsonPrimitive.content)
        assertEquals("true", user["onboardingCompleted"]!!.jsonPrimitive.content)
        assertEquals("activa", user["accountStatus"]!!.jsonPrimitive.content)
        assertEquals("@${LegacyEndpoints.SLICE_1_DEPRECATED_AT.epochSecond}", response.headers[LegacyHeaders.DEPRECATION])
        assertEquals("</api/v1/app/me>; rel=\"successor-version\"", response.headers[HttpHeaders.Link])
        assertEquals(before + 1, counterOf("EP-124"))
        assertTrue(canonical.upstream.log.single().contains("perfiles_usuario"))
        assertTrue(limiter.subjectChecks.any { it.first == OposiBotsAuthOperation.ME && it.second == TEST_LONG_TOKEN }, "misma clave de limite que antes")
    }

    @Test
    fun legacyMeDeniesEveryNonActiveAccountWithTheCanonicalCodeAndTheLegacyErrorName() = testApplication {
        val canonical = CanonicalTestEnv()
        application { authTestModule(FakeGateway(), canonical = canonical) }
        mapOf("bloqueada" to "ACCOUNT_BLOCKED", "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING", "DELETED" to "FORBIDDEN", "" to "FORBIDDEN").forEach { (state, code) ->
            canonical.status = state
            val response = client.get("/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN") }
            assertEquals(HttpStatusCode.Forbidden, response.status, state)
            val json = bodyOf(response.bodyAsText())
            assertEquals(code, json["code"]!!.jsonPrimitive.content, state)
            assertEquals("AccessDenied", json["error"]!!.jsonPrimitive.content, state)
        }
        canonical.status = "activa"
        canonical.profileExists = false
        assertEquals("PROFILE_MISSING", bodyOf(client.get("/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN") }.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue(canonical.upstream.rpcCalls.isEmpty())
    }

    @Test
    fun legacyRegisterProfileWritesThroughTheCanonicalRpcNeverThePublicUsuarioRpc() = testApplication {
        val canonical = CanonicalTestEnv().apply { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) } }
        application { authTestModule(FakeGateway(), canonical = canonical) }
        val before = counterOf("EP-218")

        val response = client.post("/v1/auth/register/profile") {
            header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"firstName":"Ada","lastName":"Lovelace","username":"ada_l","specialty":"Sanidad"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("@${LegacyEndpoints.SLICE_1_DEPRECATED_AT.epochSecond}", response.headers[LegacyHeaders.DEPRECATION])
        assertEquals("</api/v1/app/me/onboarding>; rel=\"successor-version\"", response.headers[HttpHeaders.Link])
        assertEquals(before + 1, counterOf("EP-218"))
        assertEquals(listOf("app_completar_onboarding"), canonical.upstream.rpcNames())
        assertEquals(TEST_SPECIALTY, canonical.upstream.lastArgs("app_completar_onboarding")["p_especialidad_id"]!!.jsonPrimitive.content)
        assertTrue(canonical.upstream.log.none { it.contains("oposibots_upsert_own_profile") || it.contains("/rest/v1/usuario") })
    }

    @Test
    fun legacyRegisterProfileIsIdenticalWhateverTheInvitationCodeIs() = testApplication {
        val attached = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val canonical = CanonicalTestEnv().apply {
            upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) }
            referral = ReferralAttach { _, code -> attached += code; if (code == "BOOM0001") error("the referral store is down") }
        }
        application { authTestModule(FakeGateway(), canonical = canonical) }
        suspend fun save(extra: String) = client.post("/v1/auth/register/profile") {
            header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"firstName":"Ada","lastName":"Lovelace","username":"ada_l","specialty":"Sanidad"$extra}""")
        }
        val none = save("")
        val valid = save(""","codigoInvitacion":"ABCDEFGH"""")
        val unknown = save(""","codigoInvitacion":"ZZZZZZZZ"""")
        val own = save(""","codigoInvitacion":"OWNCODE2"""")
        val failing = save(""","codigoInvitacion":"BOOM0001"""")
        val garbage = save(""","codigoInvitacion":"x y z <script>"""")
        for ((name, r) in listOf("none" to none, "valid" to valid, "unknown" to unknown, "own" to own, "failing" to failing, "garbage" to garbage)) {
            assertEquals(HttpStatusCode.NoContent, r.status, name)
            assertEquals("", r.bodyAsText(), name)
        }
        assertEquals(listOf(null, "ABCDEFGH", "ZZZZZZZZ", "OWNCODE2", "BOOM0001", "x y z <script>"), attached.toList(), "the code reaches the bridge as sent; the bridge decides, the route never differs")
        assertEquals(6, canonical.upstream.rpcNames().count { it == "app_completar_onboarding" }, "onboarding ran every time")
    }

    @Test
    fun legacyRegisterProfileAcceptsTheCanonicalSpecialtyIdAndTheIdWinsOverTheName() = testApplication {
        val canonical = CanonicalTestEnv().apply { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) } }
        application { authTestModule(FakeGateway(), canonical = canonical) }
        val otherId = "5a000000-0000-4000-8000-0000000000aa"
        suspend fun save(body: String) = client.post("/v1/auth/register/profile") {
            header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        // Solo el id (sin nombre): vale, y es el id lo que llega a la RPC canonica.
        assertEquals(HttpStatusCode.NoContent, save("""{"firstName":"Ada","lastName":"L","username":"ada_l","especialidadId":"$otherId"}""").status)
        assertEquals(otherId, canonical.upstream.lastArgs("app_completar_onboarding")["p_especialidad_id"]!!.jsonPrimitive.content)

        // Los dos: GANA el id (el nombre "Sanidad" resolveria a TEST_SPECIALTY).
        assertEquals(HttpStatusCode.NoContent, save("""{"firstName":"Ada","lastName":"L","username":"ada_l","specialty":"Sanidad","especialidadId":"$otherId"}""").status)
        assertEquals(otherId, canonical.upstream.lastArgs("app_completar_onboarding")["p_especialidad_id"]!!.jsonPrimitive.content)

        // Solo el nombre: sigue funcionando (compatibilidad).
        assertEquals(HttpStatusCode.NoContent, save("""{"firstName":"Ada","lastName":"L","username":"ada_l","specialty":"Sanidad"}""").status)
        assertEquals(TEST_SPECIALTY, canonical.upstream.lastArgs("app_completar_onboarding")["p_especialidad_id"]!!.jsonPrimitive.content)

        // Un id mal formado es 400 y no llega a la RPC; uno inexistente o inactivo lo rechaza la base con 422.
        val before = canonical.upstream.rpcNames().size
        val malformed = save("""{"firstName":"Ada","lastName":"L","username":"ada_l","especialidadId":"not-a-uuid"}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(before, canonical.upstream.rpcNames().size)
        canonical.upstream.onRpc = { _, _ -> rpcError(422, "22023", "UNKNOWN_SPECIALTY") }
        val unknown = save("""{"firstName":"Ada","lastName":"L","username":"ada_l","especialidadId":"$otherId"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        assertEquals("UNKNOWN_SPECIALTY", bodyOf(unknown.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun legacyRegisterProfileKeepsItsLegacyErrorsAndAddsTheCanonicalOnes() = testApplication {
        val canonical = CanonicalTestEnv()
        application { authTestModule(FakeGateway(), canonical = canonical) }
        suspend fun save(body: String) = client.post("/v1/auth/register/profile") {
            header(HttpHeaders.Authorization, "Bearer $TEST_LONG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val unknownSpecialty = save("""{"firstName":"Ada","lastName":"L","username":"ada_l","specialty":"Medicina"}""")
        assertEquals(HttpStatusCode.BadRequest, unknownSpecialty.status)
        assertEquals(listOf("SPECIALTY_NOT_SELECTED"), bodyOf(unknownSpecialty.bodyAsText())["issues"]!!.jsonArray.map { it.jsonPrimitive.content })

        val blank = save("""{"firstName":"","lastName":"L","username":"ada_l","specialty":"Sanidad"}""")
        assertEquals(listOf("BLANK_FIRST_NAME"), bodyOf(blank.bodyAsText())["issues"]!!.jsonArray.map { it.jsonPrimitive.content })

        canonical.upstream.onRpc = { _, _ -> rpcError(409, "23505", "USERNAME_TAKEN") }
        val taken = save("""{"firstName":"Ada","lastName":"L","username":"ada_l","specialty":"Sanidad"}""")
        assertEquals(HttpStatusCode.Conflict, taken.status)
        val json = bodyOf(taken.bodyAsText())
        assertEquals("USERNAME_TAKEN", json["code"]!!.jsonPrimitive.content)
        assertEquals("Conflict", json["error"]!!.jsonPrimitive.content)

        canonical.status = "bloqueada"
        val blocked = save("""{"firstName":"Ada","lastName":"L","username":"ada_l","specialty":"Sanidad"}""")
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals("ACCOUNT_BLOCKED", bodyOf(blocked.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    /** R5: el perfil consumer monta TAMBIEN las rutas canonicas (si no, OB apuntando a consumer recibe 404), con el sobre canonico. */
    @Test
    fun theConsumerProfileMountsTheCanonicalProfileRoutesWithTheCanonicalEnvelope() = testApplication {
        val env = CanonicalTestEnv().apply { upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" } }
        application { configureConsumerAuthOnly(env.runtime()) }

        val anonymous = client.get("/api/v1/app/me")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("UNAUTHENTICATED", bodyOf(anonymous.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertEquals("Bearer", anonymous.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(anonymous.headers["X-Request-Id"]!!.isNotBlank())

        val me = client.get("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(TEST_UID, bodyOf(me.bodyAsText())["id"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/app/legal/documents").status)
        // Y el resto de la superficie consumer no cambia.
        assertEquals(HttpStatusCode.NotFound, client.get("/health").status)
    }

    @Test
    fun aConsumerRuntimeAlwaysMountsTheCanonicalProfileRoutes() = testApplication {
        // MASTER-ARCH-005: `canonical` es obligatorio; ya no existe el modo "sin perfil canonico" (antes 404 silencioso).
        application {
            configureConsumerAuthOnly(
                OposiBotsAuthRuntime(testConfiguration(), FakeGateway(), AllowingRateLimiter, testJson, CanonicalTestEnv().runtime().canonical, NoAccessRequestsMailGuard),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/app/me").status)
    }

    private fun io.ktor.server.application.Application.authTestModule(
        gateway: OposiBotsAuthGateway,
        rateLimiter: OposiBotsAuthRateLimiter = AllowingRateLimiter,
        configuration: OposiBotsAuthConfiguration = testConfiguration(),
        canonical: CanonicalTestEnv = CanonicalTestEnv().apply {
            upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" }
        },
    ) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val runtime = OposiBotsAuthRuntime(
            configuration = configuration,
            gateway = gateway,
            rateLimiter = rateLimiter,
            json = testJson,
            canonical = canonical.runtime().let {
                // El limitador de la prueba manda tambien sobre el perfil canonico.
                it.canonical
            },
            accessRequestMailGuard = NoAccessRequestsMailGuard,
        )
        routing {
            oposiBotsAuthRoutes(runtime)
            canonicalProfileRoutes(runtime)
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
    var loginRequest: OposiBotsLoginRequest? = null
    var recoverRequest: OposiBotsRecoverRequest? = null
    var exchangeRequest: OposiBotsEmailExchangeRequest? = null
    var googleRequest: OposiBotsGoogleRequest? = null
    var appleRequest: OposiBotsAppleRequest? = null
    var loginCalls = 0
    var logoutToken: String? = null
    var failure: Exception? = null
    var resendCalls = 0
    var resetCalls = 0

    override suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext) =
        OposiBotsRegisterResponse().also { registerRequest = request }

    override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) {
        resendCalls++
    }

    override suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto {
        exchangeRequest = request
        failure?.let { throw it }
        return session()
    }

    override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto {
        loginRequest = request
        loginCalls++
        failure?.let { throw it }
        return session()
    }

    override suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto {
        failure?.let { throw it }
        return session()
    }
    override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) {
        logoutToken = accessToken
    }

    override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) {
        recoverRequest = request
    }

    override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) {
        resetCalls++
        failure?.let { throw it }
    }

    override suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext): OposiBotsOAuthResponse {
        googleRequest = request
        failure?.let { throw it }
        return OposiBotsOAuthResponse(session(), false)
    }

    override suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext): OposiBotsOAuthResponse {
        appleRequest = request
        failure?.let { throw it }
        return OposiBotsOAuthResponse(session(), false)
    }

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
