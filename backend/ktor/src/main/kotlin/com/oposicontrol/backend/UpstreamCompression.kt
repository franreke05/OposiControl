package com.oposicontrol.backend

import io.micrometer.core.instrument.Counter
import java.io.ByteArrayInputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

/** Bytes de respuesta recibidos de Supabase TAL COMO VIAJAN (comprimidos): es lo que Supabase factura como trafico de salida. */
const val UPSTREAM_WIRE_BYTES_METRIC = "upstream_response_wire_bytes"

/** `rest`, `auth`, `storage` u `other`: cardinalidad fija, nunca la ruta ni la tabla. */
internal fun upstreamTargetOf(uri: URI): String = when {
    uri.path.startsWith("/rest/") -> "rest"
    uri.path.startsWith("/auth/") -> "auth"
    uri.path.startsWith("/storage/") -> "storage"
    else -> "other"
}

private val wireByteCounters = ConcurrentHashMap<String, Counter>()

internal fun recordUpstreamWireBytes(target: String, bytes: Long) {
    wireByteCounters.computeIfAbsent(target) {
        Counter.builder(UPSTREAM_WIRE_BYTES_METRIC)
            .description("Bytes de respuesta recibidos de Supabase (en el cable, comprimidos): trafico de salida facturable")
            .baseUnit("bytes")
            .tag("target", it)
            .register(BackendPrometheusMetrics.registry)
    }.increment(bytes.toDouble())
}

/**
 * Cliente hacia Supabase que pide SIEMPRE la respuesta comprimida y la descomprime antes de entregarla.
 *
 * Incidente 2026-10-10: el backend agoto la cuota de trafico de salida del proyecto. `java.net.http.HttpClient` no negocia
 * compresion por su cuenta, asi que todas las respuestas viajaban sin comprimir; medido en produccion, Supabase devuelve el
 * mismo JSON entre 2,4 y 5,3 veces mas pequeno con `Accept-Encoding: gzip`. Vive en el cliente y no en cada gateway porque
 * TODAS las llamadas a Supabase pasan por [newUpstreamHttpClient]: un solo sitio, ninguna llamada olvidada.
 *
 * Quien llama no nota nada: recibe el cuerpo ya descomprimido a traves del `BodyHandler` que pidio, sin `Content-Encoding`
 * ni `Content-Length` (que describirian el cuerpo comprimido). Una respuesta que llega sin comprimir se entrega tal cual.
 * Cada respuesta suma sus bytes en el cable a [UPSTREAM_WIRE_BYTES_METRIC].
 */
internal class CompressedUpstreamHttpClient(
    private val delegate: HttpClient,
    private val onWireBytes: (target: String, bytes: Long) -> Unit = ::recordUpstreamWireBytes,
) : HttpClient() {
    override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()
    override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()
    override fun followRedirects(): Redirect = delegate.followRedirects()
    override fun proxy(): Optional<ProxySelector> = delegate.proxy()
    override fun sslContext(): SSLContext = delegate.sslContext()
    override fun sslParameters(): SSLParameters = delegate.sslParameters()
    override fun authenticator(): Optional<Authenticator> = delegate.authenticator()
    override fun version(): Version = delegate.version()
    override fun executor(): Optional<Executor> = delegate.executor()

    override fun <T> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        deliver(delegate.send(compressed(request), HttpResponse.BodyHandlers.ofByteArray()), request, responseBodyHandler)

    override fun <T> sendAsync(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
        delegate.sendAsync(compressed(request), HttpResponse.BodyHandlers.ofByteArray())
            .thenApply { deliver(it, request, responseBodyHandler) }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

    private fun compressed(request: HttpRequest): HttpRequest =
        HttpRequest.newBuilder(request) { name, _ -> !name.equals("Accept-Encoding", ignoreCase = true) }
            .header("Accept-Encoding", "gzip")
            .build()

    private fun <T> deliver(raw: HttpResponse<ByteArray>, request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val wire = raw.body() ?: ByteArray(0)
        onWireBytes(upstreamTargetOf(request.uri()), wire.size.toLong())
        val gzip = raw.headers().firstValue("Content-Encoding").map { it.trim().equals("gzip", ignoreCase = true) }.orElse(false)
        val plain = if (gzip && wire.isNotEmpty()) GZIPInputStream(ByteArrayInputStream(wire)).use { it.readBytes() } else wire
        val headers = if (!gzip) {
            raw.headers()
        } else {
            HttpHeaders.of(
                raw.headers().map().filterKeys { !it.equals("Content-Encoding", ignoreCase = true) && !it.equals("Content-Length", ignoreCase = true) },
            ) { _, _ -> true }
        }
        val info = object : HttpResponse.ResponseInfo {
            override fun statusCode(): Int = raw.statusCode()
            override fun headers(): HttpHeaders = headers
            override fun version(): Version = raw.version()
        }
        val subscriber = handler.apply(info)
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() = Unit
        })
        if (plain.isNotEmpty()) subscriber.onNext(listOf(ByteBuffer.wrap(plain)))
        subscriber.onComplete()
        val body: T = subscriber.body.toCompletableFuture().join()
        return object : HttpResponse<T> {
            override fun statusCode(): Int = raw.statusCode()
            override fun request(): HttpRequest = request
            override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
            override fun headers(): HttpHeaders = headers
            override fun body(): T = body
            override fun sslSession(): Optional<SSLSession> = raw.sslSession()
            override fun uri(): URI = raw.uri()
            override fun version(): Version = raw.version()
        }
    }
}
