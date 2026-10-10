package com.oposicontrol.backend

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Incidente 2026-10-10 (cuota de trafico de salida de Supabase): toda llamada a Supabase pide la respuesta comprimida, quien
 * llama recibe el cuerpo ya descomprimido y los bytes del cable quedan contados. Servidor HTTP real de la JDK al otro lado.
 */
class CompressedUpstreamHttpClientTest {
    private val json = """{"data":[""" + (1..200).joinToString(",") { """{"id":$it,"titulo":"Pregunta numero $it de prueba"}""" } + "]}"

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun withServer(compress: Boolean, status: Int = 200, block: (base: String, acceptEncodings: List<String?>) -> Unit) {
        val seen = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange.requestHeaders.getFirst("Accept-Encoding")
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(status, -1)
            } else {
                val body = if (compress) gzip(json) else json.toByteArray()
                if (compress) exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", seen)
        } finally {
            server.stop(0)
        }
    }

    private fun client(recorded: MutableList<Pair<String, Long>>) =
        CompressedUpstreamHttpClient(HttpClient.newHttpClient()) { target, bytes -> recorded += target to bytes }

    @Test
    fun everyRequestAsksForGzipAndTheCallerGetsThePlainBody() = withServer(compress = true) { base, acceptEncodings ->
        val recorded = mutableListOf<Pair<String, Long>>()
        val response = client(recorded).send(
            HttpRequest.newBuilder(URI.create("$base/rest/v1/preguntas?select=*")).header("apikey", "k").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals(json, response.body(), "el cuerpo llega descomprimido, identico al original")
        assertEquals(listOf<String?>("gzip"), acceptEncodings)
        assertFalse(response.headers().firstValue("Content-Encoding").isPresent, "la cabecera describiria un cuerpo que ya no esta comprimido")
        val (target, bytes) = recorded.single()
        assertEquals("rest", target)
        assertEquals(gzip(json).size.toLong(), bytes, "se cuentan los bytes del cable, no los descomprimidos")
        assertTrue(bytes * 3 < json.toByteArray().size, "el control: este cuerpo comprime mas de 3 veces")
    }

    @Test
    fun anUncompressedAnswerIsDeliveredAsIsAndErrorStatusesKeepTheirBody() = withServer(compress = false, status = 409) { base, _ ->
        val recorded = mutableListOf<Pair<String, Long>>()
        val response = client(recorded).send(
            HttpRequest.newBuilder(URI.create("$base/auth/v1/token")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(409, response.statusCode())
        assertEquals(json, response.body())
        assertEquals(listOf("auth" to json.toByteArray().size.toLong()), recorded)
    }

    @Test
    fun aBodylessAnswerWorksWithADiscardingHandlerAndAsync() = withServer(compress = true) { base, _ ->
        val recorded = mutableListOf<Pair<String, Long>>()
        val head = client(recorded).send(
            HttpRequest.newBuilder(URI.create("$base/rest/v1/")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, head.statusCode())
        assertEquals(listOf("rest" to 0L), recorded)
        val async = client(recorded).sendAsync(
            HttpRequest.newBuilder(URI.create("$base/storage/v1/object/x")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).join()
        assertEquals(json, async.body())
        assertEquals("storage", recorded.last().first)
    }

    @Test
    fun wireBytesAreExportedUnderTheNameTheAlertRulesUse() {
        recordUpstreamWireBytes("rest", 10)
        assertTrue(
            BackendPrometheusMetrics.registry.scrape().lines().any { it.startsWith("upstream_response_wire_bytes_total{") && "target=\"rest\"" in it },
            "ops/monitoring/prometheus/alert_rules.yml usa upstream_response_wire_bytes_total",
        )
    }

    @Test
    fun theSharedFactoryReturnsTheCompressingClientAndKeepsItsConnectTimeout() {
        val shared = newUpstreamHttpClient()
        assertTrue(shared is CompressedUpstreamHttpClient)
        assertTrue(shared.connectTimeout().isPresent)
    }
}
