package com.oposicontrol.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * MASTER7-IDEM-001: si la respuesta del `POST /auth/v1/admin/users` se pierde (transporte/timeout o 5xx despues de que
 * GoTrue confirmara), el alta reconcilia por email y borra la identidad fantasma SOLO si es inequivoca (email exacto,
 * creada tras el inicio del alta, sin fila `usuario`). Siempre relanza el error original; nunca adopta la identidad.
 */
class OposiBotsUserAdminGatewayLostAuthResponseTest {
    private val email = "ghost.user@example.com"
    private val ghost = "55555555-5555-4555-8555-555555555555"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Harness(val requests: MutableList<HttpRequestData>, val gateway: SupabaseOposiBotsUserAdminGateway) {
        fun trace() = requests.map { "${it.method.value} ${it.url.encodedPath}" }
        fun deletes() = requests.filter { it.method == HttpMethod.Delete }
    }

    private fun identity(id: String = ghost, mail: String = email, createdAt: Instant = Instant.now().plusSeconds(1)) =
        """{"id":"$id","email":"$mail","created_at":"$createdAt"}"""

    private fun harness(
        postStatus: HttpStatusCode? = null,
        postBody: String = """{"msg":"boom"}""",
        lookupStatus: HttpStatusCode = HttpStatusCode.OK,
        users: List<String> = listOf(identity()),
        usuarioRows: String = "[]",
        deleteStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Harness {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/auth/v1/admin/users" ->
                    if (postStatus == null) throw IOException("read timed out") else respond(ByteReadChannel(postBody), postStatus, jsonHeaders)
                request.method == HttpMethod.Get && path == "/auth/v1/admin/users" ->
                    respond(ByteReadChannel(if (lookupStatus == HttpStatusCode.OK) """{"users":[${users.joinToString(",")}],"aud":"authenticated"}""" else """{"msg":"down"}"""), lookupStatus, jsonHeaders)
                request.method == HttpMethod.Get && path == "/rest/v1/usuario" -> respond(ByteReadChannel(usuarioRows), HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Delete -> respond(ByteReadChannel("{}"), deleteStatus, jsonHeaders)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        return Harness(requests, SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client))
    }

    private suspend fun Harness.create(): Throwable = assertFailsWith<Throwable> {
        gateway.createUser(OposiBotsUserAdminCreateRequest(email, "pw-not-logged-123", "Ghost", "User", null, "Sanidad"))
    }

    private val reconcileTrace = listOf(
        "POST /auth/v1/admin/users",
        "GET /auth/v1/admin/users",
        "GET /rest/v1/usuario",
        "DELETE /auth/v1/admin/users/$ghost",
    )

    @Test
    fun aLostResponseOnTransportFailureDeletesTheUnambiguousGhostAndRethrowsTheOriginalError() = runTest {
        val h = harness()
        val failure = h.create()
        assertIs<IllegalStateException>(failure)
        assertFalse(failure is SupabaseHttpStatusException)
        assertTrue("network error" in failure.message.orEmpty(), failure.message)
        assertEquals(reconcileTrace, h.trace())
        val lookup = h.requests[1]
        assertEquals(email, lookup.url.parameters["filter"])
        assertEquals("eq.$ghost", h.requests[2].url.parameters["auth_user_id"])
    }

    @Test
    fun a5xxAfterTheAuthPostAlsoReconcilesAndKeepsThe5xx() = runTest {
        val h = harness(postStatus = HttpStatusCode.ServiceUnavailable)
        val failure = h.create()
        assertEquals(503, assertIs<SupabaseHttpStatusException>(failure).status)
        assertEquals(reconcileTrace, h.trace())
    }

    @Test
    fun anAlreadyDeletedGhostIsFineAndTheOriginalErrorStillPropagates() = runTest {
        val h = harness(deleteStatus = HttpStatusCode.NotFound)
        assertTrue("network error" in h.create().message.orEmpty())
        assertEquals(reconcileTrace, h.trace())
    }

    @Test
    fun aFailedGhostDeletionNamesTheAuthUserIdForManualCleanup() = runTest {
        val h = harness(deleteStatus = HttpStatusCode.InternalServerError)
        val failure = assertIs<IllegalStateException>(h.create())
        assertTrue(ghost in failure.message.orEmpty(), failure.message)
        assertEquals(reconcileTrace, h.trace())
    }

    @Test
    fun anIdentityCreatedBeforeTheAttemptIsNeverDeleted() = runTest {
        val h = harness(users = listOf(identity(createdAt = Instant.now().minusSeconds(3600))))
        h.create()
        assertEquals(0, h.deletes().size)
    }

    @Test
    fun anIdentityLinkedToAUsuarioRowIsNeverDeleted() = runTest {
        val h = harness(usuarioRows = """[{"id":7}]""")
        h.create()
        assertEquals(0, h.deletes().size)
    }

    @Test
    fun ambiguousOrInexactMatchesAreNeverDeleted() = runTest {
        val two = harness(users = listOf(identity(), identity(id = "66666666-6666-4666-8666-666666666666")))
        two.create()
        assertEquals(0, two.deletes().size)

        val substring = harness(users = listOf(identity(mail = "x.$email")))
        substring.create()
        assertEquals(0, substring.deletes().size)

        val none = harness(users = emptyList())
        none.create()
        assertEquals(0, none.deletes().size)
    }

    @Test
    fun aFailedLookupDeletesNothingAndTheOriginalErrorPropagates() = runTest {
        val h = harness(lookupStatus = HttpStatusCode.InternalServerError)
        val failure = h.create()
        assertTrue("network error" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf("POST /auth/v1/admin/users", "GET /auth/v1/admin/users"), h.trace())
    }

    @Test
    fun aDefiniteGoTrueRejectionNeverTriggersALookup() = runTest {
        val dup = harness(postStatus = HttpStatusCode.UnprocessableEntity, postBody = """{"error_code":"email_exists"}""")
        assertEquals("CONFLICT", assertIs<BackendDomainException>(dup.create()).errorCode)
        assertEquals(listOf("POST /auth/v1/admin/users"), dup.trace())

        val weak = harness(postStatus = HttpStatusCode.UnprocessableEntity, postBody = """{"error_code":"weak_password"}""")
        assertEquals("VALIDATION", assertIs<BackendDomainException>(weak.create()).errorCode)
        assertEquals(listOf("POST /auth/v1/admin/users"), weak.trace())

        val conflict = harness(postStatus = HttpStatusCode.Conflict)
        assertEquals("CONFLICT", assertIs<BackendDomainException>(conflict.create()).errorCode)
        assertEquals(listOf("POST /auth/v1/admin/users"), conflict.trace())
    }

    @Test
    fun reconciliationLogsNeverContainThePlainEmailOrPassword() = runTest {
        val logger = LoggerFactory.getLogger("OC-KTOR-USER-ADMIN") as LogbackLogger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        logger.level = Level.DEBUG
        try {
            harness().create()
            harness(lookupStatus = HttpStatusCode.InternalServerError).create()
            harness(users = listOf(identity(createdAt = Instant.now().minusSeconds(3600)))).create()
            val lines = appender.list.map { it.formattedMessage }
            assertTrue(lines.size >= 3, lines.toString())
            lines.forEach { line ->
                assertFalse(email in line, line)
                assertFalse(email.substringBefore('@') in line, line)
                assertFalse("pw-not-logged-123" in line, line)
                assertTrue("emailRef=" in line, line)
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
    }
}
