package com.oposicontrol.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
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
 * LOOP 5, area KTOR_SEC:
 * - MASTER5-SEC-001: un `kid` inventado (sin autenticar) ya no aparca hilos de IO durante toda una descarga lenta del
 *   JWKS: solo el hilo que descarga espera la red; el resto espera como mucho [JWKS_REFRESH_LOCK_WAIT_MS] y sale con
 *   503 reintentable. Dentro de la ventana anti-tormenta no se toca el lock.
 * - MASTER5-OBS-002: la causa del 503 por JWKS viaja hasta el log; el refresco por caducidad fallido deja un WARN; un
 *   fallo NO esperado del verificador (no un token invalido) deja un WARN antes de convertirse en 401.
 */
class SupabaseJwtVerifierLoop5Test {
    private val uid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Volatile private var hang = false
    @Volatile private var status = 200
    @Volatile private var body = "{}"
    private val hits = AtomicInteger()
    private val release = CountDownLatch(1)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/auth/v1/.well-known/jwks.json") { exchange ->
            hits.incrementAndGet()
            if (hang) release.await(30, TimeUnit.SECONDS)
            val bytes = body.toByteArray()
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }
    private val baseUrl = "http://${server.address.address.hostAddress}:${server.address.port}"

    @Volatile private var now = 1_000_000L
    private val fetchTimeout = Duration.ofMillis(3_000)
    private val verifier = SupabaseJwtVerifier(
        baseUrl,
        Json { ignoreUnknownKeys = true },
        HttpClient.newHttpClient(),
        clock = { now },
        fetchTimeout = fetchTimeout,
    )

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    // ------------------------------------------------------------------ MASTER5-SEC-001

    @Test
    fun unknownKidFloodDuringASlowFetchParksOnlyTheFetchingThread() {
        val known = newKey()
        body = jwks("k1" to known)
        assertEquals(uid, verifier.subFrom(tokenFor("k1", known)))
        assertEquals(1, hits.get())

        hang = true
        now += 10_000 // fuera de la ventana anti-tormenta: el siguiente `kid` desconocido descarga
        val pool = Executors.newFixedThreadPool(9)
        try {
            val fetcherStarted = CountDownLatch(1)
            val fetcher = pool.submit<Throwable?> {
                fetcherStarted.countDown()
                runCatching { verifier.subFrom(tokenFor("attacker-0", newKey())) }.exceptionOrNull()
            }
            fetcherStarted.await()
            // Espera a que la descarga lenta este realmente en curso (el servidor ya la recibio).
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (hits.get() < 2 && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals(2, hits.get(), "la descarga lenta esta en curso")

            val floods = (1..8).map { i ->
                pool.submit<Pair<Long, Throwable?>> {
                    val t0 = System.nanoTime()
                    val failure = runCatching { verifier.subFrom(tokenFor("attacker-$i", newKey())) }.exceptionOrNull()
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) to failure
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            floods.forEach { (elapsed, failure) ->
                assertIs<IOException>(failure, "sin clave y con otra descarga en curso: 503 reintentable")
                assertTrue(
                    elapsed < JWKS_REFRESH_LOCK_WAIT_MS + 700,
                    "un token con kid inventado no espera la descarga entera (${fetchTimeout.toMillis()} ms): ${floods.map { it.first }}",
                )
            }
            assertEquals(2, hits.get(), "ninguna descarga extra por la avalancha")

            // Se libera la descarga antes de su timeout: el JWKS llega sano y el kid inventado es un rechazo (401).
            release.countDown()
            assertIs<JwtRejectedException>(fetcher.get(10, TimeUnit.SECONDS), "JWKS sano + kid desconocido = 401, no 503")
        } finally {
            pool.shutdownNow()
        }

        // Dentro de la ventana tras ese intento: rechazo inmediato, sin red y sin esperar al lock.
        val t0 = System.nanoTime()
        assertFailsWith<JwtRejectedException> { verifier.subFrom(tokenFor("attacker-9", newKey())) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 200)
        assertEquals(2, hits.get())
    }

    @Test
    fun aHungFetchThatTimesOutIsNegativelyCachedAndTheNextFloodIsImmediate() {
        hang = true
        val first = assertFailsWith<IOException> { verifier.subFrom(tokenFor("attacker-0", newKey())) }
        assertTrue(first !is java.io.InterruptedIOException, first.toString())
        assertEquals(1, hits.get())
        val t0 = System.nanoTime()
        repeat(20) { assertFailsWith<IOException> { verifier.subFrom(tokenFor("attacker-$it", newKey())) } }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 500, "cache negativa: sin red ni lock")
        assertEquals(1, hits.get())
    }

    @Test
    fun aValidNewKidStillVerifiesOnceTheWindowPasses() {
        val old = newKey()
        body = jwks("k1" to old)
        assertEquals(uid, verifier.subFrom(tokenFor("k1", old)))
        val rotated = newKey()
        body = jwks("k1" to old, "k2" to rotated)
        now += 10_000
        assertEquals(uid, verifier.subFrom(tokenFor("k2", rotated)), "una rotacion legitima se acepta sin espera extra")
        // Y dentro de la ventana, un kid desconocido con un JWKS sano es rechazo (401), no 503.
        val failure = assertFailsWith<Exception> { verifier.subFrom(tokenFor("nope", newKey())) }
        assertFalse(failure is IOException)
        assertIs<JwtRejectedException>(failure)
        assertEquals(2, hits.get())
    }

    // ------------------------------------------------------------------ MASTER5-OBS-002

    private fun <T> capture(loggerName: String, level: Level? = null, block: (ListAppender<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(loggerName) as LogbackLogger
        val previous = logger.level
        level?.let { logger.level = it }
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        return try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
    }

    @Test
    fun aFailedStaleRefreshLeavesOneWarningPerBackoff() = capture("OC-KTOR-JWT") { appender ->
        val key = newKey()
        body = jwks("k1" to key)
        val token = tokenFor("k1", key)
        assertEquals(uid, verifier.subFrom(token))

        status = 503
        now += JWKS_MAX_AGE_MS + 1
        assertEquals(uid, verifier.subFrom(token), "fail-soft: la clave en cache sigue valiendo")
        now += 10_000
        assertEquals(uid, verifier.subFrom(token), "dentro del backoff: sin reintento ni log nuevo")
        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("JWKS HTTP 503" in warnings.single(), warnings.toString())
        assertFalse(token in warnings.single(), "nunca el token")
    }

    @Test
    fun theJwks503CarriesItsCauseIntoTheUpstreamLog() = capture("OC-KTOR-UPSTREAM") { appender ->
        status = 500
        val token = tokenFor("k1", newKey())
        val resolver = CanonicalIdentityResolver(verifySubject = verifier::subFrom, directory = { null })
        val direct = assertFailsWith<BackendDomainException> {
            runBlocking { verifiedJwtPrincipalOrNull(token, verifier::subFrom) }
        }
        assertEquals("UPSTREAM_UNAVAILABLE", direct.errorCode)
        assertIs<IOException>(direct.cause, "gateway-auth conserva la causa")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRequestCorrelation()
                installBackendStatusPages()
                routing { get("/probe") { call.respondText(resolver.userId(call).value) } }
            }
            val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val requestId = response.headers["X-Request-Id"]!!
            val lines = appender.list.map { it.formattedMessage }.filter { requestId in it }
            assertTrue(lines.any { "UPSTREAM_UNAVAILABLE" in it && "JWKS HTTP 500" in it }, "el motivo real llega al log: $lines")
        }
    }

    @Test
    fun onlyUnexpectedVerifierFailuresWarnAndExpectedRejectionsStayQuiet() = capture("OC-KTOR-JWT", Level.DEBUG) { appender ->
        val bug = NullPointerException("clave del JWKS sin coordenada")
        assertEquals(null, runBlocking { verifiedJwtPrincipalOrNull("a.b.c") { throw bug } }, "sigue siendo 401")
        assertEquals(null, runBlocking { verifiedJwtPrincipalOrNull("a.b.c") { throw JWTDecodeException("basura") } })
        assertEquals(null, runBlocking { verifiedJwtPrincipalOrNull("a.b.c") { throw JwtRejectedException("kid no encontrado en JWKS") } })
        val warnings = appender.list.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, appender.list.map { it.formattedMessage }.toString())
        assertTrue("NullPointerException" in warnings.single().formattedMessage)
        assertEquals(2, appender.list.count { it.level == Level.DEBUG }, "los rechazos esperados, solo en DEBUG")
        assertTrue(appender.list.none { "basura" in it.formattedMessage }, "de un rechazo esperado solo la clase")
    }

    @Test
    fun theCanonicalResolverKeepsTheJwksCauseToo() {
        val io = IOException("JWKS HTTP 502")
        val resolver = CanonicalIdentityResolver(verifySubject = { throw io }, directory = { null })
        testApplication {
            var captured: Throwable? = null
            application {
                routing {
                    get("/probe") {
                        captured = runCatching { resolver.userId(call) }.exceptionOrNull()
                        call.respondText("ok")
                    }
                }
            }
            client.get("/probe") { header(HttpHeaders.Authorization, "Bearer a.b.c") }
            val failure = assertIs<BackendDomainException>(captured)
            assertEquals("UPSTREAM_UNAVAILABLE", failure.errorCode)
            // La recuperacion de trazas de corrutinas puede copiar la excepcion al cruzar `withUpstreamIo`: clase y motivo.
            val cause = assertIs<IOException>(failure.cause)
            assertEquals(io.message, cause.message)
        }
    }

    private fun newKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun b64(v: BigInteger): String {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val padded = ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
    }

    private fun jwks(vararg keys: Pair<String, KeyPair>): String =
        keys.joinToString(prefix = """{"keys":[""", postfix = "]}") { (kid, kp) ->
            val w = (kp.public as ECPublicKey).w
            """{"kty":"EC","crv":"P-256","kid":"$kid","x":"${b64(w.affineX)}","y":"${b64(w.affineY)}"}"""
        }

    private fun tokenFor(kid: String, kp: KeyPair): String =
        JWT.create().withKeyId(kid).withIssuer("$baseUrl/auth/v1").withSubject(uid)
            .sign(Algorithm.ECDSA256(kp.public as ECPublicKey, kp.private as ECPrivateKey))
}
