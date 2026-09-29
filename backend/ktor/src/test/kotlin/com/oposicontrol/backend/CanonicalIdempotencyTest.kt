package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@CanonicalCommand
@Serializable
internal data class IdemProbeCommand(val modo: String = "NORMAL")

@Serializable
internal data class IdemProbeResult(val intentoId: String, val modo: String)

/** SLICE 0 · idempotencia canonica (`specs.idempotency`). */
class CanonicalIdempotencyTest {

    private val key = "3F2504E0-4F89-41D3-9A0C-0305E82C3301"
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val template = "/api/v1/app/examenes/{examId}/intentos"

    // --- cabecera ---

    @Test
    fun theKeyIsAUuidStoredLowercase() {
        assertEquals(key.lowercase(), IdempotencyKey.parse(key, required = true)!!.value)
        assertNull(IdempotencyKey.parse(null, required = false))
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", assertFailsWith<BackendDomainException> { IdempotencyKey.parse(null, true) }.errorCode)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", assertFailsWith<BackendDomainException> { IdempotencyKey.parse("", true) }.errorCode)
        listOf("abc", "3F2504E0-4F89-41D3-9A0C-0305E82C330", "3F2504E04F8941D39A0C0305E82C3301", " $key").forEach {
            val error = assertFailsWith<BackendDomainException>(it) { IdempotencyKey.parse(it, false) }
            assertEquals("IDEMPOTENCY_KEY_INVALID", error.errorCode)
            assertEquals(400, error.status.value)
        }
    }

    // --- huella (RFC 8785) ---

    @Test
    fun jcsCanonicalizesKeysWhitespaceNumbersAndStrings() {
        fun jcs(text: String) = RequestFingerprint.canonicalJsonBody(text.toByteArray())
        assertEquals("""{"a":2,"b":{"x":[1,true,null],"y":"z"}}""", jcs("""{ "b": {"y":"z", "x":[1, true, null]}, "a": 2 }"""))
        // Vectores numericos de RFC 8785 / ECMAScript.
        assertEquals("[1e+30,4.5,0.002,0.000001,1e-7,333333333.3333333,0,100,1e+21,100000000000000000000]",
            jcs("[1E30, 4.50, 2e-3, 0.000001, 1e-7, 333333333.33333329, -0, 100, 1e21, 1e20]"))
        assertEquals("[-1.5,123456789012345680000]", jcs("[-1.50, 123456789012345678901]"))
        // Vector de cadenas de RFC 8785 §3.2.2.2.
        run {
            val q = "\""
            val bs = "\\"
            val input = q + bs + "u20ac$" + bs + "u000F" + bs + "u000aA'" + bs + "u0042" + bs + "u0022" + bs + "u005c" +
                bs + bs + bs + q + bs + "/" + q
            val expected = q + Char(0x20AC) + "$" + bs + "u000f" + bs + "nA'B" + bs + q + bs + bs + bs + bs + bs + q + "/" + q
            assertEquals(expected, jcs(input))
        }
        assertEquals("", jcs(""))
        assertEquals("", jcs("   "))
        assertEquals("MALFORMED_BODY", assertFailsWith<BackendDomainException> { jcs("{nope") }.errorCode)
    }

    @Test
    fun theFingerprintIgnoresKeyOrderButNotMethodPathParamsOrBody() {
        fun fp(method: String = "POST", params: Map<String, String> = mapOf("examId" to "e1"), body: String = """{"modo":"NORMAL","x":1}""") =
            RequestFingerprint.of(method, template, params, RequestFingerprint.canonicalJsonBody(body.toByteArray()))

        val base = fp()
        assertTrue(base.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(base, fp(body = """{ "x" : 1, "modo" : "NORMAL" }"""))
        assertTrue(base != fp(method = "PUT"))
        assertTrue(base != fp(params = mapOf("examId" to "e2")))
        assertTrue(base != fp(body = """{"modo":"DEMO","x":1}"""))
        assertEquals(
            RequestFingerprint.of("POST", "/x/{a}/{b}", mapOf("b" to "2", "a" to "1"), ""),
            RequestFingerprint.of("POST", "/x/{a}/{b}", mapOf("a" to "1", "b" to "2"), ""),
        )
        assertEquals(
            RequestFingerprint.multipartBody(listOf("file" to byteArrayOf(1))),
            RequestFingerprint.multipartBody(listOf("file" to byteArrayOf(1))),
        )
        assertTrue(
            RequestFingerprint.multipartBody(listOf("a" to byteArrayOf(1), "b" to byteArrayOf(2))) !=
                RequestFingerprint.multipartBody(listOf("b" to byteArrayOf(2), "a" to byteArrayOf(1))),
            "el orden de las partes cuenta",
        )
    }

    // --- almacen: los casos de la migracion ---

    private class Clock(var now: Instant = Instant.parse("2026-09-28T10:00:00Z")) : () -> Instant {
        override fun invoke(): Instant = now
    }

    private fun begin(store: IdempotencyStore, fp: String = "a".repeat(64), k: String = key, a: String = actor) = runBlocking {
        store.begin(IdempotencyBeginRequest(a, "POST $template", IdempotencyKey.parse(k, true)!!, fp))
    }

    private val created = StoredIdempotentResponse(201, """{"id":"i1"}""", mapOf(HttpHeaders.Location to "/api/v1/app/intentos/i1"))

    @Test
    fun newThenReplayThenMismatch() = runBlocking {
        val store = InMemoryIdempotencyStore(Clock())
        val claimed = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertEquals(false, claimed.takeover)
        store.complete(claimed.claim, created)

        assertEquals(IdempotencyBeginResult.Replay(created), begin(store))
        assertEquals(IdempotencyBeginResult.Mismatch, begin(store, fp = "b".repeat(64)))
    }

    @Test
    fun inProgressThenStaleTakeoverAfterTheLease() {
        val clock = Clock()
        val store = InMemoryIdempotencyStore(clock)
        val first = assertIs<IdempotencyBeginResult.Claimed>(begin(store))

        assertEquals(IdempotencyBeginResult.InProgress, begin(store))
        assertEquals(IdempotencyBeginResult.Mismatch, begin(store, fp = "c".repeat(64)), "mismatch en cualquier estado")

        clock.now = clock.now.plusSeconds(31)
        val stale = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertEquals(first.id, stale.id)
        assertEquals(true, stale.takeover)
        assertNotEquals(first.claim.token, stale.claim.token, "el takeover rota el token de fencing")
        assertEquals(IdempotencyBeginResult.InProgress, begin(store), "el lease se ha renovado")
    }

    /** RB-1-01: A pierde el lease, B lo toma (STALE); el release/complete tardio de A es no-op. */
    @Test
    fun staleTakeoverFencesTheSlowOriginalOwner() = runBlocking {
        val clock = Clock()
        val store = InMemoryIdempotencyStore(clock)
        val a = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        clock.now = clock.now.plusSeconds(31)
        val b = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertEquals(true, b.takeover)

        store.release(a.claim)
        assertEquals(1, store.size(), "A.release tardio no borra la fila de B")
        store.complete(a.claim, created)
        assertEquals(IdempotencyBeginResult.InProgress, begin(store), "ni A.complete guarda ni un tercero obtiene NEW")

        val bResponse = StoredIdempotentResponse(201, """{"id":"b"}""")
        store.complete(b.claim, bResponse)
        assertEquals(IdempotencyBeginResult.Replay(bResponse), begin(store), "se guarda la respuesta de B, no la de A")
    }

    @Test
    fun releaseThenRetryIsNewAgain() = runBlocking {
        val store = InMemoryIdempotencyStore(Clock())
        val first = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        store.release(first.claim)
        val retry = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertEquals(false, retry.takeover)
        assertTrue(first.id != retry.id)
    }

    @Test
    fun expiryAfterTheTtlStartsOverAndSweepsOtherExpiredRows() = runBlocking {
        val clock = Clock()
        val store = InMemoryIdempotencyStore(clock)
        store.complete(assertIs<IdempotencyBeginResult.Claimed>(begin(store)).claim, created)
        store.complete(
            assertIs<IdempotencyBeginResult.Claimed>(begin(store, k = "3f2504e0-4f89-41d3-9a0c-0305e82c3302")).claim,
            created,
        )
        assertEquals(2, store.size())

        clock.now = clock.now.plus(Duration.ofHours(24)).plusSeconds(1)
        assertIs<IdempotencyBeginResult.Claimed>(begin(store, fp = "d".repeat(64)), "caducada: ni replay ni mismatch")
        assertEquals(1, store.size(), "la otra fila caducada se barre")
    }

    @Test
    fun theSameKeyIsIndependentPerActorAndScope() {
        val store = InMemoryIdempotencyStore(Clock())
        assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertIs<IdempotencyBeginResult.Claimed>(begin(store, a = "22222222-2222-4222-8222-222222222222"))
        runBlocking {
            assertIs<IdempotencyBeginResult.Claimed>(
                store.begin(IdempotencyBeginRequest(actor, "POST /otra", IdempotencyKey.parse(key, true)!!, "a".repeat(64))),
            )
        }
    }

    @Test
    fun nConcurrentBeginsConvergeOnExactlyOneNew() = runBlocking {
        val store = InMemoryIdempotencyStore()
        val results = withContext(Dispatchers.Default) {
            (1..64).map { async { begin(store) } }.awaitAll()
        }
        assertEquals(1, results.count { it is IdempotencyBeginResult.Claimed })
        assertEquals(63, results.count { it == IdempotencyBeginResult.InProgress })
    }

    // --- plugin de ruta ---

    private class Probe {
        val executions = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
    }

    private fun idemApp(
        store: IdempotencyStore = InMemoryIdempotencyStore(),
        probe: Probe = Probe(),
        block: suspend ApplicationTestBuilder.(Probe) -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                post(template) {
                    val command = call.receiveCanonicalCommand<IdemProbeCommand>()
                    val n = probe.executions.incrementAndGet()
                    probe.gate?.await()
                    if (probe.fail) throw CanonicalErrorCode.EXAM_EMPTY.exception()
                    val id = "intento-$n"
                    call.response.header(HttpHeaders.Location, "/api/v1/app/intentos/$id")
                    call.respond(HttpStatusCode.Created, IdemProbeResult(id, command.modo))
                }.install(CanonicalIdempotency) {
                    routeTemplate = template
                    this.store = store
                    actor = { call -> CanonicalUserId.fromJwtSubject(call.request.headers["X-Test-Sub"]).value }
                }
            }
        }
        block(probe)
    }

    private suspend fun ApplicationTestBuilder.start(
        body: String = """{"modo":"NORMAL"}""",
        idemKey: String? = key,
        exam: String = "e1",
    ): HttpResponse = client.post("/api/v1/app/examenes/$exam/intentos") {
        header("X-Test-Sub", actor)
        idemKey?.let { header(IdempotencyHeaders.KEY, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun code(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun aRepeatedRequestIsReplayedWithoutExecutingAgain() = idemApp { probe ->
        val first = start()
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals("/api/v1/app/intentos/intento-1", first.headers[HttpHeaders.Location])
        assertNull(first.headers[IdempotencyHeaders.REPLAYED])

        val replay = start(body = """{ "modo" : "NORMAL" }""")
        assertEquals(HttpStatusCode.Created, replay.status)
        assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(first.headers[HttpHeaders.Location], replay.headers[HttpHeaders.Location])
        assertEquals(
            Json.parseToJsonElement(first.bodyAsText()),
            Json.parseToJsonElement(replay.bodyAsText()),
        )
        assertEquals(1, probe.executions.get())
    }

    @Test
    fun theSameKeyWithAnotherBodyOrPathIs422Reused() = idemApp { probe ->
        assertEquals(HttpStatusCode.Created, start().status)

        val otherBody = start(body = """{"modo":"DEMO"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, otherBody.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", code(otherBody))

        val otherExam = start(exam = "e2")
        assertEquals(HttpStatusCode.UnprocessableEntity, otherExam.status)
        assertEquals(1, probe.executions.get())
    }

    @Test
    fun aMissingOrMalformedKeyIs400BeforeExecuting() = idemApp { probe ->
        val missing = start(idemKey = null)
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", code(missing))
        val malformed = start(idemKey = "not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("IDEMPOTENCY_KEY_INVALID", code(malformed))
        assertEquals(0, probe.executions.get())
    }

    @Test
    fun anUnknownFieldIsRejectedStrictlyAndDoesNotBurnTheKey() = idemApp { probe ->
        val unknown = start(body = """{"modo":"NORMAL","usuarioId":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        val body = Json.parseToJsonElement(unknown.bodyAsText()).jsonObject
        assertEquals("VALIDATION", body["code"]!!.jsonPrimitive.content)
        assertTrue(body.toString().contains("UNKNOWN_FIELD"), body.toString())
        assertEquals(0, probe.executions.get())
    }

    @Test
    fun aFailedExecutionReleasesTheKeySoTheSameKeyCanRetry() = idemApp { probe ->
        probe.fail = true
        val failed = start()
        assertEquals(HttpStatusCode.Conflict, failed.status)
        assertEquals("EXAM_EMPTY", code(failed))

        probe.fail = false
        val retry = start()
        assertEquals(HttpStatusCode.Created, retry.status)
        assertNull(retry.headers[IdempotencyHeaders.REPLAYED], "un fallo no se guarda ni se repite")
        assertEquals(2, probe.executions.get())
    }

    @Test
    fun aConcurrentDuplicateWhileInProgressIs409WithRetryAfter() {
        val probe = Probe().apply { gate = CompletableDeferred() }
        idemApp(probe = probe) {
            coroutineScope {
                val first = async { start() }
                while (probe.executions.get() == 0) delay(5)
                val duplicate = start()
                assertEquals(HttpStatusCode.Conflict, duplicate.status)
                assertEquals("1", duplicate.headers[HttpHeaders.RetryAfter])
                assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", code(duplicate))
                probe.gate!!.complete(Unit)
                assertEquals(HttpStatusCode.Created, first.await().status)
            }
            assertEquals(1, probe.executions.get())
            assertEquals("true", start().headers[IdempotencyHeaders.REPLAYED])
        }
    }

    @Test
    fun aBurstOfIdenticalRequestsExecutesTheCommandExactlyOnce() {
        val probe = Probe().apply { gate = CompletableDeferred() }
        idemApp(probe = probe) {
            val statuses = coroutineScope {
                val burst = (1..12).map { async { start().status } }
                while (probe.executions.get() == 0) delay(5)
                delay(50)
                probe.gate!!.complete(Unit)
                burst.awaitAll()
            }
            assertEquals(1, probe.executions.get(), "statuses=$statuses")
            assertEquals(1, statuses.count { it == HttpStatusCode.Created }, "statuses=$statuses")
            assertTrue(statuses.all { it == HttpStatusCode.Created || it == HttpStatusCode.Conflict }, "statuses=$statuses")
        }
    }

    // --- PostgREST ---

    @Test
    fun thePostgrestStoreCallsTheThreeRpcsWithTheServiceRoleAndParsesEveryOutcome() = runBlocking {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        var next = """[{"outcome":"NEW","record_id":"9d7c0000-0000-4000-8000-000000000001","claim_token":"7a110000-0000-4000-8000-000000000001"}]"""
        val transport = SupabaseRequestTransport.forTesting(
            baseUrl = "https://stub.supabase.test",
            secretKey = "sb_secret_test_only",
            client = V1StubHttpClient(record = { request -> calls += request.uri().path to request.bodyText() }) {
                V1StubResponse(200, next)
            },
        )
        val store = PostgrestIdempotencyStore(transport)

        val claimed = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertEquals(IdempotencyClaim("9d7c0000-0000-4000-8000-000000000001", "7a110000-0000-4000-8000-000000000001"), claimed.claim)
        val (path, body) = calls.single()
        assertEquals("/rest/v1/rpc/idem_begin", path)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(actor, sent["p_actor_id"]!!.jsonPrimitive.content)
        assertEquals("POST $template", sent["p_scope"]!!.jsonPrimitive.content)
        assertEquals(key.lowercase(), sent["p_idem_key"]!!.jsonPrimitive.content)
        assertEquals("86400 seconds", sent["p_ttl"]!!.jsonPrimitive.content)
        assertEquals("30 seconds", sent["p_lease"]!!.jsonPrimitive.content)

        next = """{"outcome":"STALE","record_id":"9d7c0000-0000-4000-8000-000000000001","claim_token":"7a110000-0000-4000-8000-000000000002"}"""
        val stale = assertIs<IdempotencyBeginResult.Claimed>(begin(store))
        assertEquals(true, stale.takeover)
        assertEquals("7a110000-0000-4000-8000-000000000002", stale.claim.token)
        next = """{"outcome":"STALE","record_id":"9d7c0000-0000-4000-8000-000000000001"}"""
        assertFailsWith<kotlinx.serialization.SerializationException>("STALE sin claim_token no se ejecuta sin fencing") { begin(store) }
        next = """[{"outcome":"MISMATCH","record_id":null}]"""
        assertEquals(IdempotencyBeginResult.Mismatch, begin(store))
        next = """[{"outcome":"IN_PROGRESS","record_id":null,"retry_after_seconds":1}]"""
        assertEquals(IdempotencyBeginResult.InProgress, begin(store))
        next = """[{"outcome":"REPLAY","record_id":"x","stored_status":201,"stored_body":{"id":"i1"},""" +
            """"stored_headers":{"Location":"/api/v1/app/intentos/i1","Content-Type":"application/json"}}]"""
        val replay = assertIs<IdempotencyBeginResult.Replay>(begin(store))
        assertEquals(201, replay.response.status)
        assertEquals("""{"id":"i1"}""", replay.response.body)
        assertEquals("/api/v1/app/intentos/i1", replay.response.headers[HttpHeaders.Location])

        calls.clear()
        next = ""
        store.complete(IdempotencyClaim("9d7c", "t0k3n"), created)
        store.release(IdempotencyClaim("9d7c", "t0k3n"))
        assertEquals(listOf("/rest/v1/rpc/idem_complete", "/rest/v1/rpc/idem_release"), calls.map { it.first })
        val completeBody = Json.parseToJsonElement(calls.first().second).jsonObject
        val releaseBody = Json.parseToJsonElement(calls.last().second).jsonObject
        listOf(completeBody, releaseBody).forEach {
            assertEquals("9d7c", it["p_record_id"]!!.jsonPrimitive.content)
            assertEquals("t0k3n", it["p_claim_token"]!!.jsonPrimitive.content)
        }
        assertEquals("201", completeBody["p_status"]!!.jsonPrimitive.content)
        assertEquals("i1", completeBody["p_body"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    /** PF2-2-04: `idem_complete/idem_release` = false (claim rotado) es un no-op fenced que se registra, no se traga. */
    @Test
    fun aFencedNoOpOfCompleteOrReleaseIsDetectedNotSilentlyDropped() = runBlocking {
        val claim = IdempotencyClaim("9d7c", "t0k3n")
        assertEquals(true, warnIfFencedNoOp("idem_complete", claim, "false"))
        assertEquals(true, warnIfFencedNoOp("idem_release", claim, " false\n"))
        assertEquals(false, warnIfFencedNoOp("idem_complete", claim, "true"))
        assertEquals(false, warnIfFencedNoOp("idem_release", claim, ""))
        // Y ninguna de las dos operaciones lanza cuando el fencing las descarta: el efecto (no tocar la fila de otro) es el correcto.
        val store = PostgrestIdempotencyStore(
            SupabaseRequestTransport.forTesting("https://stub.supabase.test", "sb_secret_test_only", V1StubHttpClient({}) { V1StubResponse(200, "false") }),
        )
        store.complete(claim, created)
        store.release(claim)
    }

    @Test
    fun anUnexpectedRpcShapeIsAnUpstreamContractErrorNotASilentExecute() {
        val store = PostgrestIdempotencyStore(
            SupabaseRequestTransport.forTesting(
                "https://stub.supabase.test",
                "sb_secret_test_only",
                V1StubHttpClient({}) { V1StubResponse(200, """[{"outcome":"WHATEVER"}]""") },
            ),
        )
        assertFailsWith<kotlinx.serialization.SerializationException> { begin(store) }
    }
}

/** El cuerpo que salio por el cable (el `BodyPublisher` de la JDK no se puede leer de otra forma). */
internal fun HttpRequest.bodyText(): String {
    val publisher = bodyPublisher().orElse(null) ?: return ""
    val out = java.io.ByteArrayOutputStream()
    val done = java.util.concurrent.CountDownLatch(1)
    publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
        override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
        override fun onNext(item: ByteBuffer) {
            val bytes = ByteArray(item.remaining())
            item.get(bytes)
            out.write(bytes)
        }
        override fun onError(throwable: Throwable) = done.countDown()
        override fun onComplete() = done.countDown()
    })
    done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    return out.toString(Charsets.UTF_8)
}
