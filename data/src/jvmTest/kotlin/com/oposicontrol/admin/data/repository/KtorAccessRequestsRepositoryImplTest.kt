package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestCodeOutcome
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El cliente de solicitudes de acceso contra un MockEngine: lo que se ENVIA (ruta, cabeceras, cuerpo) y como se LEE la
 * respuesta. Ningun byte sale de la maquina.
 */
class KtorAccessRequestsRepositoryImplTest {
    private val password = "S3creta-Larga-No-Repetible-9731"
    private val requestId = "77777777-7777-4777-8777-000000000001"

    private fun appConfig() = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "test-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private fun session(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    /** Logger que guarda todo lo que el adaptador de red escribe: sirve para comprobar que la contrasena no llega ahi. */
    private class RecordingLogger : Logger {
        val lines = mutableListOf<String>()
        override fun debug(message: String) { lines += message }
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
        override fun error(message: String, throwable: Throwable?) {
            lines += message
            generateSequence(throwable) { it.cause }.forEach { lines += it.toString() }
        }
    }

    private class Recorded(val request: HttpRequestData, val body: String)

    private fun repository(
        requests: MutableList<Recorded>,
        logger: Logger = RecordingLogger(),
        reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorAccessRequestsRepositoryImpl {
        val client = HttpClient(MockEngine { request ->
            val body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> String(content.bytes())
                else -> ""
            }
            requests += Recorded(request, body)
            reply(request)
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorAccessRequestsRepositoryImpl(client, appConfig(), session(), NetworkResultAdapter(logger))
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(content = ByteReadChannel(body), status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    private fun submission() = AccessRequestSubmission(
        name = "Ada Lovelace",
        email = "ada@example.com",
        reason = "Coordino el equipo de contenido.",
    )

    private fun wire(id: String, estado: String, resolved: Boolean = false) =
        """{"id":"$id","usuarioId":"u-$id","nombre":"Ada Lovelace","email":"ada@example.com","motivo":"Coordino","estado":"$estado",""" +
            """"creadoEn":"2026-10-13T09:00:00Z"${if (resolved) ""","resueltoEn":"2026-10-13T10:00:00Z","resueltoPor":"actor","motivoResolucion":"No procede"""" else ""}}"""

    // ------------------------------------------------------------------------------------------------ envio publico

    @Test
    fun submittingPostsToThePublicAuthRouteWithoutAnyCredentialAndWithoutAnyPassword() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.Accepted, """{"estado":"recibida"}""") }

        val result = repository.submitRequest(submission())

        assertIs<AppResult.Success<Unit>>(result)
        val sent = requests.single()
        assertEquals("POST", sent.request.method.value)
        assertEquals("/v1/auth/admin-access-requests", sent.request.url.encodedPath)
        assertEquals("localhost", sent.request.url.host)
        // Publica: ni el token de sesion ni el token estatico de desarrollo viajan.
        assertNull(sent.request.headers[HttpHeaders.Authorization])
        assertTrue(sent.request.url.parameters.isEmpty(), "nada en la URL")
        val body = Json.parseToJsonElement(sent.body).jsonObject
        // Revision de seguridad 2026-10-14: la solicitud ya no lleva contrasena (se elige al verificar).
        assertEquals(setOf("nombre", "email", "motivo"), body.keys)
        assertEquals("Ada Lovelace", body["nombre"]!!.jsonPrimitive.content)
        assertEquals("ada@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("Coordino el equipo de contenido.", body["motivo"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRejectedSubmissionIsAFailureWithTheServerMessage() = runTest {
        val requests = mutableListOf<Recorded>()
        val logger = RecordingLogger()
        val replies = ArrayDeque(
            listOf(
                HttpStatusCode.TooManyRequests to """{"error":"TemporarilyUnavailable","code":"RATE_LIMITED","message":"Demasiadas peticiones. Vuelve a intentarlo en unos segundos."}""",
                HttpStatusCode.BadRequest to """{"error":"Validation","code":"VALIDATION","message":"Algun dato de la peticion no es valido."}""",
                HttpStatusCode.ServiceUnavailable to """{"error":"TemporarilyUnavailable","code":"UPSTREAM_UNAVAILABLE","message":"El servicio no esta disponible ahora mismo."}""",
            ),
        )
        val repository = repository(requests, logger) { replies.removeFirst().let { (status, body) -> json(status, body) } }

        val limited = repository.submitRequest(submission())
        val invalid = repository.submitRequest(submission())
        val down = repository.submitRequest(submission())

        assertEquals("Demasiadas peticiones. Vuelve a intentarlo en unos segundos.", (assertIs<AppResult.Failure>(limited).error as AppError.Validation).message)
        assertIs<AppError.Validation>(assertIs<AppResult.Failure>(invalid).error)
        assertEquals(503, (assertIs<AppResult.Failure>(down).error as AppError.Network).code)
        assertTrue(logger.lines.isNotEmpty(), "los fallos se registran")
        assertFalse("ada@example.com" in submission().toString())
    }

    // ------------------------------------------------------------------------------------------------ verificacion del correo

    private fun MockRequestHandleScope.limited(retryAfter: String?) = respond(
        content = ByteReadChannel("""{"error":"TemporarilyUnavailable","code":"RATE_LIMITED","message":"Demasiadas peticiones."}"""),
        status = HttpStatusCode.TooManyRequests,
        headers = if (retryAfter == null) {
            headersOf(HttpHeaders.ContentType, "application/json")
        } else {
            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf(retryAfter))
        },
    )

    private val invalidCredential =
        """{"error":"Validation","message":"Algun dato de la peticion no es valido.","code":"VALIDATION","requestId":"r",""" +
            """"details":{"issues":[{"field":"credential","issue":"INVALID_CREDENTIAL"}]}}"""
    private val invalidIdentifier =
        """{"error":"Validation","message":"Algun dato de la peticion no es valido.","code":"VALIDATION","requestId":"r",""" +
            """"details":{"issues":[{"field":"identifier","issue":"INVALID_IDENTIFIER"}]}}"""
    private val invalidPassword =
        """{"error":"Validation","message":"Algun dato de la peticion no es valido.","code":"VALIDATION","requestId":"r",""" +
            """"details":{"issues":[{"field":"password","issue":"INVALID_PASSWORD"}]}}"""

    @Test
    fun verifyingPostsEmailCodeAndChosenPasswordToThePublicRouteAndA204IsAccepted() = runTest {
        val requests = mutableListOf<Recorded>()
        val logger = RecordingLogger()
        val repository = repository(requests, logger) { respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent) }

        val result = repository.verifyEmail("ada@example.com", "123456", password)

        assertEquals(AccessRequestCodeOutcome.Accepted, assertIs<AppResult.Success<AccessRequestCodeOutcome>>(result).value)
        val sent = requests.single()
        assertEquals("POST", sent.request.method.value)
        assertEquals("/v1/auth/admin-access-requests/verify", sent.request.url.encodedPath)
        assertNull(sent.request.headers[HttpHeaders.Authorization])
        assertTrue(sent.request.url.parameters.isEmpty(), "el codigo no viaja en la URL")
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(setOf("email", "code", "password"), body.keys)
        assertEquals("ada@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("123456", body["code"]!!.jsonPrimitive.content)
        assertEquals(password, body["password"]!!.jsonPrimitive.content)
        // La contrasena solo en el cuerpo: ni URL, ni cabeceras, ni log del cliente.
        assertFalse(password in sent.request.url.toString())
        assertFalse(sent.request.headers.entries().any { (_, values) -> values.any { password in it } })
        assertFalse(logger.lines.any { password in it })
    }

    @Test
    fun verifyFailuresAreReadFromTheEnvelopeAndRetryAfter() = runTest {
        val replies = ArrayDeque<MockRequestHandleScope.() -> HttpResponseData>(
            listOf(
                { json(HttpStatusCode.BadRequest, invalidCredential) },
                { json(HttpStatusCode.BadRequest, invalidIdentifier) },
                { json(HttpStatusCode.BadRequest, invalidPassword) },
                { limited("120") },
                { limited(null) },
                { limited("Wed, 21 Oct 2026 07:28:00 GMT") },
                { json(HttpStatusCode.ServiceUnavailable, """{"error":"TemporarilyUnavailable","code":"UPSTREAM_UNAVAILABLE","message":"x"}""") },
            ),
        )
        val repository = repository(mutableListOf()) { replies.removeFirst().invoke(this) }

        fun AppResult<AccessRequestCodeOutcome>.outcome() = assertIs<AppResult.Success<AccessRequestCodeOutcome>>(this).value
        assertEquals(AccessRequestCodeOutcome.InvalidCode, repository.verifyEmail("ada@example.com", "000000", password).outcome())
        assertEquals(AccessRequestCodeOutcome.InvalidEmail, repository.verifyEmail("ada@", "000000", password).outcome())
        assertEquals(AccessRequestCodeOutcome.InvalidPassword, repository.verifyEmail("ada@example.com", "000000", password).outcome())
        assertEquals(AccessRequestCodeOutcome.RateLimited(120), repository.verifyEmail("ada@example.com", "000000", password).outcome())
        assertEquals(AccessRequestCodeOutcome.RateLimited(null), repository.verifyEmail("ada@example.com", "000000", password).outcome())
        assertEquals(AccessRequestCodeOutcome.RateLimited(null), repository.verifyEmail("ada@example.com", "000000", password).outcome())
        val down = repository.verifyEmail("ada@example.com", "000000", password)
        assertEquals(503, (assertIs<AppResult.Failure>(down).error as AppError.Network).code)
    }

    @Test
    fun resendingPostsOnlyTheEmailAndReadsTheSameAnswers() = runTest {
        val requests = mutableListOf<Recorded>()
        val replies = ArrayDeque<MockRequestHandleScope.() -> HttpResponseData>(
            listOf(
                { json(HttpStatusCode.Accepted, """{"estado":"recibida"}""") },
                { json(HttpStatusCode.BadRequest, invalidIdentifier) },
                { limited("30") },
                { json(HttpStatusCode.BadRequest, """{"error":"Validation","code":"VALIDATION","message":"Algun dato de la peticion no es valido."}""") },
            ),
        )
        val repository = repository(requests) { replies.removeFirst().invoke(this) }

        fun AppResult<AccessRequestCodeOutcome>.outcome() = assertIs<AppResult.Success<AccessRequestCodeOutcome>>(this).value
        assertEquals(AccessRequestCodeOutcome.Accepted, repository.resendCode("ada@example.com").outcome())
        assertEquals(AccessRequestCodeOutcome.InvalidEmail, repository.resendCode("ada@").outcome())
        assertEquals(AccessRequestCodeOutcome.RateLimited(30), repository.resendCode("ada@example.com").outcome())
        // Un 400 sin campo conocido sigue el mapeo de siempre.
        assertIs<AppError.Validation>(assertIs<AppResult.Failure>(repository.resendCode("ada@example.com")).error)

        val sent = requests.first()
        assertEquals("/v1/auth/admin-access-requests/resend-code", sent.request.url.encodedPath)
        assertNull(sent.request.headers[HttpHeaders.Authorization])
        assertEquals(setOf("email"), Json.parseToJsonElement(sent.body).jsonObject.keys)
    }

    // ------------------------------------------------------------------------------------------------ revision

    @Test
    fun listingReadsTheVerificationFlagsWithAndWithoutTheVerifiedInstant() = runTest {
        val verified =
            """{"id":"v","estado":"pendiente","creadoEn":"2026-10-13T09:00:00Z","correoVerificado":true,"caducada":false,""" +
                """"correoVerificadoEn":"2026-10-13T09:05:00Z"}"""
        val expired = """{"id":"x","estado":"pendiente","creadoEn":"2026-10-13T07:00:00Z","correoVerificado":false,"caducada":true}"""
        val legacy = """{"id":"l","estado":"pendiente","creadoEn":"2026-10-13T07:00:00Z"}"""
        val repository = repository(mutableListOf()) { json(HttpStatusCode.OK, """{"items":[$verified,$expired,$legacy]}""") }

        val rows = assertIs<AppResult.Success<List<AccessRequest>>>(repository.listRequests()).value

        assertTrue(rows[0].emailVerified)
        assertFalse(rows[0].expired)
        assertEquals("2026-10-13T09:05:00Z", rows[0].emailVerifiedAt)
        assertTrue(rows[0].canBeApproved)
        assertFalse(rows[1].emailVerified)
        assertTrue(rows[1].expired)
        assertNull(rows[1].emailVerifiedAt)
        assertFalse(rows[1].canBeApproved)
        // Sin los campos (servidor antiguo): sin verificar, nunca aprobable desde la pantalla.
        assertFalse(rows[2].emailVerified)
        assertFalse(rows[2].canBeApproved)
    }

    @Test
    fun listingSendsTheSessionCredentialTheFilterAndReadsTheRows() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) {
            json(HttpStatusCode.OK, """{"items":[${wire("a", "pendiente")},${wire("b", "rechazada", resolved = true)},${wire("c", "algo-nuevo")}]}""")
        }

        val all = repository.listRequests()
        repository.listRequests(AccessRequestStatus.REJECTED)

        val rows = assertIs<AppResult.Success<List<AccessRequest>>>(all).value
        // Un estado desconocido se omite: nunca se pinta como pendiente.
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals(AccessRequestStatus.PENDING, rows[0].status)
        assertEquals("Ada Lovelace", rows[0].name)
        assertEquals("ada@example.com", rows[0].email)
        assertEquals("Coordino", rows[0].reason)
        assertEquals("2026-10-13T09:00:00Z", rows[0].createdAt)
        assertNull(rows[0].resolvedAt)
        assertEquals(AccessRequestStatus.REJECTED, rows[1].status)
        assertEquals("No procede", rows[1].resolutionReason)
        assertEquals("actor", rows[1].resolvedBy)

        assertEquals("/api/v1/admin/solicitudes-acceso", requests[0].request.url.encodedPath)
        assertEquals("Bearer test-token", requests[0].request.headers[HttpHeaders.Authorization])
        assertEquals("200", requests[0].request.url.parameters["limit"])
        assertNull(requests[0].request.url.parameters["estado"])
        assertEquals("rechazada", requests[1].request.url.parameters["estado"])
    }

    @Test
    fun aPlainAdminListingIsAnAuthorizationErrorWhichIsWhatHidesTheTab() = runTest {
        val repository = repository(mutableListOf()) {
            json(HttpStatusCode.Forbidden, """{"error":"forbidden","code":"SUPERADMIN_REQUIRED","message":"Esta accion requiere un superadministrador."}""")
        }

        val result = repository.listRequests()

        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
    }

    @Test
    fun approvingNeverSendsARoleAndReadsTheResolvedRequest() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.OK, wire(requestId, "aprobada", resolved = true)) }

        val result = repository.approve(requestId)

        assertEquals(AccessRequestStatus.APPROVED, assertIs<AppResult.Success<AccessRequest>>(result).value.status)
        val sent = requests.single()
        assertEquals("POST", sent.request.method.value)
        assertEquals("/api/v1/admin/solicitudes-acceso/$requestId/aprobar", sent.request.url.encodedPath)
        assertEquals("Bearer test-token", sent.request.headers[HttpHeaders.Authorization])
        assertEquals("", sent.body, "el rol lo decide el servidor: no hay nada que enviar")
    }

    @Test
    fun rejectingSendsTheReasonOnlyWhenThereIsOne() = runTest {
        val requests = mutableListOf<Recorded>()
        val repository = repository(requests) { json(HttpStatusCode.OK, wire(requestId, "rechazada", resolved = true)) }

        val withReason = repository.reject(requestId, "  No pertenece al equipo.  ")
        repository.reject(requestId, "   ")
        repository.reject(requestId, null)

        assertEquals(AccessRequestStatus.REJECTED, assertIs<AppResult.Success<AccessRequest>>(withReason).value.status)
        assertEquals("/api/v1/admin/solicitudes-acceso/$requestId/rechazar", requests[0].request.url.encodedPath)
        assertEquals("No pertenece al equipo.", Json.parseToJsonElement(requests[0].body).jsonObject["motivo"]!!.jsonPrimitive.content)
        assertEquals("", requests[1].body)
        assertEquals("", requests[2].body)
    }

    @Test
    fun aConflictOrAMissingRequestIsReportedAsItsOwnError() = runTest {
        val replies = ArrayDeque(
            listOf(
                HttpStatusCode.Conflict to """{"error":"conflict","code":"CONFLICT","message":"La solicitud ya fue rechazada."}""",
                HttpStatusCode.NotFound to """{"error":"not_found","code":"NOT_FOUND","message":"La solicitud no existe."}""",
            ),
        )
        val repository = repository(mutableListOf()) { replies.removeFirst().let { (status, body) -> json(status, body) } }

        val conflict = repository.approve(requestId)
        val missing = repository.reject(requestId, null)

        assertEquals("La solicitud ya fue rechazada.", (assertIs<AppResult.Failure>(conflict).error as AppError.Validation).message)
        assertIs<AppError.NotFound>(assertIs<AppResult.Failure>(missing).error)
    }
}
