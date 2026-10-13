package com.oposicontrol.backend

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class AuditLogAppender : AppenderBase<ILoggingEvent>() {
    val lines = CopyOnWriteArrayList<String>()
    override fun append(event: ILoggingEvent) {
        lines += event.formattedMessage
    }
}

private fun <T> captureLogs(loggerNames: List<String>, block: () -> T): Pair<T, List<String>> {
    val appender = AuditLogAppender().apply { start() }
    val loggers = loggerNames.map { LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
    loggers.forEach { it.addAppender(appender) }
    try {
        return block() to appender.lines.toList().let { appender.lines.toList() }
    } finally {
        loggers.forEach { it.detachAppender(appender) }
    }
}

private fun auditGatewayConfiguration() = testCanonicalConfiguration()

private val auditJson = Json { ignoreUnknownKeys = true }

/** MASTER-AUTH-007, ARCH-001, OBS-003/004/006, ARCH-006, ARCH-007 (KTOR_REST part 1). */
class AuditRestPart1ResilienceTest {

    // ---------------- AUTH-007: sin oraculo de existencia en resend/recover ----------------

    private fun goTrueThrottlingKnownEmails(): SupabaseOposiBotsAuthGateway {
        val client = HttpClient(MockEngine { request ->
            val body = (request.body as io.ktor.http.content.TextContent).text
            if ("known@example.com" in body) {
                respond(ByteReadChannel("""{"code":429,"error_code":"over_email_send_rate_limit","msg":"only once every 60 seconds"}"""), HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(ByteReadChannel("{}"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        return authGatewayWithoutAccessRequests(auditGatewayConfiguration(), auditJson, client)
    }

    @Test
    fun aGoTrue429ForAKnownEmailIsIndistinguishableFromAnUnknownEmailForResendAndRecover() = testApplication {
        val env = CanonicalTestEnv(gateway = goTrueThrottlingKnownEmails())
        application { env.mount(this) }
        for (path in listOf("/v1/auth/email/resend", "/v1/auth/recover")) {
            val known = client.post(path) {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"known@example.com"}""")
            }
            val unknown = client.post(path) {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"nobody@example.com"}""")
            }
            assertEquals(HttpStatusCode.NoContent, known.status, path)
            assertEquals(HttpStatusCode.NoContent, unknown.status, path)
            assertEquals(known.bodyAsText(), unknown.bodyAsText(), path)
            assertEquals(known.headers[HttpHeaders.RetryAfter], unknown.headers[HttpHeaders.RetryAfter], path)
            assertEquals(null, known.headers[HttpHeaders.RetryAfter], "sin Retry-After que delate al titular")
        }
    }

    @Test
    fun aRealUpstreamOutageOnResendAndRecoverStillSurfacesAs503() = runBlocking {
        val client = HttpClient(MockEngine { respond(ByteReadChannel("{}"), HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = authGatewayWithoutAccessRequests(auditGatewayConfiguration(), auditJson, client)
        val context = OposiBotsAuthContext("request-123", "127.0.0.1")
        val resend = assertFailsWith<OposiBotsAuthProblem> { gateway.resend(OposiBotsEmailResendRequest("a@example.com"), context) }
        val recover = assertFailsWith<OposiBotsAuthProblem> { gateway.recover(OposiBotsRecoverRequest(email = "a@example.com"), context) }
        assertEquals(HttpStatusCode.ServiceUnavailable, resend.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, recover.status)
    }

    // ---------------- ARCH-001: la escritura de nombres de Apple sube el ETag (actualizado_en) ----------------

    @Test
    fun firstAuthNamesBumpTheProfileEtagSoAStaleIfMatchCannotOverwriteThem() = runBlocking {
        val uid = "11111111-1111-4111-8111-111111111111"
        val patches = CopyOnWriteArrayList<String>()
        val idToken = fakeJwt(uid)
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.url.host == "appleid.apple.com" -> respond(ByteReadChannel("""{"id_token":"$idToken"}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/token" -> respond(
                    ByteReadChannel("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"$uid","email":"p@example.com"}}"""),
                    HttpStatusCode.OK,
                    json,
                )
                path == "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, json)
                path == "/rest/v1/perfiles_usuario" && request.method == HttpMethod.Get ->
                    respond(ByteReadChannel("""[{"usuario_id":"$uid","estado_cuenta":"activa","onboarding_completado":false}]"""), HttpStatusCode.OK, json)
                path == "/rest/v1/perfiles_usuario" && request.method == HttpMethod.Patch -> {
                    patches += (request.body as io.ktor.http.content.TextContent).text
                    respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val gateway = authGatewayWithoutAccessRequests(
            auditGatewayConfiguration().copy(appleClientId = "apple-client", appleClientSecret = "apple-secret"),
            auditJson,
            client,
        )
        gateway.apple(
            OposiBotsAppleRequest(idToken, "auth-code", nonce = "n-1", firstAuthGivenName = "Ada", firstAuthFamilyName = "Lovelace"),
            OposiBotsAuthContext("request-123", "127.0.0.1"),
        )
        assertEquals(2, patches.size)
        patches.forEach {
            val body = auditJson.parseToJsonElement(it).jsonObject
            assertNotNull(body["actualizado_en"], it)
            java.time.Instant.parse(body["actualizado_en"]!!.jsonPrimitive.content)
        }
    }

    private fun fakeJwt(subject: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$subject","exp":4102444800}""".toByteArray())
        return "$header.$payload.signature"
    }

    // ---------------- OBS-003: todo 5xx de dominio deja log, requestId y causa ----------------

    private suspend fun HttpClientPatch(client: HttpClient, body: String): HttpResponse = client.request("/api/v1/app/me/profile") {
        method = HttpMethod.Patch
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun anUndecodableRpcAnswerIs502WithReferenceAndLeavesALogWithTheRedactedCause() {
        val (response, lines) = captureLogs(listOf("OC-KTOR-OBSERVABILITY", "OC-KTOR-UPSTREAM")) {
            var captured: Pair<HttpStatusCode, String>? = null
            var requestIdHeader: String? = null
            testApplication {
                val env = CanonicalTestEnv()
                env.upstream.onRpc = { _, _ -> rpcOk("""{"unexpected":"person@example.com"}""") }
                application { env.mount(this) }
                val r = HttpClientPatch(client, """{"nombre":"Ada"}""")
                captured = r.status to r.bodyAsText()
                requestIdHeader = r.headers["X-Request-Id"]
            }
            Triple(captured!!, requestIdHeader!!, Unit)
        }
        val (status, body) = response.first
        val requestId = response.second
        assertEquals(HttpStatusCode.BadGateway, status)
        val envelope = Json.parseToJsonElement(body).jsonObject
        assertEquals("UPSTREAM_CONTRACT_ERROR", envelope["code"]!!.jsonPrimitive.content)
        assertTrue("Referencia: $requestId" in envelope["message"]!!.jsonPrimitive.content, body)
        assertEquals(requestId, envelope["requestId"]!!.jsonPrimitive.content)
        assertTrue(lines.any { "event=SUPABASE_REQUEST_FAILED" in it && "request_id=$requestId" in it && "error_type=UPSTREAM_CONTRACT_ERROR" in it }, lines.toString())
        assertTrue(lines.any { "UPSTREAM_CONTRACT_ERROR" in it && "requestId=$requestId" in it && "decode app_actualizar_perfil failed" in it }, lines.toString())
        assertTrue(lines.none { "person@example.com" in it }, "el email del cuerpo upstream no llega al log")
    }

    // ---------------- OBS-004: redaccion de PII ----------------

    @Test
    fun sensitiveTextIsRedactedBeforeItReachesLogsOrSentry() {
        val body = """Supabase POST /rest/v1/x -> 409: {"code":"23505","details":"Key (email)=(ada@example.com) already exists.","message":"duplicate key value violates unique constraint"} Bearer abc.def.ghi apikey=sb_secret_123"""
        val redacted = IllegalStateException(body).safeBackendMessage("x", maxLength = 1_000)
        assertTrue("ada@example.com" !in redacted, redacted)
        assertTrue("Key (email)=([redacted])" in redacted, redacted)
        assertTrue("abc.def.ghi" !in redacted && "sb_secret_123" !in redacted, redacted)
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"
        assertEquals("token=[redacted-jwt]", "token=$jwt".redactSensitiveText())
        assertEquals("sin datos personales", "sin datos personales".redactSensitiveText())
    }

    // ---------------- OBS-006: el fallo del RPC de revocacion se registra ----------------

    @Test
    fun aRejectedRevocationRpcIsLoggedWithTheRequestIdAndStatusAndNeverChangesThe202() {
        var status: HttpStatusCode? = null
        val (_, lines) = captureLogs(listOf("OC-KTOR-ACCOUNT-DELETION")) {
            testApplication {
                val env = CanonicalTestEnv()
                env.upstream.onRpc = { function, _ ->
                    when (function) {
                        "app_solicitar_eliminacion_cuenta" ->
                            rpcOk("""[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"2026-09-30T11:00:00.654321+00:00","ya_pendiente":false}]""")
                        else -> SupabaseRawResponse(500, """{"message":"boom"}""")
                    }
                }
                application { env.mount(this) }
                status = client.request("/api/v1/app/me") {
                    method = HttpMethod.Delete
                    header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"ELIMINAR","currentPassword":"the-password-123"}""")
                }.status
            }
        }
        assertEquals(HttpStatusCode.Accepted, status)
        assertTrue(lines.any { "recording session revocation rejected" in it && "status=500" in it && "requestId=" in it }, lines.toString())
    }

    // ---------------- ARCH-006: el p_patch sale de un unico mapeo y coincide con la RPC ----------------

    @Test
    fun theRpcPatchKeysComeFromOneMapperAndMatchTheSqlAllowLists() {
        val migration = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "supabase/migrations/20260930100200_slice1_profile_command_rpcs.sql") }
            .first { it.exists() }
            .readText()
        fun sqlList(name: String): Set<String> {
            val block = Regex("$name constant text\\[] := array\\[([^\\]]*)]").find(migration)!!.groupValues[1]
            return Regex("'([^']+)'").findAll(block).map { it.groupValues[1] }.toSet()
        }
        assertEquals(sqlList("v_allowed"), ProfileRpcContract.PATCH_KEYS)
        assertEquals(sqlList("v_detail_allowed"), ProfileRpcContract.DETAIL_KEYS)

        val full = ProfilePatchRequest(
            username = "ada_l",
            nombre = " Ada ",
            apellidos = " Lovelace ",
            especialidadId = TEST_SPECIALTY.uppercase(),
            comunidadPrincipalId = TEST_COMMUNITY.uppercase(),
            detail = ProfileDetailPatch(30, "Madrid", "activo", "Hospital", 90, "manana", "2027-06-01"),
            targetCommunityIds = listOf(TEST_COMMUNITY.uppercase()),
            introSeen = true,
        )
        val patch = ProfileRpcContract.patch(full.toCommand())
        assertEquals(ProfileRpcContract.PATCH_KEYS, patch.keys)
        assertEquals(ProfileRpcContract.DETAIL_KEYS, patch["detail"]!!.jsonObject.keys)
        assertEquals("Ada", patch["nombre"]!!.jsonPrimitive.content)
        assertEquals(TEST_SPECIALTY, patch["especialidadId"]!!.jsonPrimitive.content)
        assertEquals(listOf(TEST_COMMUNITY), patch["targetCommunityIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptySet(), ProfileRpcContract.patch(ProfilePatchRequest().toCommand()).keys)
    }

    // ---------------- ARCH-007: Valkey degradado no cuelga hilos ni devuelve 500 ----------------

    /** RESP falso: contesta a todo salvo a EVAL, que deja colgado (un Valkey degradado). */
    private class StallingRespServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val sockets = CopyOnWriteArrayList<java.net.Socket>()

        init {
            thread(isDaemon = true, name = "stalling-resp-accept") {
                try {
                    while (true) {
                        val socket = server.accept()
                        sockets += socket
                        thread(isDaemon = true, name = "stalling-resp-conn") { serve(socket) }
                    }
                } catch (_: SocketException) {
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) return
                    val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                    if ("EVAL" in text) continue
                    val commands = Regex("\\*\\d+\r\n").findAll(text).count().coerceAtLeast(1)
                    repeat(commands) { output.write((if ("PING" in text) "+PONG\r\n" else "+OK\r\n").toByteArray()) }
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }

        override fun close() {
            sockets.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    @Test
    fun aStalledValkeyFailsFastWithA503AndRetryAfterInsteadOfPinningIoThreadsForSixtySeconds() {
        StallingRespServer().use { server ->
            val limiter = ValkeyOposiBotsAuthRateLimiter(
                url = "redis://127.0.0.1:${server.port}",
                hasher = RateLimitKeyHasher("test-only-rate-limit-secret-32chars"),
                connectTimeout = Duration.ofMillis(500),
                commandTimeout = Duration.ofMillis(400),
            )
            try {
                val started = System.nanoTime()
                val failure = assertFailsWith<BackendDomainException> {
                    runBlocking { limiter.checkIp(OposiBotsAuthOperation.LOGIN, "203.0.113.5") }
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
                assertEquals("UPSTREAM_UNAVAILABLE", failure.errorCode)
                assertNotNull(failure.retryAfterSeconds)
                assertNotNull(failure.cause, "la causa tecnica se conserva para el log")
                assertTrue(elapsedMs < 10_000, "fallo en ${elapsedMs}ms; el tope por defecto de Lettuce serian 60000ms")
            } finally {
                limiter.close()
            }
        }
    }

    @Test
    fun theProductionTimeoutsAreShort() {
        assertTrue(ValkeyOposiBotsAuthRateLimiter.DEFAULT_CONNECT_TIMEOUT <= Duration.ofSeconds(2))
        assertTrue(ValkeyOposiBotsAuthRateLimiter.DEFAULT_COMMAND_TIMEOUT <= Duration.ofSeconds(5))
    }
}
