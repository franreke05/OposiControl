package com.oposicontrol.backend

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Solicitud PUBLICA de acceso de administrador (`POST /v1/auth/admin-access-requests`): misma respuesta exista o no el
 * correo, validacion antes de tocar Supabase, limite por IP y tope silencioso por email, y la contrasena fuera de toda
 * respuesta y de todo log.
 */
class AdminAccessRequestPublicRouteTest {
    private val password = "S3creta-Larga-No-Repetible-9731"

    private class FakeGateway(var outcome: AdminAccessSubmitOutcome = AdminAccessSubmitOutcome.CREATED) : UnusedAdminAccessRequestGateway() {
        val submissions = CopyOnWriteArrayList<AdminAccessSubmission>()
        val codeMails = CopyOnWriteArrayList<String>()
        var failure: Exception? = null

        override suspend fun submit(submission: AdminAccessSubmission): AdminAccessSubmitOutcome {
            submissions += submission
            failure?.let { throw it }
            return outcome
        }

        /** Ninguna solicitud previa para el correo: el alta sigue. */
        override suspend fun nonApprovedByEmail(email: String): List<AccessRequestReservation> = emptyList()

        override suspend fun sendAccessCode(email: String) {
            codeMails += email
        }
    }

    private fun Application.module(gateway: AdminAccessRequestGateway?, limiter: OposiBotsAuthRateLimiter = TestAllowAllLimiter) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        val runtime = CanonicalTestEnv(limiter = limiter).runtime()
        routing { adminAccessRequestPublicRoute(runtime, gateway) }
    }

    private fun body(
        nombre: String = "  Ada Lovelace ",
        email: String = " Ada@Example.com ",
        motivo: String = "Coordino el equipo de contenido de Enfermeria.",
        legacyPassword: String? = null,
    ) = """{"nombre":${JsonPrimitive(nombre)},"email":"$email",""" +
        legacyPassword.orEmpty().let { if (legacyPassword == null) "" else """"password":${JsonPrimitive(it)},""" } +
        """"motivo":${JsonPrimitive(motivo)}}"""

    private suspend fun ApplicationTestBuilder.submit(payload: String, type: ContentType = ContentType.Application.Json): HttpResponse =
        client.post("/v1/auth/admin-access-requests") {
            contentType(type)
            setBody(payload)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

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

    // ------------------------------------------------------------------------------------------------ camino feliz

    @Test
    fun aValidRequestIsAcceptedAndReachesTheGatewayNormalised() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway) }

        val response = submit(body())

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals("recibida", response.json()["estado"]!!.jsonPrimitive.content)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val sent = gateway.submissions.single()
        assertEquals("Ada Lovelace", sent.nombre)
        assertEquals("ada@example.com", sent.email)
        assertEquals("Coordino el equipo de contenido de Enfermeria.", sent.motivo)
        assertFalse(password in response.bodyAsText())
        // Cuenta creada: se pide a GoTrue EXACTAMENTE un correo con el codigo, al correo canonico.
        assertEquals(listOf("ada@example.com"), gateway.codeMails.toList())
    }

    /** Sin alta nueva (duplicado, rechazo de Auth) no sale ningun correo con codigo. */
    @Test
    fun onlyACreatedRequestTriggersTheCodeMail() = testApplication {
        val gateway = FakeGateway(AdminAccessSubmitOutcome.ALREADY_REGISTERED)
        application { module(gateway) }

        submit(body())
        gateway.outcome = AdminAccessSubmitOutcome.REJECTED_BY_AUTH
        submit(body())

        assertEquals(2, gateway.submissions.size)
        assertTrue(gateway.codeMails.isEmpty())
    }

    /**
     * Revision 2026-10-14 (DO_NOT_SHIP): el alta ya no lleva contrasena. Un campo `password` de un cliente antiguo se IGNORA
     * como cualquier campo desconocido del plano de auth (valido o no): misma 202 y no llega a ningun sitio.
     */
    @Test
    fun aLegacyPasswordFieldIsIgnoredAndNeverReachesTheGateway() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway) }

        for (legacy in listOf(password, "a".repeat(11), "ñ".repeat(12), "")) {
            val response = submit(body(legacyPassword = legacy))
            assertEquals(HttpStatusCode.Accepted, response.status, legacy)
            assertEquals("recibida", response.json()["estado"]!!.jsonPrimitive.content)
        }
        assertEquals(4, gateway.submissions.size)
        assertTrue(gateway.submissions.none { password in it.nombre || password in it.email || password in it.motivo })
    }

    // ------------------------------------------------------------------------------------------------ sin enumeracion

    @Test
    fun anAlreadyRegisteredEmailGetsExactlyTheSameAnswer() = testApplication {
        val fresh = FakeGateway(AdminAccessSubmitOutcome.CREATED)
        application { module(fresh) }
        val created = submit(body())
        val createdBody = created.bodyAsText()

        fresh.outcome = AdminAccessSubmitOutcome.ALREADY_REGISTERED
        val duplicate = submit(body())
        fresh.outcome = AdminAccessSubmitOutcome.REJECTED_BY_AUTH
        val rejected = submit(body())

        listOf(duplicate, rejected).forEach { other ->
            assertEquals(created.status, other.status)
            assertEquals(createdBody, other.bodyAsText())
            assertEquals(created.headers[HttpHeaders.CacheControl], other.headers[HttpHeaders.CacheControl])
            assertEquals(created.headers[HttpHeaders.RetryAfter], other.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun theEmailCeilingIsSilentSameAnswerWithoutCallingSupabase() = testApplication {
        val gateway = FakeGateway()
        val limiter = TestCountingLimiter(subjectAllowed = false)
        application { module(gateway, limiter) }

        val response = submit(body())

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("recibida", response.json()["estado"]!!.jsonPrimitive.content)
        assertTrue(gateway.submissions.isEmpty(), "superado el tope por email no se crea nada")
        assertEquals(listOf(OposiBotsAuthOperation.ADMIN_ACCESS_REQUEST to "ada@example.com"), limiter.subjectChecks.toList())
    }

    // ------------------------------------------------------------------------------------------------ validacion

    @Test
    fun invalidDataIsRejectedBeforeTheGatewayAndNamesTheField() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway) }

        suspend fun issuesOf(payload: String): List<Pair<String, String>> {
            val response = submit(payload)
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val json = response.json()
            assertEquals("VALIDATION", json["code"]!!.jsonPrimitive.content)
            assertFalse(password in response.bodyAsText())
            return json["details"]!!.jsonObject["issues"]!!.jsonArray.map {
                it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["issue"]!!.jsonPrimitive.content
            }
        }

        assertEquals(listOf("nombre" to "REQUIRED"), issuesOf(body(nombre = "   ")))
        assertEquals(listOf("nombre" to "OUT_OF_RANGE"), issuesOf(body(nombre = "a".repeat(121))))
        assertEquals(listOf("nombre" to "INVALID_FORMAT"), issuesOf(body(nombre = "Ada‮evil")))
        assertEquals(listOf("motivo" to "REQUIRED"), issuesOf(body(motivo = "")))
        assertEquals(listOf("motivo" to "OUT_OF_RANGE"), issuesOf(body(motivo = "m".repeat(1_001))))
        assertEquals(listOf("identifier" to "INVALID_IDENTIFIER"), issuesOf(body(email = "sin-arroba")))
        assertEquals(listOf("identifier" to "BLANK_IDENTIFIER"), issuesOf(body(email = "")))
        assertTrue(gateway.submissions.isEmpty(), "nada invalido llega a Supabase")
    }

    @Test
    fun theLimitsAreInclusiveAndMultilineReasonsAreAccepted() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway) }

        assertEquals(HttpStatusCode.Accepted, submit(body(nombre = "a".repeat(120), motivo = "m".repeat(1_000))).status)
        assertEquals(HttpStatusCode.Accepted, submit(body(motivo = "Linea uno\nLinea dos\tcon tabulador")).status)
        assertEquals(2, gateway.submissions.size)
    }

    @Test
    fun aNonJsonOrOversizedBodyIsRejectedWithoutReadingItAll() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway) }

        assertEquals(HttpStatusCode.BadRequest, submit(body(), ContentType.Text.Plain).status)
        assertEquals(HttpStatusCode.BadRequest, submit("no es json").status)
        // 16 KB es el tope del plano de auth: un motivo gigante ni se decodifica.
        assertEquals(HttpStatusCode.BadRequest, submit(body(motivo = "m".repeat(17_000))).status)
        assertTrue(gateway.submissions.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ limite por IP

    @Test
    fun theIpLimitAnswers429WithRetryAfterBeforeAnythingElse() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway, TestCountingLimiter(ipAllowed = false)) }

        val response = submit(body())

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        assertEquals("RATE_LIMITED", response.json()["code"]!!.jsonPrimitive.content)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun theRealLimiterAllowsFiveRequestsPerIpAndHourAndThenStops() = testApplication {
        val gateway = FakeGateway()
        application { module(gateway, InMemoryOposiBotsAuthRateLimiter()) }

        // Correos distintos: lo que se agota es el cupo de la IP (5), no el del correo (3).
        val statuses = (1..7).map { submit(body(email = "persona$it@example.com")).status }

        assertEquals(List(5) { HttpStatusCode.Accepted } + List(2) { HttpStatusCode.TooManyRequests }, statuses)
        assertEquals(5, gateway.submissions.size)
    }

    @Test
    fun theOperationIsAsStrictAsRegistration() {
        val operation = OposiBotsAuthOperation.ADMIN_ACCESS_REQUEST
        assertTrue(operation.ipLimit <= OposiBotsAuthOperation.REGISTER.ipLimit)
        assertTrue(operation.subjectLimit <= OposiBotsAuthOperation.REGISTER.subjectLimit)
        assertTrue(operation.windowSeconds >= OposiBotsAuthOperation.REGISTER.windowSeconds)
    }

    // ------------------------------------------------------------------------------------------------ fallos y secretos

    @Test
    fun withoutAGatewayTheRouteFailsClosed() = testApplication {
        application { module(gateway = null) }
        assertEquals(HttpStatusCode.ServiceUnavailable, submit(body()).status)
    }

    @Test
    fun thePasswordNeverAppearsInResponsesNorInLogsWhateverHappens() {
        val logs = capturingAllLogs { appender ->
            testApplication {
                val gateway = FakeGateway()
                application { module(gateway) }

                val accepted = submit(body(legacyPassword = password))
                gateway.outcome = AdminAccessSubmitOutcome.ALREADY_REGISTERED
                val duplicate = submit(body(legacyPassword = password))
                // Un fallo del upstream cuyo mensaje no controla esta ruta: sale 503 y nada del cuerpo.
                gateway.failure = IllegalStateException("Supabase network error on POST /auth/v1/admin/users: ConnectException")
                val failed = submit(body(legacyPassword = password))
                val invalid = submit(body(nombre = "", legacyPassword = password))

                assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
                listOf(accepted, duplicate, failed, invalid).forEach { response ->
                    assertFalse(password in response.bodyAsText(), "la contrasena no vuelve en ${response.status}")
                    assertFalse(response.headers.entries().any { (_, values) -> values.any { password in it } })
                }
            }
            appender.everything()
        }
        assertTrue("admin access request outcome=CREATED" in logs, "la prueba tiene que estar leyendo los logs de la ruta")
        assertFalse(password in logs, "la contrasena no se escribe en ningun log")
        assertFalse("ada@example.com" in logs, "ni el correo: solo un prefijo de su hash")
    }

    @Test
    fun theRequestObjectsDoNotPrintTheirPassword() {
        val body = AdminAccessRequestBody(nombre = "Ada", email = "ada@example.com", motivo = "m")
        assertFalse("ada@example.com" in body.toString())
        assertFalse("ada@example.com" in body.validated().toString())
        val verify = AdminAccessVerifyBody(email = "ada@example.com", code = "123456", password = password)
        assertFalse(password in verify.toString())
        assertFalse("123456" in verify.toString())
    }
}
