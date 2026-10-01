package com.oposicontrol.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * LOOP 6, area L2 (OposiControl backend/ktor, planos auth/app/usuarios legacy):
 * MASTER6-OB-002 (causa de los fallos de Apple), MASTER6-OB-001 (evento del 503 inesperado), MASTER6-AUTH-008 (precarga
 * del JWKS), MASTER6-AUTH-009 (fallback legacy de /api/v1/app), MASTER6-API-006 (topes del feedback), MASTER6-API-004
 * (rechazos de GoTrue al crear), MASTER6-DB-001 (borrado legacy con retencion legal).
 */
class Master6AuthL2Loop6Test {

    private class Capture(vararg names: String) : AutoCloseable {
        private val attached = names.map { LoggerFactory.getLogger(it) as LogbackLogger }
        private val previous = attached.map { it.level }
        val appender = ListAppender<ILoggingEvent>().also { a -> a.start(); attached.forEach { it.addAppender(a) } }
        init { attached.forEach { it.level = Level.DEBUG } }
        fun lines(): List<String> = appender.list.map { it.formattedMessage }
        override fun close() = attached.forEachIndexed { i, it -> it.detachAppender(appender); it.level = previous[i] }
    }

    // ------------------------------------------------------------------ MASTER6-OB-002

    private val context = OposiBotsAuthContext("request-ob2", "127.0.0.1")
    private val uid = "22222222-2222-4222-8222-222222222222"

    private fun unsignedJwt(subject: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$subject","exp":4102444800}""".toByteArray())
        return "$header.$payload.signature"
    }

    private fun appleGateway(apple: () -> Pair<HttpStatusCode, String>?): Pair<SupabaseOposiBotsAuthGateway, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val session = """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$uid","email":"p@example.com"}}"""
        val client = HttpClient(MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                request.url.host == "appleid.apple.com" -> {
                    val (status, body) = apple() ?: throw IOException("connect timed out appleid.apple.com")
                    respond(ByteReadChannel(body), status, json)
                }
                path == "/auth/v1/token" -> respond(ByteReadChannel(session), HttpStatusCode.OK, json)
                path == "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                path == "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, json)
                path == "/rest/v1/perfiles_usuario" -> respond(
                    ByteReadChannel("""[{"usuario_id":"$uid","estado_cuenta":"activa","onboarding_completado":true}]"""),
                    HttpStatusCode.OK,
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
            appleClientSecret = "apple-secret-value",
            captchaRequired = false,
            trustProxyHeaders = false,
            trustedProxyHosts = emptySet(),
            forwardClientIpToSupabase = false,
            rateLimitMode = "memory",
            rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
            valkeyUrl = null,
        )
        return SupabaseOposiBotsAuthGateway(configuration, Json { ignoreUnknownKeys = true }, client) to requests
    }

    @Test
    fun anExpiredAppleClientSecretIsA503WithItsOwnCategoryInTheLog() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            val (gateway, _) = appleGateway { HttpStatusCode.BadRequest to """{"error":"invalid_client"}""" }
            val failure = assertFailsWith<OposiBotsAuthProblem> {
                gateway.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "single-use-code"), context)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, failure.status, "fallo de configuracion del servidor, no del usuario")
            val line = capture.lines().single { "apple verification failed" in it }
            assertTrue("category=apple_token_rejected" in line && "upstreamStatus=400" in line && "appleError=invalid_client" in line, line)
            assertTrue("requestId=request-ob2" in line, line)
            assertFalse("single-use-code" in line || "apple-secret-value" in line, "nunca el code ni el secreto: $line")
        }
    }

    @Test
    fun aBadAppleCodeStays401ButIsNowDistinguishableInTheLog() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            val (gateway, _) = appleGateway { HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"code a@b.c expired"}""" }
            val failure = assertFailsWith<OposiBotsAuthProblem> {
                gateway.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "single-use-code"), context)
            }
            assertEquals(HttpStatusCode.Unauthorized, failure.status)
            val line = capture.lines().single { "apple verification failed" in it }
            assertTrue("appleError=invalid_grant" in line, line)
            assertFalse("a@b.c" in line, "nunca el texto libre de Apple: $line")
        }
    }

    @Test
    fun anUndocumentedAppleErrorIsLoggedAsOtherNotVerbatim() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            val (gateway, _) = appleGateway { HttpStatusCode.BadRequest to """{"error":"token=abc person@example.com"}""" }
            assertFailsWith<OposiBotsAuthProblem> { gateway.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "c"), context) }
            val line = capture.lines().single { "apple verification failed" in it }
            assertTrue("appleError=other" in line, line)
            assertFalse("person@example.com" in line, line)
        }
    }

    @Test
    fun theOtherAppleFailuresHaveDistinctCategories() = runTest {
        Capture("OC-KTOR-AUTH-GATEWAY").use { capture ->
            val (mismatch, _) = appleGateway { HttpStatusCode.OK to """{"id_token":"${unsignedJwt("otro")}"}""" }
            assertFailsWith<OposiBotsAuthProblem> { mismatch.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "c"), context) }
            val (missing, _) = appleGateway { HttpStatusCode.OK to """{"token_type":"Bearer"}""" }
            assertFailsWith<OposiBotsAuthProblem> { missing.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "c"), context) }
            val (garbage, _) = appleGateway { HttpStatusCode.OK to "<html>" }
            val undecodable = assertFailsWith<OposiBotsAuthProblem> { garbage.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "c"), context) }
            assertEquals(HttpStatusCode.ServiceUnavailable, undecodable.status)
            val (down, _) = appleGateway { null }
            assertFailsWith<OposiBotsAuthProblem> { down.apple(OposiBotsAppleRequest(unsignedJwt("a-1"), "c"), context) }
            val lines = capture.lines()
            listOf("apple_subject_mismatch", "apple_token_missing_id_token", "apple_token_undecodable").forEach { category ->
                assertTrue(lines.any { "category=$category" in it }, "$category: $lines")
            }
            val transport = lines.single { "category=apple_token_transport" in it }
            assertTrue("error=IOException" in transport, transport)
            assertFalse("appleid.apple.com" in transport, "sin el mensaje (lleva la URL): $transport")
        }
    }

    // ------------------------------------------------------------------ MASTER6-OB-001

    private class ThrowingLoginGateway(private val failure: Exception) : OposiBotsAuthGateway by NoAuthGateway {
        override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto = throw failure
    }

    @Test
    fun anUnexpectedFailureOnTheAuthPlaneEmitsTheStrategicEventAndTheCauseClass() {
        Capture("OC-KTOR-AUTH", "OC-KTOR-OBSERVABILITY").use { capture ->
            testApplication {
                val env = CanonicalTestEnv(gateway = ThrowingLoginGateway(IllegalStateException("socket reset")))
                application { env.mount(this) }
                val response = client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"person@example.com","password":"safe-password-12"}""")
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                val requestId = response.headers["X-Request-Id"]!!
                val lines = capture.lines().filter { requestId in it }
                assertTrue(
                    lines.any { "event=SUPABASE_REQUEST_FAILED" in it && "operation=LOGIN" in it && "error_type=UPSTREAM_UNAVAILABLE" in it },
                    "antes: sin evento estrategico en esta rama: $lines",
                )
                assertTrue(lines.any { "auth failure" in it && "causeClass=IllegalStateException" in it }, lines.toString())
                assertEquals(1, lines.count { it.startsWith("auth operation=LOGIN") && "status=503" in it }, "una sola linea de resultado: $lines")
                assertTrue(lines.none { "person@example.com" in it })
            }
        }
    }

    // ------------------------------------------------------------------ MASTER6-AUTH-008

    @Volatile private var hang = false
    private val hits = AtomicInteger()
    private val release = CountDownLatch(1)
    @Volatile private var jwksBody = "{}"
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/auth/v1/.well-known/jwks.json") { exchange ->
            hits.incrementAndGet()
            if (hang) release.await(30, TimeUnit.SECONDS)
            val bytes = jwksBody.toByteArray()
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }
    private val baseUrl = "http://${server.address.address.hostAddress}:${server.address.port}"

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun b64(v: BigInteger): String {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val padded = ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
    }

    private fun jwks(kid: String, kp: KeyPair): String {
        val w = (kp.public as ECPublicKey).w
        return """{"keys":[{"kty":"EC","crv":"P-256","kid":"$kid","x":"${b64(w.affineX)}","y":"${b64(w.affineY)}"}]}"""
    }

    private fun tokenFor(kid: String, kp: KeyPair): String =
        JWT.create().withKeyId(kid).withIssuer("$baseUrl/auth/v1").withSubject(uid)
            .sign(Algorithm.ECDSA256(kp.public as ECPublicKey, kp.private as ECPrivateKey))

    private fun verifier() = SupabaseJwtVerifier(baseUrl, Json { ignoreUnknownKeys = true }, java.net.http.HttpClient.newHttpClient(), fetchTimeout = Duration.ofSeconds(3))

    /**
     * Rotacion: cache caliente con k1; Supabase pasa a firmar con k2. Un hilo descarga (lento, mas que
     * [JWKS_REFRESH_LOCK_WAIT_MS]) y llega una rafaga con el MISMO k2. Una sola descarga las resuelve a todas: antes, todas
     * menos la que descargaba salian con 503 a los 250 ms.
     */
    @Test
    fun aRotationBurstWithTheNewKidWaitsForTheSingleInFlightFetchInsteadOf503() {
        val (results, fetchHits) = rotationBurst(burstKid = "k2")
        results.forEach { assertEquals(null, it, "la clave nueva verifica para toda la rafaga: $results") }
        assertEquals(1, fetchHits, "una sola descarga para toda la rafaga")
    }

    /** MASTER5-SEC-001 sigue en pie: un `kid` DISTINTO del que se descarga no espera la descarga, 503 a los 250 ms. */
    @Test
    fun aDifferentUnknownKidDuringTheRotationFetchStillGetsTheBounded503() {
        val (results, _) = rotationBurst(burstKid = "forged")
        results.forEach { assertIs<IOException>(it, "kid ajeno a la descarga en curso: 503 reintentable") }
    }

    private fun rotationBurst(burstKid: String): Pair<List<Throwable?>, Int> {
        var now = 1_000_000L
        val k1 = newKey()
        val k2 = newKey()
        jwksBody = jwks("k1", k1)
        val v = SupabaseJwtVerifier(baseUrl, Json { ignoreUnknownKeys = true }, java.net.http.HttpClient.newHttpClient(), fetchTimeout = Duration.ofSeconds(3), clock = { now })
        assertEquals(uid, v.subFrom(tokenFor("k1", k1)))
        val w1 = (k1.public as ECPublicKey).w
        val w2 = (k2.public as ECPublicKey).w
        jwksBody = """{"keys":[{"kty":"EC","crv":"P-256","kid":"k1","x":"${b64(w1.affineX)}","y":"${b64(w1.affineY)}"},""" +
            """{"kty":"EC","crv":"P-256","kid":"k2","x":"${b64(w2.affineX)}","y":"${b64(w2.affineY)}"}]}"""
        now += 10_000 // fuera de la ventana anti-tormenta
        hang = true
        val before = hits.get()
        val pool = Executors.newFixedThreadPool(9)
        try {
            val fetcher = pool.submit<Throwable?> { runCatching { v.subFrom(tokenFor("k2", k2)) }.exceptionOrNull() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (hits.get() == before && System.nanoTime() < deadline) Thread.onSpinWait()
            val burstToken = if (burstKid == "k2") tokenFor("k2", k2) else tokenFor(burstKid, newKey())
            val burst = (1..8).map { pool.submit<Throwable?> { runCatching { v.subFrom(burstToken) }.exceptionOrNull() } }
            // La descarga tarda MAS que la espera acotada del lock.
            Thread.sleep(JWKS_REFRESH_LOCK_WAIT_MS + 400)
            release.countDown()
            val results = listOf(fetcher.get(10, TimeUnit.SECONDS)) + burst.map { it.get(10, TimeUnit.SECONDS) }
            return (if (burstKid == "k2") results else results.drop(1)) to (hits.get() - before)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * MASTER7-AUTH-004: intercalacion determinista. El que espera agota la espera del lock y, justo antes de leer
     * `inFlight`, la descarga ajena (mismo `kid`) publica la clave, limpia `inFlight` y suelta el lock. Antes: 503
     * (`IOException`) con la clave ya en cache. Ahora la cache se relee y la clave verifica.
     */
    @Test
    fun aKeyPublishedJustBeforeTheInFlightReadIsServedInsteadOf503() {
        var now = 1_000_000L
        val k1 = newKey()
        val k2 = newKey()
        jwksBody = jwks("k1", k1)
        val v = SupabaseJwtVerifier(baseUrl, Json { ignoreUnknownKeys = true }, java.net.http.HttpClient.newHttpClient(), fetchTimeout = Duration.ofSeconds(3), clock = { now })
        assertEquals(uid, v.subFrom(tokenFor("k1", k1)))
        val w1 = (k1.public as ECPublicKey).w
        val w2 = (k2.public as ECPublicKey).w
        jwksBody = """{"keys":[{"kty":"EC","crv":"P-256","kid":"k1","x":"${b64(w1.affineX)}","y":"${b64(w1.affineY)}"},""" +
            """{"kty":"EC","crv":"P-256","kid":"k2","x":"${b64(w2.affineX)}","y":"${b64(w2.affineY)}"}]}"""
        now += 10_000 // fuera de la ventana anti-tormenta
        hang = true
        val before = hits.get()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val fetcher = pool.submit<String> { v.subFrom(tokenFor("k2", k2)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (hits.get() == before && System.nanoTime() < deadline) Thread.onSpinWait()
            val fired = java.util.concurrent.atomic.AtomicBoolean(false)
            v.beforeInFlightReadForTesting = {
                if (fired.compareAndSet(false, true)) {
                    // La descarga termina AQUI: publica k2, limpia inFlight y suelta el lock.
                    release.countDown()
                    assertEquals(uid, fetcher.get(10, TimeUnit.SECONDS))
                }
            }
            val waiter = runCatching { v.subFrom(tokenFor("k2", k2)) }
            assertTrue(fired.get(), "la intercalacion se ejerce de verdad")
            assertEquals(uid, waiter.getOrThrow(), "la clave ya publicada se sirve, no un 503")
            assertEquals(1, hits.get() - before, "sin descarga extra")
        } finally {
            v.beforeInFlightReadForTesting = null
            pool.shutdownNow()
        }
    }

    @Test
    fun theWarmUpLoadsTheKeysSoTheFirstBurstVerifiesWithoutAnyFetch() {
        val key = newKey()
        jwksBody = jwks("k1", key)
        val v = verifier()
        assertTrue(v.warmUp(), "la precarga deja claves")
        assertEquals(1, hits.get())
        val token = tokenFor("k1", key)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<String> { v.subFrom(token) } }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(List(8) { uid }, results)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, hits.get(), "ninguna descarga extra")
        assertTrue(v.warmUp(), "idempotente")
        assertEquals(1, hits.get())
    }

    @Test
    fun aFailedWarmUpIsHarmlessAndOnlyWarnsTheClass() {
        jwksBody = "not json"
        Capture("OC-KTOR-JWT").use { capture ->
            assertFalse(verifier().warmUp())
            val line = capture.lines().single { "JWKS warm-up failed" in it }
            assertTrue("IOException" in line, line)
        }
    }

    @Test
    fun theModuleLaunchesTheWarmUp() {
        val source = File("src/main/kotlin/com/oposicontrol/backend/Application.kt").readText()
        assertTrue(Regex("""launch\(kotlinx\.coroutines\.Dispatchers\.IO\) \{ jwtVerifier\.warmUp\(\) }""").containsMatchIn(source))
    }

    // ------------------------------------------------------------------ MASTER6-AUTH-009

    private fun legacyAppModule(application: io.ktor.server.application.Application, verifier: SupabaseJwtVerifier) = with(application) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AppV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({}) { V1StubResponse(200, "[]") },
        )
        routing { appV1Routes(gateway, verifier, allowDevUser = false, identityResolver = null) }
    }

    @Test
    fun theLegacyAppFallbackMapsAJwksOutageTo503NotTo401() = testApplication {
        jwksBody = "{}" // sin `keys`: JWKS ilegible
        application { legacyAppModule(this, verifier()) }
        val response = client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer ${tokenFor("k1", newKey())}") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
    }

    /**
     * El StatusPages del backend ya no publica el mensaje de [AppUnauthorized]; esta prueba mira el MENSAJE de la
     * excepcion (defensa en profundidad: cualquier otro manejador que lo publicara filtraria el detalle del verificador).
     */
    @Test
    fun theLegacyAppFallbackNoLongerCarriesTheVerifierMessage() = testApplication {
        application {
            install(StatusPages) {
                exception<AppUnauthorized> { call, cause -> call.respondText(cause.message.orEmpty(), status = HttpStatusCode.Unauthorized) }
            }
            val gateway = AppV1Gateway.forTesting(
                supabaseUrl = "https://stub.supabase.test",
                serviceRoleKey = "stub-key",
                json = Json { ignoreUnknownKeys = true },
                client = V1StubHttpClient({}) { V1StubResponse(200, "[]") },
            )
            routing { appV1Routes(gateway, verifier(), allowDevUser = false, identityResolver = null) }
        }
        val noKid = JWT.create().withSubject(uid).sign(Algorithm.HMAC256("x".repeat(32)))
        val response = client.get("/api/v1/app/reto-diario") { header(HttpHeaders.Authorization, "Bearer $noKid") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("JWT invalido.", response.bodyAsText(), "mensaje fijo, sin el detalle del verificador")
    }

    // ------------------------------------------------------------------ MASTER6-API-006

    private val pregunta = "7a7a7a7a-0000-4000-8000-000000000001"

    private fun feedbackGateway(sent: MutableList<String>) = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ sent += it.method() + " " + it.uri() }) { uri ->
            if ("feedback_pregunta" in uri) V1StubResponse(201, """[{"id":"7b7b7b7b-0000-4000-8000-000000000001"}]""") else V1StubResponse(200, "[]")
        },
    )

    @Test
    fun feedbackFieldsAreBoundedBeforeAnyWrite() {
        val sent = mutableListOf<String>()
        val g = feedbackGateway(sent)
        val tooLong = assertFailsWith<BackendDomainException> {
            g.crearFeedback(uid, AppFeedbackRequest(preguntaId = pregunta, descripcion = "x".repeat(FEEDBACK_DESCRIPTION_MAX + 1)))
        }
        assertEquals("VALIDATION", tooLong.errorCode)
        assertTrue("OUT_OF_RANGE" in tooLong.details.toString(), tooLong.details.toString())
        val badType = assertFailsWith<BackendDomainException> {
            g.crearFeedback(uid, AppFeedbackRequest(preguntaId = pregunta, tipoFeedback = "y".repeat(5_000)))
        }
        assertEquals("VALIDATION", badType.errorCode)
        assertTrue(sent.isEmpty(), "nada sale: $sent")
        // Limites exactos aceptados (puntos de codigo: 2000 emojis son 4000 unidades UTF-16).
        g.crearFeedback(uid, AppFeedbackRequest(preguntaId = pregunta, tipoFeedback = "respuesta_incorrecta", descripcion = "😀".repeat(FEEDBACK_DESCRIPTION_MAX)))
        g.crearFeedback(uid, AppFeedbackRequest(preguntaId = pregunta, tipoFeedback = "", descripcion = ""))
        assertEquals(2, sent.count { "feedback_pregunta" in it })
    }

    // ------------------------------------------------------------------ MASTER6-API-004 / MASTER6-DB-001

    private val authUserId = "33333333-3333-4333-8333-333333333333"
    private val usuarioRow =
        """[{"id":7,"auth_user_id":"$authUserId","email":"a@example.com","nombre":"Ada","apellidos":"L","username":"ada","especialidad_registro":"E","perfil_completo":true,"perfil_completado_at":null,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""

    private fun userAdmin(
        authCreate: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"id":"$authUserId"}""",
        retention: () -> String = { "[]" },
        authDelete: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<HttpRequestData> = mutableListOf(),
    ): SupabaseOposiBotsUserAdminGateway {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/auth/v1/admin/users" && request.method == HttpMethod.Post -> respond(ByteReadChannel(authCreate.second), authCreate.first, json)
                path.startsWith("/auth/v1/admin/users/") && request.method == HttpMethod.Delete ->
                    respond(ByteReadChannel("""{"code":500,"error_code":"unexpected_failure","msg":"Database error deleting user"}"""), authDelete, json)
                path == "/rest/v1/aceptaciones_legales" || path == "/rest/v1/solicitudes_eliminacion_cuenta" ->
                    respond(ByteReadChannel(if (path.endsWith("aceptaciones_legales")) retention() else "[]"), HttpStatusCode.OK, json)
                path == "/rest/v1/usuario" -> respond(ByteReadChannel(usuarioRow), HttpStatusCode.OK, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        return SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)
    }

    private fun createRequest() = OposiBotsUserAdminCreateRequest(
        email = "new@example.com", password = "x", nombre = "N", apellidos = "A", username = null, especialidadRegistro = "E",
    )

    @Test
    fun goTrueClientRejectionsOnCreateAre4xxNot502() = runTest {
        val duplicate = assertFailsWith<BackendDomainException> {
            userAdmin(authCreate = HttpStatusCode.UnprocessableEntity to """{"code":422,"error_code":"email_exists","msg":"x"}""").createUser(createRequest())
        }
        assertEquals("CONFLICT", duplicate.errorCode)
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        val weak = assertFailsWith<BackendDomainException> {
            userAdmin(authCreate = HttpStatusCode.UnprocessableEntity to """{"code":422,"error_code":"weak_password","msg":"x"}""").createUser(createRequest())
        }
        assertEquals("VALIDATION", weak.errorCode)
        val conflict = assertFailsWith<BackendDomainException> {
            userAdmin(authCreate = HttpStatusCode.Conflict to """{"msg":"exists"}""").createUser(createRequest())
        }
        assertEquals("CONFLICT", conflict.errorCode)
        // 5xx sigue siendo fallo del upstream (502 por StatusPages), no se disfraza de error del cliente.
        val upstream = assertFailsWith<SupabaseHttpStatusException> {
            userAdmin(authCreate = HttpStatusCode.InternalServerError to """{"msg":"down"}""").createUser(createRequest())
        }
        assertEquals(500, upstream.status)
    }

    @Test
    fun legacyDeleteOfAnAccountUnderLegalRetentionIs409AndKeepsTheRow() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        // GoTrue no puede borrar la identidad (FK NO ACTION): 500 "Database error deleting user".
        val failure = assertFailsWith<BackendDomainException> {
            userAdmin(
                retention = { """[{"usuario_id":"$authUserId"}]""" },
                authDelete = HttpStatusCode.InternalServerError,
                requests = requests,
            ).deleteUser(7)
        }
        assertEquals("CONFLICT", failure.errorCode)
        assertEquals(HttpStatusCode.Conflict, failure.status)
        assertTrue(
            requests.none { it.method == HttpMethod.Delete && it.url.encodedPath == "/rest/v1/usuario" },
            "la fila usuario sigue ahi: ${requests.map { it.method.value + " " + it.url }}",
        )
    }

    @Test
    fun withoutRetentionRowsAGoTrue5xxIsStillAnUpstreamFailure() = runTest {
        // Sin filas de retencion, un 5xx de GoTrue sigue siendo un fallo del upstream (502), nunca un 409 inventado.
        assertFailsWith<SupabaseHttpStatusException> { userAdmin(authDelete = HttpStatusCode.InternalServerError).deleteUser(7) }
        // Y sin retencion ni fallo, el borrado legacy sigue funcionando sin lecturas extra.
        val requests = mutableListOf<HttpRequestData>()
        assertTrue(userAdmin(requests = requests).deleteUser(7))
        assertTrue(requests.none { "aceptaciones_legales" in it.url.encodedPath }, requests.map { it.url.encodedPath }.toString())
    }
}
