package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Revision de seguridad independiente de B1 (2026-10-13): «una cuenta de solicitud de acceso de administrador no obtiene
 * sesion de consumidor sin aprobacion» se garantiza en el UNICO punto donde se entregan sesiones (`enforceAccountGate`),
 * no solo reteniendo el correo. Recorre ruta -> gateway REAL -> GoTrue y `perfiles_usuario` (falsos, graban las
 * peticiones) y gateway -> puerta REAL ([PostgrestAccessRequestMailGuard]) -> `solicitudes_acceso_admin` (falsa, graba
 * las rutas pedidas).
 *
 * Cada prueba comprueba la lectura concreta que hace la puerta (por `usuario_id`) y la respuesta: sin la puerta, ni se
 * haria esa lectura ni saldria el 403.
 *
 * Ademas: el tope de 72 bytes de una contrasena nueva (registro y reset), que cerraba el oraculo del registro retenido.
 */
class AccessRequestSessionGateTest {
    private val context = OposiBotsAuthContext("request-session-gate", "127.0.0.1")
    private val pendingEmail = "ada@example.com"
    private val sessionLookup =
        "/rest/v1/solicitudes_acceso_admin?select=estado&usuario_id=eq.$UID&estado=neq.aprobada&limit=1"

    private data class Row(val userId: String, val email: String, val estado: String)

    /** `solicitudes_acceso_admin` falsa: aplica los filtros de las dos consultas reales (`usuario_id=eq.` o `email=eq.`, y `estado=neq.`). */
    private class FakeAccessRequests(vararg rows: Row) {
        private val rows = rows.toList()
        val paths = CopyOnWriteArrayList<String>()
        var failure: Exception? = null
        var rawBody: String? = null

        fun get(path: String): String {
            paths += path
            failure?.let { throw it }
            rawBody?.let { return it }
            check(path.startsWith("/rest/v1/solicitudes_acceso_admin?")) { "tabla inesperada: $path" }
            val query = path.substringAfter('?').split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
            }
            check(query.getValue("estado").startsWith("neq.")) { path }
            val notEstado = query.getValue("estado").removePrefix("neq.")
            val matching = when {
                "usuario_id" in query -> {
                    check(query.getValue("usuario_id").startsWith("eq.")) { path }
                    rows.filter { it.userId == query.getValue("usuario_id").removePrefix("eq.") }
                }
                else -> {
                    check(query.getValue("email").startsWith("eq.")) { path }
                    rows.filter { it.email == query.getValue("email").removePrefix("eq.") }
                }
            }
            return matching.filter { it.estado != notEstado }
                .take(query.getValue("limit").toInt())
                .joinToString(",", "[", "]") { """{"estado":"${it.estado}"}""" }
        }
    }

    /** GoTrue, `perfiles_usuario`, el reclamo OAuth y Apple, todos falsos detras del gateway real. */
    private class Upstream(profile: String = profileRow("activa")) {
        val requests = CopyOnWriteArrayList<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = request.url.encodedPath
            when {
                request.url.host == "appleid.apple.com" -> respond(ByteReadChannel("""{"id_token":"$ID_TOKEN"}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/token" || path == "/auth/v1/verify" -> respond(ByteReadChannel(SESSION), HttpStatusCode.OK, json)
                path == "/auth/v1/signup" -> respond(ByteReadChannel("""{"user":{"id":"user"}}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/user" && request.method == HttpMethod.Put -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, json)
                path == "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                path == "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, json)
                path == "/rest/v1/perfiles_usuario" -> respond(ByteReadChannel(profile), HttpStatusCode.OK, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })

        fun paths() = requests.map { it.url.encodedPath }
        fun logouts() = requests.filter { it.url.encodedPath == "/auth/v1/logout" }
        fun claims() = requests.count { it.url.encodedPath == "/rest/v1/rpc/oposibots_claim_oauth_credential" }
        fun appleCalls() = requests.count { it.url.host == "appleid.apple.com" }
    }

    private fun configuration() = testCanonicalConfiguration().copy(appleClientId = "apple-client", appleClientSecret = "apple-secret")

    private fun guard(requests: FakeAccessRequests) =
        PostgrestAccessRequestMailGuard(upstream = requests::get, emailRef = configuration()::authLogEmailRef)

    private fun Application.module(requests: FakeAccessRequests, upstream: Upstream) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        val configuration = configuration()
        val accessRequests = guard(requests)
        val runtime = OposiBotsAuthRuntime(
            configuration = configuration,
            gateway = SupabaseOposiBotsAuthGateway(configuration, testJson, accessRequests, upstream.client),
            rateLimiter = TestAllowAllLimiter,
            json = testJson,
            canonical = CanonicalTestEnv().runtime().canonical,
            accessRequestMailGuard = accessRequests,
        )
        routing { oposiBotsAuthRoutes(runtime) }
    }

    /** [transientRevocation]: lo que revoca la puerta ante un fallo de LECTURA (refresh no revoca nada, MASTER-AUTH-010). */
    private data class SessionRoute(val path: String, val body: String, val transientRevocation: String?, val oauth: Boolean = false)

    private val sessionRoutes = listOf(
        SessionRoute("/v1/auth/login", """{"email":"ada@example.com","password":"S3creta-Larga-9731"}""", "local"),
        SessionRoute("/v1/auth/refresh", """{"refreshToken":"old-refresh-token"}""", null),
        SessionRoute("/v1/auth/email/exchange", """{"code":"confirmation-token-hash"}""", "local"),
        SessionRoute("/v1/auth/oauth/google", """{"idToken":"$ID_TOKEN"}""", "local", oauth = true),
        SessionRoute("/v1/auth/oauth/apple", """{"identityToken":"$ID_TOKEN","authorizationCode":"single-use-code"}""", "local", oauth = true),
    )

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.jsonBody(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun assertNoSession(body: String, message: String) {
        assertFalse("minted-access" in body || "minted-refresh" in body || "accessToken" in body || "refreshToken" in body, "$message: $body")
    }

    // ------------------------------------------------------------------------------------------------ deniega

    @Test
    fun aNonApprovedRequestGetsNoSessionOnAnyRouteThatHandsOneOut() {
        for (estado in listOf("pendiente", "rechazada")) for (route in sessionRoutes) testApplication {
            val requests = FakeAccessRequests(Row(UID, pendingEmail, estado))
            val upstream = Upstream()
            application { module(requests, upstream) }

            val response = post(route.path, route.body)
            val text = response.bodyAsText()
            val message = "${route.path} ($estado)"

            assertEquals(HttpStatusCode.Forbidden, response.status, message)
            assertEquals("FORBIDDEN", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, message)
            assertNoSession(text, message)
            // La lectura de la puerta: exactamente esa, por id de usuario.
            assertEquals(listOf(sessionLookup), requests.paths.toList(), message)
            // Denegacion definitiva: se revocan TODAS las sesiones del usuario con el token recien acuñado.
            val logout = upstream.logouts().single()
            assertEquals("scope=global", logout.url.encodedQuery, message)
            assertEquals("Bearer minted-access", logout.headers[HttpHeaders.Authorization], message)
            // OAuth: ni se consume la credencial ni se canjea el codigo de Apple.
            assertEquals(0, upstream.claims(), message)
            assertEquals(0, upstream.appleCalls(), message)
        }
    }

    /** Se decide antes que el perfil: una cuenta de solicitud sin fila de perfil es FORBIDDEN, no PROFILE_MISSING. */
    @Test
    fun theAccessRequestDenialDoesNotDependOnTheProfileRow() {
        for (profile in listOf("[]", profileRow("bloqueada"))) testApplication {
            val requests = FakeAccessRequests(Row(UID, pendingEmail, "pendiente"))
            val upstream = Upstream(profile)
            application { module(requests, upstream) }

            val response = post(sessionRoutes[0].path, sessionRoutes[0].body)

            assertEquals(HttpStatusCode.Forbidden, response.status, profile)
            assertEquals("FORBIDDEN", response.jsonBody()["code"]?.jsonPrimitive?.content, profile)
            assertEquals("scope=global", upstream.logouts().single().url.encodedQuery, profile)
        }
    }

    // ------------------------------------------------------------------------------------------------ no deniega

    @Test
    fun anApprovedRequestNoRequestOrSomeoneElsesRequestKeepsTheUsualSession() {
        val tables = listOf(
            "sin solicitud" to arrayOf<Row>(),
            "aprobada" to arrayOf(Row(UID, pendingEmail, "aprobada")),
            "pendiente de otro usuario" to arrayOf(Row(OTHER_UID, "other@example.com", "pendiente")),
        )
        for ((name, rows) in tables) for (route in sessionRoutes) testApplication {
            val requests = FakeAccessRequests(*rows)
            val upstream = Upstream()
            application { module(requests, upstream) }

            val response = post(route.path, route.body)
            val body = response.jsonBody()
            val message = "${route.path} ($name)"

            assertEquals(HttpStatusCode.OK, response.status, message)
            val session = if (route.oauth) body.getValue("session").jsonObject else body
            assertEquals("minted-access", session["accessToken"]?.jsonPrimitive?.content, message)
            assertEquals("minted-refresh", session["refreshToken"]?.jsonPrimitive?.content, message)
            // La puerta pregunta SIEMPRE, tambien cuando deja pasar.
            assertEquals(listOf(sessionLookup), requests.paths.toList(), message)
            assertEquals(emptyList(), upstream.logouts().map { it.url.encodedQuery }, message)
            assertEquals(if (route.oauth) 1 else 0, upstream.claims(), message)
        }
    }

    /** La puerta de siempre sigue igual: una cuenta bloqueada sin solicitud es ACCOUNT_BLOCKED. */
    @Test
    fun aBlockedAccountWithoutRequestIsStillAccountBlocked() = testApplication {
        val requests = FakeAccessRequests()
        val upstream = Upstream(profileRow("bloqueada"))
        application { module(requests, upstream) }

        val response = post(sessionRoutes[0].path, sessionRoutes[0].body)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("ACCOUNT_BLOCKED", response.jsonBody()["code"]?.jsonPrimitive?.content)
        assertEquals("scope=global", upstream.logouts().single().url.encodedQuery)
    }

    // ------------------------------------------------------------------------------------------------ falla cerrado

    @Test
    fun aFailedLookupAnswers503AndHandsOutNoSession() {
        for (route in sessionRoutes) testApplication {
            val requests = FakeAccessRequests(Row(UID, pendingEmail, "aprobada")).apply {
                failure = IllegalStateException("Supabase GET -> 500 for $UID")
            }
            val upstream = Upstream()
            application { module(requests, upstream) }

            val response = post(route.path, route.body)
            val text = response.bodyAsText()

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, route.path)
            assertEquals("UPSTREAM_UNAVAILABLE", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, route.path)
            assertNoSession(text, route.path)
            assertTrue(requests.paths.isNotEmpty() && requests.paths.all { it == sessionLookup }, "${route.path}: ${requests.paths}")
            // Fallo transitorio: nunca en global (MASTER-AUTH-010); refresh no revoca nada y el resto solo la sesion acuñada.
            assertEquals(listOfNotNull(route.transientRevocation?.let { "scope=$it" }), upstream.logouts().map { it.url.encodedQuery }, route.path)
            assertEquals(0, upstream.claims(), route.path)
            assertEquals(0, upstream.appleCalls(), route.path)
        }
    }

    @Test
    fun anUnreadableLookupBodyAlsoFailsClosed() = testApplication {
        val requests = FakeAccessRequests().apply { rawBody = """{"message":"not a list"}""" }
        val upstream = Upstream()
        application { module(requests, upstream) }

        val response = post(sessionRoutes[0].path, sessionRoutes[0].body)
        val text = response.bodyAsText()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertNoSession(text, "login")
        assertEquals(listOf("scope=local"), upstream.logouts().map { it.url.encodedQuery })
    }

    /** MASTER2-AUTH-003: en refresh la lectura de la puerta se reintenta; un fallo pasajero de ESTA lectura tambien. */
    @Test
    fun aTransientLookupFailureOnRefreshIsRetriedAndThenDecides() = runBlocking {
        val calls = AtomicInteger()
        val upstream = Upstream()
        val flaky = PostgrestAccessRequestMailGuard(
            upstream = { if (calls.incrementAndGet() == 1) error("Supabase GET -> 503") else """[{"estado":"pendiente"}]""" },
            emailRef = configuration()::authLogEmailRef,
        )
        val gateway = SupabaseOposiBotsAuthGateway(configuration(), testJson, flaky, upstream.client)

        val denied = assertFailsWith<BackendDomainException> { gateway.refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }

        assertEquals(CanonicalErrorCode.FORBIDDEN.name, denied.errorCode)
        assertEquals(2, calls.get())
        assertEquals(listOf("scope=global"), upstream.logouts().map { it.url.encodedQuery })
    }

    /**
     * MASTER3-AUTH-005: la lectura de la puerta de sesion es un GET bloqueante. Si se cuelga, el limite por intento y el
     * plazo global de refresh la cortan igual que a la del perfil (el hilo se interrumpe): 503 dentro del plazo y sin
     * revocar nada. Reloj real.
     */
    @Test
    fun aHangingLookupOnRefreshIsCutByTheGateDeadlineAndRevokesNothing() = runBlocking {
        val interrupted = AtomicInteger()
        val upstream = Upstream()
        val hanging = PostgrestAccessRequestMailGuard(
            upstream = {
                try {
                    Thread.sleep(60_000)
                    "[]"
                } catch (interruption: InterruptedException) {
                    interrupted.incrementAndGet()
                    throw interruption
                }
            },
            emailRef = configuration()::authLogEmailRef,
        )
        val gateway = SupabaseOposiBotsAuthGateway(configuration(), testJson, hanging, upstream.client)

        val started = System.nanoTime()
        val failure = assertFailsWith<OposiBotsAuthProblem> { gateway.refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status, "plazo agotado = transitorio, nunca fail-open")
        assertTrue(elapsed < REFRESH_GATE_DEADLINE_MS + 2_000, "acotado por el plazo global: $elapsed ms")
        assertTrue(interrupted.get() >= 2, "cada intento colgado se interrumpe: ${interrupted.get()}")
        assertTrue(upstream.logouts().isEmpty(), "refresh no revoca por un fallo transitorio (MASTER-AUTH-010)")
    }

    // ------------------------------------------------------------------------------------------------ la puerta sola

    @Test
    fun theSessionLookupIsKeyedByTheLowercasedUserIdAndNothingElse() {
        assertEquals(sessionLookup, accessRequestSessionPath(UID.uppercase()))
    }

    @Test
    fun aUserIdThatIsNotAUuidNeverReachesTheUpstreamAndFailsClosed() = runTest {
        for (userId in listOf("", "not-a-uuid", "$UID&estado=eq.aprobada", "$UID,or=(estado.eq.aprobada)")) {
            val requests = FakeAccessRequests()

            val problem = assertFailsWith<OposiBotsAuthProblem> { guard(requests).deniesSession(userId) }

            assertEquals(HttpStatusCode.ServiceUnavailable, problem.status, userId)
            assertEquals(emptyList(), requests.paths.toList(), userId)
        }
    }

    // ------------------------------------------------------------------------------------------------ contrasena de 72 bytes

    private fun registerBody(email: String, password: String) = """{"email":"$email","password":"$password"}"""

    /** Mas de 72 bytes en UTF-8 (GoTrue las rechaza con 400), todas dentro de los 12..128 caracteres del contrato. */
    private val overLongPasswords = listOf(
        "a".repeat(73),
        "a".repeat(100),
        "a".repeat(128),
        "ñ".repeat(37), // 37 caracteres, 74 bytes
        "S3creta-" + "€".repeat(22), // 30 caracteres, 8 + 66 = 74 bytes
    )

    /**
     * El oraculo: el registro de un correo RETENIDO contesta 202 sin llegar a GoTrue, y GoTrue rechaza con 400 una
     * contrasena de mas de 72 bytes. Ahora el 400 es local y anterior a la guarda: retenido y libre son indistinguibles.
     */
    @Test
    fun aPasswordOverSeventyTwoBytesIsTheSameValidationErrorForAHeldAndAFreeEmail() {
        for (password in overLongPasswords) testApplication {
            val requests = FakeAccessRequests(Row(UID, pendingEmail, "pendiente"))
            val upstream = Upstream()
            application { module(requests, upstream) }
            val label = "${password.length} caracteres, ${password.toByteArray(StandardCharsets.UTF_8).size} bytes"

            val held = post("/v1/auth/register", registerBody(pendingEmail, password))
            val free = post("/v1/auth/register", registerBody("free@example.com", password))
            val heldBody = held.jsonBody()
            val freeBody = free.jsonBody()

            assertEquals(HttpStatusCode.BadRequest, held.status, label)
            assertEquals(HttpStatusCode.BadRequest, free.status, label)
            assertEquals("VALIDATION", heldBody["code"]?.jsonPrimitive?.content, label)
            assertTrue("INVALID_PASSWORD" in heldBody.toString(), "$label: $heldBody")
            // Lo mismo salvo el id de peticion, distinto en cada llamada por construccion.
            assertEquals(JsonObject(freeBody - "requestId"), JsonObject(heldBody - "requestId"), label)
            // Ni la guarda ni GoTrue: se decide antes de las dos.
            assertEquals(emptyList(), requests.paths.toList(), label)
            assertEquals(emptyList(), upstream.paths(), label)
        }
    }

    @Test
    fun aPasswordOfExactlySeventyTwoBytesIsStillAccepted() {
        for (password in listOf("a".repeat(72), "ñ".repeat(36))) testApplication {
            val requests = FakeAccessRequests(Row(UID, pendingEmail, "pendiente"))
            val upstream = Upstream()
            application { module(requests, upstream) }

            val held = post("/v1/auth/register", registerBody(pendingEmail, password))
            val free = post("/v1/auth/register", registerBody("free@example.com", password))

            assertEquals(HttpStatusCode.Accepted, held.status)
            assertEquals(HttpStatusCode.Accepted, free.status)
            assertEquals(held.bodyAsText(), free.bodyAsText())
            // Solo el correo libre llega a GoTrue; el retenido se queda en la guarda.
            assertEquals(listOf("/auth/v1/signup"), upstream.paths())
        }
    }

    @Test
    fun resetPasswordAppliesTheSameSeventyTwoByteCapBeforeSpendingTheToken() {
        for (password in overLongPasswords) testApplication {
            val upstream = Upstream()
            application { module(FakeAccessRequests(), upstream) }

            val response = post("/v1/auth/reset-password", """{"resetToken":"recovery-token-hash","newPassword":"$password"}""")
            val body = response.jsonBody()

            assertEquals(HttpStatusCode.BadRequest, response.status, password)
            assertEquals("VALIDATION", body["code"]?.jsonPrimitive?.content, password)
            assertTrue("INVALID_PASSWORD" in body.toString(), body.toString())
            // El token de recuperacion (un solo uso) no se gasta en una contrasena que GoTrue iba a rechazar.
            assertEquals(emptyList(), upstream.paths(), password)
        }
        testApplication {
            val upstream = Upstream()
            application { module(FakeAccessRequests(), upstream) }

            val response = post("/v1/auth/reset-password", """{"resetToken":"recovery-token-hash","newPassword":"${"ñ".repeat(36)}"}""")

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(listOf("/auth/v1/verify", "/auth/v1/user", "/auth/v1/logout"), upstream.paths())
        }
    }

    /** El login no fija contrasena: conserva su regla (12..128 caracteres) y sigue llegando a GoTrue. */
    @Test
    fun loginKeepsItsOwnPasswordRule() = testApplication {
        val upstream = Upstream()
        application { module(FakeAccessRequests(), upstream) }

        val response = post("/v1/auth/login", registerBody("free@example.com", "a".repeat(100)))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("/auth/v1/token", upstream.paths().first())
    }

    private companion object {
        const val UID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_UID = "44444444-4444-4444-8444-444444444444"

        const val SESSION =
            """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$UID","email":"ada@example.com"}}"""

        /** Un id_token con forma de JWT (3 segmentos, `sub` y `exp` futuros); GoTrue falso no lo verifica. */
        val ID_TOKEN: String = run {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = encoder.encodeToString("""{"sub":"provider-subject","exp":4102444800}""".toByteArray())
            "$header.$payload.signature"
        }

        fun profileRow(estado: String) =
            """[{"usuario_id":"$UID","estado_cuenta":"$estado","onboarding_completado":true}]"""
    }
}
