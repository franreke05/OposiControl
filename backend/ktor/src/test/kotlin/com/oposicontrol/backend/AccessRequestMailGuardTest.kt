package com.oposicontrol.backend

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B1 (2026-10-13): una cuenta creada por `POST /v1/auth/admin-access-requests` no puede confirmarse sola por las rutas de
 * consumidor que envian correo. Recorre ruta -> guarda -> lectura PostgREST (falsa, graba las rutas pedidas) y ruta ->
 * gateway REAL -> GoTrue (falso, graba las peticiones): con una solicitud no aprobada no sale NINGUNA peticion a GoTrue y
 * la respuesta es byte a byte la del exito normal.
 */
class AccessRequestMailGuardTest {
    private val pendingEmail = "ada@example.com"

    /** `solicitudes_acceso_admin` falsa: aplica los dos filtros de la consulta real (`email=eq.`, `estado=neq.`). */
    private class FakeAccessRequests(vararg rows: Pair<String, String>) {
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
            val email = query.getValue("email").removePrefix("eq.")
            val notEstado = query.getValue("estado").removePrefix("neq.")
            check(query.getValue("email").startsWith("eq.") && query.getValue("estado").startsWith("neq."))
            return rows.filter { (rowEmail, estado) -> rowEmail == email && estado != notEstado }
                .take(query.getValue("limit").toInt())
                .joinToString(",", "[", "]") { (_, estado) -> """{"estado":"$estado"}""" }
        }
    }

    /** GoTrue falso detras del gateway real: graba cada peticion y contesta 200. */
    private class RecordingGoTrue {
        val paths = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            val body = if (request.url.encodedPath == "/auth/v1/signup") """{"user":{"id":"user"}}""" else "{}"
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    private fun Application.module(
        requests: FakeAccessRequests,
        goTrue: RecordingGoTrue,
        limiter: OposiBotsAuthRateLimiter = TestAllowAllLimiter,
    ) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        val configuration = testCanonicalConfiguration()
        val runtime = OposiBotsAuthRuntime(
            configuration = configuration,
            gateway = authGatewayWithoutAccessRequests(configuration, testJson, goTrue.client),
            rateLimiter = limiter,
            json = testJson,
            canonical = CanonicalTestEnv().runtime().canonical,
            accessRequestMailGuard = PostgrestAccessRequestMailGuard(upstream = requests::get, emailRef = testCanonicalConfiguration()::authLogEmailRef),
        )
        routing { oposiBotsAuthRoutes(runtime) }
    }

    private data class MailRoute(val path: String, val goTruePath: String, val body: (String) -> String)

    private val mailRoutes = listOf(
        MailRoute("/v1/auth/register", "/auth/v1/signup") { """{"email":"$it","password":"S3creta-Larga-9731"}""" },
        MailRoute("/v1/auth/email/resend", "/auth/v1/resend") { """{"email":"$it"}""" },
        MailRoute("/v1/auth/recover", "/auth/v1/recover") { """{"email":"$it"}""" },
    )

    private suspend fun ApplicationTestBuilder.call(route: MailRoute, email: String): HttpResponse =
        client.post(route.path) {
            contentType(ContentType.Application.Json)
            setBody(route.body(email))
        }

    /** Todo lo que ve el cliente salvo el id de peticion, que es distinto en cada llamada por construccion. */
    private suspend fun HttpResponse.wire(): Triple<HttpStatusCode, List<Byte>, Map<String, List<String>>> = Triple(
        status,
        readRawBytes().toList(),
        headers.entries().filterNot { it.key.equals("X-Request-Id", ignoreCase = true) }.associate { it.key to it.value },
    )

    private fun <T> capturingAllLogs(block: (ListAppender<ILoggingEvent>) -> T): T {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        return try {
            block(appender)
        } finally {
            root.detachAppender(appender)
        }
    }

    private fun ListAppender<ILoggingEvent>.everything(): String = list.joinToString("\n") { event ->
        event.formattedMessage + " " + event.argumentArray?.joinToString(" ").orEmpty() + " " +
            generateSequence(event.throwableProxy) { it.cause }.joinToString(" ") { "${it.className}: ${it.message}" }
    }

    // ------------------------------------------------------------------------------------------------ bloquea

    @Test
    fun aPendingRequestHoldsTheMailOnTheThreeRoutesWithTheNormalSuccessAnswer() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests(pendingEmail to "pendiente")
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue) }

            val normal = call(route, "free@example.com")
            assertEquals(listOf(route.goTruePath), goTrue.paths.toList(), route.path)
            val held = call(route, pendingEmail)

            // Ni una peticion mas a GoTrue: sigue solo la del correo sin solicitud.
            assertEquals(listOf(route.goTruePath), goTrue.paths.toList(), route.path)
            assertEquals(2, requests.paths.size, route.path)
            // Byte a byte la misma respuesta: no revela que exista una solicitud.
            val normalWire = normal.wire()
            val heldWire = held.wire()
            assertEquals(normalWire.first, heldWire.first, route.path)
            assertContentEquals(normalWire.second, heldWire.second, route.path)
            assertEquals(normalWire.third, heldWire.third, route.path)
        }
    }

    @Test
    fun theHeldAnswersAreTheDocumentedSuccessShapes() = testApplication {
        val requests = FakeAccessRequests(pendingEmail to "pendiente")
        val goTrue = RecordingGoTrue()
        application { module(requests, goTrue) }

        val register = call(mailRoutes[0], pendingEmail)
        val resend = call(mailRoutes[1], pendingEmail)
        val recover = call(mailRoutes[2], pendingEmail)

        assertEquals(HttpStatusCode.Accepted, register.status)
        assertEquals("""{"verificationRequired":true}""", register.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, resend.status)
        assertEquals("", resend.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, recover.status)
        assertEquals("", recover.bodyAsText())
        assertEquals(emptyList(), goTrue.paths.toList())
    }

    @Test
    fun aRejectedRequestWhoseAccountWasNotRemovedStillHoldsTheMail() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests(pendingEmail to "rechazada")
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue) }

            val response = call(route, pendingEmail)

            assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, route.path)
            assertEquals(emptyList(), goTrue.paths.toList(), route.path)
        }
    }

    @Test
    fun theLookupUsesTheStoredNormalisationTrimmedAndLowercased() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests(pendingEmail to "pendiente")
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue) }

            call(route, "  Ada@EXAMPLE.com ")

            assertEquals(
                "/rest/v1/solicitudes_acceso_admin?select=estado&email=eq.ada%40example.com&estado=neq.aprobada&limit=1",
                requests.paths.single(),
                route.path,
            )
            assertEquals(emptyList(), goTrue.paths.toList(), route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ no bloquea

    @Test
    fun anApprovedRequestOrNoRequestCallsGoTrueAsBefore() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests("approved@example.com" to "aprobada", pendingEmail to "pendiente")
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue) }

            val approved = call(route, "approved@example.com")
            val none = call(route, "nobody@example.com")

            assertEquals(listOf(route.goTruePath, route.goTruePath), goTrue.paths.toList(), route.path)
            assertEquals(approved.status, none.status, route.path)
            assertTrue(approved.status == HttpStatusCode.Accepted || approved.status == HttpStatusCode.NoContent, route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ falla cerrado

    @Test
    fun aFailedLookupAnswers503UpstreamUnavailableAndNeverCallsGoTrue() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests().apply { failure = IllegalStateException("Supabase GET -> 500 for $pendingEmail") }
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue) }

            val response = call(route, "nobody@example.com")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, route.path)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("UPSTREAM_UNAVAILABLE", body["code"]?.jsonPrimitive?.content, route.path)
            assertEquals(emptyList(), goTrue.paths.toList(), route.path)
        }
    }

    @Test
    fun anUnreadableLookupBodyAlsoFailsClosed() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests().apply { rawBody = """{"message":"not a list"}""" }
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue) }

            val response = call(route, "nobody@example.com")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, route.path)
            assertEquals(emptyList(), goTrue.paths.toList(), route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ orden y logs

    @Test
    fun theSilentPerEmailLimitStillWinsAndSkipsTheLookup() {
        for (route in mailRoutes) testApplication {
            val requests = FakeAccessRequests(pendingEmail to "pendiente")
            val goTrue = RecordingGoTrue()
            application { module(requests, goTrue, limiter = TestCountingLimiter(subjectAllowed = false)) }

            val response = call(route, "nobody@example.com")

            assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, route.path)
            assertEquals(emptyList(), requests.paths.toList(), route.path)
            assertEquals(emptyList(), goTrue.paths.toList(), route.path)
        }
    }

    @Test
    fun neitherTheHoldNorTheFailureWritesTheEmailInTheLogs() {
        val logs = capturingAllLogs { appender ->
            testApplication {
                val requests = FakeAccessRequests(pendingEmail to "pendiente")
                val goTrue = RecordingGoTrue()
                application { module(requests, goTrue) }
                for (route in mailRoutes) call(route, pendingEmail)
                requests.failure = IllegalStateException("Supabase GET -> 500 for $pendingEmail")
                for (route in mailRoutes) call(route, pendingEmail)
            }
            appender.everything()
        }

        assertFalse(pendingEmail in logs, logs)
        assertTrue("held by a non-approved admin access request" in logs, logs)
        assertTrue("access request mail guard lookup failed" in logs, logs)
        // Seudonimo CON CLAVE (HMAC con el secreto del limitador), no el prefijo SHA-256 sin clave de antes.
        assertTrue("emailRef=${testCanonicalConfiguration().authLogEmailRef(pendingEmail)}" in logs, logs)
        assertFalse(adminAccessEmailRef(pendingEmail) in logs, logs)
    }

    @Test
    fun theLogPseudonymDependsOnTheSecretAndOnTheNormalisedEmail() {
        val configuration = testCanonicalConfiguration()
        val ref = configuration.authLogEmailRef(pendingEmail)

        assertEquals(12, ref.length)
        assertEquals(ref, configuration.authLogEmailRef("  ADA@example.com "))
        assertFalse(ref == configuration.copy(rateLimitHmacSecret = "another-rate-limit-secret-32-chars!!").authLogEmailRef(pendingEmail))
        assertFalse(ref == adminAccessEmailRef(pendingEmail))
        // Separado de las claves del limitador: no es el HMAC del correo a secas.
        assertFalse(ref == RateLimitKeyHasher(configuration.rateLimitHmacSecret).hash(pendingEmail).take(12))
    }

    // ------------------------------------------------------------------------------------------------ la guarda sola

    @Test
    fun theLookupPathEncodesTheEmailSoItCannotAddFilters() {
        val path = accessRequestHoldPath("a+b&estado=eq.aprobada@x.io")

        assertEquals(
            "/rest/v1/solicitudes_acceso_admin?select=estado&email=eq.a%2Bb%26estado%3Deq.aprobada%40x.io&estado=neq.aprobada&limit=1",
            path,
        )
    }

    /** Un correo con espacios (ASCII o Unicode) es un 400 de identificador invalido, nunca un 503 + WARN, y no se consulta nada. */
    @Test
    fun aMalformedEmailNeverReachesTheUpstreamAndIsAValidationError() = runTest {
        for (email in listOf("a b@example.com", "a b@example.com", "a b@example.com", "ab", "a".repeat(321))) {
            val requests = FakeAccessRequests(pendingEmail to "pendiente")
            val guard = PostgrestAccessRequestMailGuard(upstream = requests::get, emailRef = testCanonicalConfiguration()::authLogEmailRef)

            val problem = assertFailsWith<OposiBotsAuthProblem> { guard.holdsMail(email) }

            assertEquals(HttpStatusCode.BadRequest, problem.status, email)
            assertEquals(CanonicalErrorCode.VALIDATION, problem.canonicalCode(), email)
            assertEquals(setOf(OposiBotsValidationIssue.INVALID_IDENTIFIER), problem.issues, email)
            assertEquals(emptyList(), requests.paths.toList(), email)
        }
    }

    /**
     * Un espacio Unicode pasaba la expresion de la ruta (`\s` solo cubre ASCII) y acababa en la guarda como 503 + WARN.
     * Ahora las tres rutas contestan el 400 de siempre ANTES de la guarda, tenga o no solicitud el correo: ni lectura, ni
     * GoTrue, ni linea de fallo.
     */
    @Test
    fun anEmailWithUnicodeWhitespaceIsTheUsualValidationErrorOnTheThreeRoutes() {
        val logs = capturingAllLogs { appender ->
            for (route in mailRoutes) testApplication {
                val requests = FakeAccessRequests("a da@example.com" to "pendiente")
                val goTrue = RecordingGoTrue()
                application { module(requests, goTrue) }

                val response = call(route, "a da@example.com")
                val asciiSpace = call(route, "a da@example.com")

                assertEquals(HttpStatusCode.BadRequest, response.status, route.path)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("VALIDATION", body["code"]?.jsonPrimitive?.content, route.path)
                assertTrue("INVALID_IDENTIFIER" in body.toString(), route.path)
                // Lo mismo que el espacio ASCII, que la ruta ya rechazaba.
                assertEquals(asciiSpace.status, response.status, route.path)
                assertEquals(emptyList(), requests.paths.toList(), route.path)
                assertEquals(emptyList(), goTrue.paths.toList(), route.path)
            }
            appender.everything()
        }

        assertFalse("lookup failed" in logs, logs)
    }

    // ------------------------------------------------------------------------------------------------ cableado

    /**
     * El runtime que monta `fromEnvironment` (todo menos leer el entorno) usa la guarda REAL en los dos sitios: la de
     * correo del runtime y la puerta de sesion del gateway son la MISMA instancia de [PostgrestAccessRequestMailGuard].
     * No abre ninguna conexion al construirse.
     */
    @Test
    fun theProductionWiringRoutesBothTheMailGuardAndTheSessionGateThroughTheRealGuard() {
        val runtime = OposiBotsAuthRuntime.fromConfiguration(testCanonicalConfiguration(), testJson)
        try {
            val mailGuard = runtime.accessRequestMailGuard
            val sessionGate = (runtime.gateway as SupabaseOposiBotsAuthGateway).accessRequestSessionGate

            assertTrue(mailGuard is PostgrestAccessRequestMailGuard, mailGuard::class.toString())
            assertTrue(sessionGate is PostgrestAccessRequestMailGuard, sessionGate::class.toString())
            assertTrue(mailGuard === sessionGate, "una sola guarda real para el correo y para la sesion")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun theProductionFactoryBuildsThePostgrestGuard() {
        val guard: Any = OposiBotsAuthRuntime.accessRequestMailGuard(testCanonicalConfiguration())

        assertTrue(guard is AccessRequestMailGuard && guard is AccessRequestSessionGate)
    }
}
