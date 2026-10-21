package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CERT-LOAD-1000 P1: la comprobacion del limitador en Valkey ya no necesita un hilo de `Dispatchers.IO`. Con el pool IO
 * entero bloqueado (como bajo la carga medida, donde las llamadas a Supabase llenaban los 64 hilos) la comprobacion
 * contesta igual, y un Valkey mudo sigue fallando CERRADO con 503 dentro de su propio tope.
 */
class ValkeyRateLimiterIoIsolationTest {

    /** Servidor RESP minimo: a EVAL contesta `[1, 60000]` (permitido) o, si [mute], nada. */
    private class FakeValkey(private val mute: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val sockets = CopyOnWriteArrayList<java.net.Socket>()

        init {
            thread(isDaemon = true, name = "fake-valkey-accept") {
                try {
                    while (true) {
                        val socket = server.accept()
                        sockets += socket
                        thread(isDaemon = true, name = "fake-valkey-conn") { serve(socket) }
                    }
                } catch (_: SocketException) {
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) return
                    val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                    // Lettuce encadena su saludo (CLIENT SETINFO ...) en una sola escritura: una respuesta por comando.
                    val commands = Regex("\\*\\d+\r\n").findAll(text).count().coerceAtLeast(1)
                    val reply = when {
                        "EVAL" in text -> if (mute) null else "*2\r\n:1\r\n:60000\r\n"
                        "PING" in text -> "+PONG\r\n".repeat(commands)
                        else -> "+OK\r\n".repeat(commands)
                    } ?: continue
                    output.write(reply.toByteArray())
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

    /** Ocupa TODOS los hilos de Dispatchers.IO (64 por defecto) hasta que se suelte el cerrojo. */
    private fun saturateIo(release: CountDownLatch): CountDownLatch {
        val busy = CountDownLatch(IO_THREADS)
        runBlocking {
            repeat(IO_THREADS + 16) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    busy.countDown()
                    release.await()
                }
            }
        }
        assertTrue(busy.await(10, TimeUnit.SECONDS), "no se pudo saturar el pool IO")
        return busy
    }

    @Test
    fun theCheckAnswersWhileEveryIoThreadIsBlocked() {
        FakeValkey(mute = false).use { valkey ->
            val limiter = ValkeyOposiBotsAuthRateLimiter(
                url = "redis://127.0.0.1:${valkey.port}",
                hasher = RateLimitKeyHasher("test-only-rate-limit-secret-32chars"),
                commandTimeout = Duration.ofMillis(1_500),
            )
            val release = CountDownLatch(1)
            try {
                runBlocking { limiter.checkSubject(OposiBotsAuthOperation.ATTEMPT_ANSWER, "warm-up") } // conexion lista
                saturateIo(release)
                val started = System.nanoTime()
                // Con la version anterior (sync en Dispatchers.IO) esto esperaba un hilo IO que no llega: el withTimeout la hace fallar.
                val decision = runBlocking { kotlinx.coroutines.withTimeout(3_000) { limiter.checkSubject(OposiBotsAuthOperation.ATTEMPT_ANSWER, "user-1") } }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue(decision.allowed)
                assertEquals(60, decision.retryAfterSeconds)
                assertTrue(elapsedMs < 1_000, "con el pool IO lleno la comprobacion tardo $elapsedMs ms (antes: esperaba un hilo y fallaba con 503)")
            } finally {
                release.countDown()
                limiter.close()
            }
        }
    }

    @Test
    fun aMuteValkeyStillFailsClosedWithA503WithinItsOwnTimeoutEvenWithIoSaturated() {
        FakeValkey(mute = true).use { valkey ->
            val limiter = ValkeyOposiBotsAuthRateLimiter(
                url = "redis://127.0.0.1:${valkey.port}",
                hasher = RateLimitKeyHasher("test-only-rate-limit-secret-32chars"),
                connectTimeout = Duration.ofMillis(500),
                commandTimeout = Duration.ofMillis(400),
            )
            val release = CountDownLatch(1)
            try {
                saturateIo(release)
                val started = System.nanoTime()
                val failure = assertFailsWith<BackendDomainException> {
                    runBlocking { kotlinx.coroutines.withTimeout(3_000) { limiter.checkIp(OposiBotsAuthOperation.LOGIN, "203.0.113.5") } }
                }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
                assertEquals("UPSTREAM_UNAVAILABLE", failure.errorCode, "falla cerrado: sin limitador no se atiende")
                assertTrue(elapsedMs < 3_000, "fallo en $elapsedMs ms")
            } finally {
                release.countDown()
                limiter.close()
            }
        }
    }

    private companion object {
        const val IO_THREADS = 64
    }
}
