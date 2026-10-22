package com.oposicontrol.backend

import io.ktor.client.request.get
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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CERT-LOAD-1000 (AdmissionControl.kt): admision acotada, rechazo inmediato con 503 SERVER_BUSY + Retry-After, salud exenta,
 * plazo por peticion en el transporte de Supabase y escrituras idempotentes que nunca quedan a medias por el plazo.
 */
class AdmissionControlTest {

    private val actor = "11111111-1111-4111-8111-111111111111"

    private fun Application.admissionModule(settings: AdmissionSettings, gate: CompletableDeferred<Unit>, started: AtomicInteger) {
        install(ContentNegotiation) { json() }
        installRequestCorrelation()
        installBackendStatusPages()
        installBotAdmissionControl(settings, SimpleMeterRegistry())
        routing {
            get("/api/v1/app/slow") {
                started.incrementAndGet()
                gate.await()
                call.respondText("done")
            }
            get("/health/live") { call.respondText("""{"status":"UP"}""") }
            get("/metrics") { call.respondText("# metrics") }
        }
    }

    private suspend fun code(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.awaitStarted(started: AtomicInteger, n: Int) {
        repeat(200) { if (started.get() >= n) return; delay(10) }
        error("solo arrancaron ${started.get()} de $n")
    }

    @Test
    fun aFullReplicaRejectsImmediatelyWith503ServerBusyAndRetryAfterWhileHealthAndMetricsStillAnswer() = testApplication {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        application { admissionModule(AdmissionSettings(maxInFlight = 1, maxQueued = 0, queueTimeoutMs = 5_000), gate, started) }
        coroutineScope {
            val first = async { client.get("/api/v1/app/slow") }
            awaitStarted(started, 1)

            val t0 = System.nanoTime()
            val rejected = client.get("/api/v1/app/slow")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
            assertEquals("SERVER_BUSY", code(rejected))
            val retryAfter = assertNotNull(rejected.headers[HttpHeaders.RetryAfter]).toInt()
            assertTrue(retryAfter in 2..4, "Retry-After con fluctuacion base..2*base, fue $retryAfter")
            assertNotNull(rejected.headers["X-Request-Id"], "el rechazo lleva X-Request-Id")
            assertTrue(elapsedMs < 2_000, "rechazo inmediato (cola llena), tardo $elapsedMs ms")
            assertEquals(1, started.get(), "la peticion rechazada no ejecuto el handler")

            // /health* y /metrics estan exentas: contestan aunque la replica este llena.
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            assertEquals(HttpStatusCode.OK, client.get("/metrics").status)

            gate.complete(Unit)
            assertEquals(HttpStatusCode.OK, first.await().status)
        }
        // Liberado el permiso, se vuelve a admitir.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/app/slow").status)
    }

    @Test
    fun aQueuedRequestRunsWhenAPermitFreesAndOneThatWaitsTooLongIsRejected() = testApplication {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        application { admissionModule(AdmissionSettings(maxInFlight = 1, maxQueued = 1, queueTimeoutMs = 300), gate, started) }
        coroutineScope {
            val first = async { client.get("/api/v1/app/slow") }
            awaitStarted(started, 1)
            // Cola de 1 con espera de 300 ms: esta espera y se rechaza por tiempo, sin ejecutar.
            val timedOut = client.get("/api/v1/app/slow")
            assertEquals(HttpStatusCode.ServiceUnavailable, timedOut.status)
            assertEquals("SERVER_BUSY", code(timedOut))
            assertEquals(1, started.get())

            // Esta espera en la cola y entra en cuanto la primera termina.
            val queued = async { client.get("/api/v1/app/slow") }
            delay(100)
            gate.complete(Unit)
            assertEquals(HttpStatusCode.OK, first.await().status)
            assertEquals(HttpStatusCode.OK, queued.await().status)
            assertEquals(2, started.get())
        }
    }

    @Test
    fun theLimiterNeverLeaksPermits() = runBlocking {
        val limiter = AdmissionLimiter(maxInFlight = 2, maxQueued = 10, queueTimeoutMs = 1)
        suspend fun use() { if (limiter.acquire() == null) try { delay(1) } finally { limiter.release() } }
        repeat(300) { coroutineScope { List(5) { async { use() } }.forEach { it.await() } } }
        assertEquals(0, limiter.inFlight)
        assertEquals(0, limiter.queued)
        assertNull(limiter.acquire()); assertNull(limiter.acquire())
        assertEquals(AdmissionRejection.QUEUE_TIMEOUT, limiter.acquire())
    }

    @Test
    fun aRequestCancelledWithThePermitOrInTheQueueGivesEverythingBack() = runBlocking {
        val limiter = AdmissionLimiter(maxInFlight = 1, maxQueued = 4, queueTimeoutMs = 60_000)
        val running = CompletableDeferred<Unit>()
        val holder = launch { limiter.runAdmitted { running.complete(Unit); awaitCancellation() } }
        running.await()
        val queuedJobs = List(3) { launch { limiter.runAdmitted { fail("no deberia ejecutarse") } } }
        while (limiter.queued < 3) yield()
        assertEquals(1, limiter.inFlight)
        queuedJobs.forEach { it.cancelAndJoin() } // cancelados en la cola
        assertEquals(0, limiter.queued)
        holder.cancelAndJoin() // cancelado con el permiso cogido
        assertEquals(0, limiter.inFlight)
        // Carrera cancelacion / permiso que llega: nunca se pierde un permiso.
        repeat(500) {
            val first = launch { limiter.runAdmitted { yield() } }
            val second = launch { limiter.runAdmitted { yield() } }
            yield()
            second.cancel()
            first.join(); second.join()
        }
        assertEquals(0, limiter.inFlight)
        assertEquals(0, limiter.queued)
    }

    // ---------------- plazo en el transporte ----------------

    private class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val paths get() = sent.map { it.method() + " " + it.uri().path }
        fun transport() = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ sent += it }) { url ->
                when {
                    "/rpc/idem_" in url -> V1StubResponse(200, "true")
                    "/rpc/" in url -> V1StubResponse(200, """{"ok":true}""")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
    }

    private fun expired() = DeadlineState(System.nanoTime() - 1)

    @Test
    fun withAnExpiredDeadlineNoNewUpstreamCallStartsAndItIsServerBusy() {
        val upstream = Upstream()
        val transport = upstream.transport()
        RequestDeadline.runWith(expired()) {
            val read = assertFailsWith<BackendDomainException> { transport.get("/rest/v1/examenes?select=id") }
            assertEquals("SERVER_BUSY", read.errorCode)
            assertNotNull(read.retryAfterSeconds)
            val write = assertFailsWith<BackendDomainException> { transport.rpc("app_record_attempt_answer", "{}") }
            assertEquals("SERVER_BUSY", write.errorCode)
            // La finalizacion de idempotencia corre siempre (si no, la clave quedaria "en curso").
            transport.rpc("idem_release", "{}")
            transport.rpc("idem_complete", "{}")
        }
        assertEquals(listOf("POST /rest/v1/rpc/idem_release", "POST /rest/v1/rpc/idem_complete"), upstream.paths)
    }

    @Test
    fun readsWaitAtMostTheRemainingDeadlineWhileWritesKeepTheirFullTimeout() {
        val upstream = Upstream()
        val transport = upstream.transport()
        RequestDeadline.runWith(DeadlineState(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3_000))) {
            transport.get("/rest/v1/examenes?select=id")
            transport.rpc("app_start_exam_attempt", "{}")
        }
        val read = upstream.sent[0].timeout().get()
        val write = upstream.sent[1].timeout().get()
        assertTrue(read.toMillis() <= 3_000, "lectura acotada al plazo restante: $read")
        assertEquals(UPSTREAM_REQUEST_TIMEOUT, write, "una escritura nunca se acorta")
    }

    @Test
    fun afterABusinessWriteTheRequestIsPastTheNoReturnPointAndFinishesEvenWithTheDeadlineGone() {
        val upstream = Upstream()
        val transport = upstream.transport()
        val nowNanos = AtomicNanos(System.nanoTime())
        val state = DeadlineState(nowNanos.value + TimeUnit.MILLISECONDS.toNanos(1_000)) { nowNanos.value }
        RequestDeadline.runWith(state) {
            // Ni el reloj de la base ni el claim de idempotencia aplican negocio: no fijan el punto sin retorno.
            transport.rpc("app_server_now", "{}")
            transport.rpc("idem_begin", "{}")
            assertEquals(false, state.committed)
            transport.rpc("app_record_attempt_answer", "{}")
            assertEquals(true, state.committed)
            nowNanos.value += TimeUnit.SECONDS.toNanos(30) // plazo vencido de sobra
            transport.get("/rest/v1/intentos_examen?select=id") // se termina la peticion
        }
        assertEquals(4, upstream.sent.size)
    }

    private class AtomicNanos(@Volatile var value: Long)

    @Test
    fun aWriteWhoseTimeoutNoLongerFitsBeforeTheEdgeDoesNotStartButReadsAndKeyReleaseStillRun() {
        val upstream = Upstream()
        val transport = upstream.transport()
        val nowNanos = AtomicNanos(System.nanoTime())
        val writeTimeoutNanos = UPSTREAM_REQUEST_TIMEOUT.toNanos()
        // Plazo todavia vivo (5 s), pero al borde le queda 1 ms menos que el timeout de una escritura.
        val state = DeadlineState(
            deadlineNanos = nowNanos.value + TimeUnit.SECONDS.toNanos(5),
            writeEndNanos = nowNanos.value + writeTimeoutNanos - TimeUnit.MILLISECONDS.toNanos(1),
        ) { nowNanos.value }
        RequestDeadline.runWith(state) {
            transport.get("/rest/v1/examenes?select=id")
            val write = assertFailsWith<BackendDomainException> { transport.rpc("app_record_attempt_answer", "{}") }
            assertEquals("SERVER_BUSY", write.errorCode)
            assertNotNull(write.retryAfterSeconds)
            val claim = assertFailsWith<BackendDomainException> { transport.rpc("idem_begin", "{}") }
            assertEquals("SERVER_BUSY", claim.errorCode)
            transport.rpc("idem_release", "{}")
            assertEquals(false, state.committed)
        }
        assertEquals(listOf("GET /rest/v1/examenes", "POST /rest/v1/rpc/idem_release"), upstream.paths)
    }

    @Test
    fun aWriteThatStillFitsBeforeTheEdgeStartsWithItsFullTimeoutAndOnceCommittedTheEdgeNoLongerApplies() {
        val upstream = Upstream()
        val transport = upstream.transport()
        val nowNanos = AtomicNanos(System.nanoTime())
        val state = DeadlineState(
            deadlineNanos = nowNanos.value + TimeUnit.SECONDS.toNanos(5),
            writeEndNanos = nowNanos.value + UPSTREAM_REQUEST_TIMEOUT.toNanos(),
        ) { nowNanos.value }
        RequestDeadline.runWith(state) {
            transport.rpc("app_record_attempt_answer", "{}")
            assertEquals(true, state.committed)
            nowNanos.value += TimeUnit.SECONDS.toNanos(30)
            transport.rpc("app_record_attempt_answer", "{}") // pasado el punto sin retorno se termina la peticion
        }
        assertEquals(UPSTREAM_REQUEST_TIMEOUT, upstream.sent[0].timeout().get())
        assertEquals(2, upstream.sent.size)
    }

    @Test
    fun theEdgeBudgetMustLeaveRoomForAWriteOrStartupFails() {
        assertFailsWith<IllegalArgumentException> { AdmissionSettings(deadlineMs = 15_000, edgeTimeoutMs = 15_000) }
        assertEquals(24_000L, AdmissionSettings().writeBudgetMs)
        assertEquals(
            30_000L,
            AdmissionSettings.fromEnvironment { if (it == "BOT_EDGE_TIMEOUT_MS") "30000" else null }.edgeTimeoutMs,
        )
    }

    @Test
    fun theDeadlineFollowsTheRequestOntoIoThreadsAndParallelBranches() = runBlocking {
        val upstream = Upstream()
        val transport = upstream.transport()
        withContext(RequestDeadline(expired())) {
            val serial = assertFailsWith<BackendDomainException> { withUpstreamIo { transport.get("/rest/v1/a") } }
            assertEquals("SERVER_BUSY", serial.errorCode)
            val parallel = assertFailsWith<BackendDomainException> {
                withUpstreamIoParallel { asyncUpstreamIo { transport.get("/rest/v1/b") }.await() }
            }
            assertEquals("SERVER_BUSY", parallel.errorCode)
        }
        // Fuera de la peticion (sin plazo) el mismo hilo IO no arrastra el plazo de otra.
        withUpstreamIo { transport.get("/rest/v1/c") }
        assertEquals(listOf("GET /rest/v1/c"), upstream.paths)
    }

    // ---------------- escritura idempotente bajo plazo / cancelacion ----------------

    private val template = "/api/v1/app/intentos/{attemptId}/respuestas/{questionId}"

    /**
     * Ruta de comando real: CanonicalIdempotency + una escritura de negocio por el transporte. [beforeWrite] simula una cola
     * o un upstream lento ANTES de la escritura; [afterWrite] lo mismo DESPUES (lecturas de cierre).
     */
    private fun idempotentWriteApp(
        store: InMemoryIdempotencyStore,
        upstream: Upstream,
        deadlineMs: Long,
        beforeWrite: suspend () -> Unit,
        afterWrite: suspend () -> Unit,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val transport = upstream.transport()
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            installBotAdmissionControl(AdmissionSettings(deadlineMs = deadlineMs), SimpleMeterRegistry())
            routing {
                post("/api/v1/app/intentos/{attemptId}/respuestas/{questionId}") {
                    beforeWrite()
                    withUpstreamIo { transport.rpc("app_record_attempt_answer", "{}") }
                    afterWrite()
                    withUpstreamIo { transport.get("/rest/v1/respuestas_intento?select=id") }
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }.install(CanonicalIdempotency) {
                    routeTemplate = template
                    this.store = store
                    actor = { this@AdmissionControlTest.actor }
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.answer(key: String) = client.post("/api/v1/app/intentos/a1/respuestas/q1") {
        header(IdempotencyHeaders.KEY, key)
        contentType(ContentType.Application.Json)
        setBody("{}")
    }

    @Test
    fun aDeadlineHitBeforeTheWriteAppliesNothingReleasesTheKeyAndTheRetryWritesExactlyOnce() {
        val store = InMemoryIdempotencyStore()
        val upstream = Upstream()
        val slowOnce = AtomicInteger(1)
        val key = "2b1f8f9e-6c1d-4c55-9a43-6a1f2b3c4d5e"
        idempotentWriteApp(
            store, upstream, deadlineMs = 1_000,
            beforeWrite = { if (slowOnce.getAndDecrement() > 0) delay(1_300) },
            afterWrite = {},
        ) {
            val first = answer(key)
            assertEquals(HttpStatusCode.ServiceUnavailable, first.status)
            assertEquals("SERVER_BUSY", code(first))
            assertNotNull(first.headers[HttpHeaders.RetryAfter])
            assertTrue(upstream.paths.none { "app_record_attempt_answer" in it }, "el plazo vencio ANTES de escribir: nada aplicado")

            // La clave se libero (no queda "en curso"): el reintento con la MISMA clave ejecuta y escribe una sola vez.
            val retry = answer(key)
            assertEquals(HttpStatusCode.OK, retry.status)
            val replay = answer(key)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED])
            assertEquals(1, upstream.paths.count { "app_record_attempt_answer" in it }, "exactamente una escritura")
        }
    }

    @Test
    fun aDeadlineHitAfterTheWriteNeverLeavesItHalfAppliedTheRequestFinishesAndTheKeyIsCompleted() {
        val store = InMemoryIdempotencyStore()
        val upstream = Upstream()
        val key = "3c2f8f9e-6c1d-4c55-9a43-6a1f2b3c4d5e"
        idempotentWriteApp(
            store, upstream, deadlineMs = 1_000,
            beforeWrite = {},
            afterWrite = { delay(1_300) }, // vence el plazo con la escritura ya hecha
        ) {
            val first = answer(key)
            assertEquals(HttpStatusCode.OK, first.status, "pasado el punto sin retorno la peticion termina")
            assertTrue(upstream.paths.any { it == "GET /rest/v1/respuestas_intento" }, "la lectura de cierre se hizo")
            val replay = answer(key)
            assertEquals("true", replay.headers[IdempotencyHeaders.REPLAYED], "la clave quedo completada, no en curso")
            assertEquals(1, upstream.paths.count { "app_record_attempt_answer" in it })
        }
    }
}
