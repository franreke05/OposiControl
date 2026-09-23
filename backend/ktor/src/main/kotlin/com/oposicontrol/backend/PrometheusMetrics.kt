package com.oposicontrol.backend

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** Registro único del proceso, compartido por el plugin HTTP/JVM y `/metrics`. */
internal object BackendPrometheusMetrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
}

internal fun Application.installBackendPrometheusMetrics(
    registry: PrometheusMeterRegistry = BackendPrometheusMetrics.registry,
) {
    install(MicrometerMetrics) {
        this.registry = registry
        metricName = KTOR_HTTP_METRIC
        distributionStatisticConfig = DistributionStatisticConfig.Builder()
            .percentilesHistogram(true)
            .build()
    }
}

internal fun Route.prometheusMetricsRoute(
    registry: PrometheusMeterRegistry = BackendPrometheusMetrics.registry,
) {
    get("/metrics") {
        call.respondText(
            text = registry.scrape(),
            contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }
}

private const val KTOR_HTTP_METRIC = "ktor.http.server.requests"
private const val DEFAULT_PROMETHEUS_URL = "http://oposicontrol-prometheus:9090"

internal data class SystemMetricsSnapshot(
    val cpuPercent: Double?,
    val ramPercent: Double?,
    val apiLatencyMs: Long?,
    val errors24h: Long?,
)

internal fun interface SystemMetricsProvider {
    fun snapshot(): SystemMetricsSnapshot
}

/**
 * Cliente de la API HTTP de Prometheus. Cada serie falla de forma independiente:
 * una respuesta vacía, no finita o malformada nunca se convierte en cero.
 */
internal class PrometheusSystemMetricsGateway(
    baseUrl: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SystemMetricsProvider {
    private val queryEndpoint = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_PROMETHEUS_URL }

    override fun snapshot(): SystemMetricsSnapshot {
        // Las cuatro consultas salen a la vez: si Prometheus no responde, el
        // endpoint administrativo espera un único timeout, no cuatro seguidos.
        val cpu = queryAsync(CPU_QUERY)
        val ram = queryAsync(RAM_QUERY)
        val latency = queryAsync(API_LATENCY_QUERY)
        val errors = queryAsync(ERRORS_24H_QUERY)
        return SystemMetricsSnapshot(
            cpuPercent = cpu.join()?.coerceIn(0.0, 100.0),
            ramPercent = ram.join()?.coerceIn(0.0, 100.0),
            apiLatencyMs = latency.join()?.takeIf { it >= 0.0 }?.times(1_000)?.roundToLong(),
            errors24h = errors.join()?.takeIf { it >= 0.0 }?.roundToLong(),
        )
    }

    private fun queryAsync(promql: String): CompletableFuture<Double?> {
        return runCatching {
            val encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8).replace("+", "%20")
            val request = HttpRequest.newBuilder(URI.create("$queryEndpoint/api/v1/query?query=$encoded"))
                .timeout(requestTimeout)
                .GET()
                .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle { response, error ->
                    if (error != null || response.statusCode() !in 200..299) null
                    else parsePrometheusInstantValue(response.body(), json)
                }
        }.getOrElse { CompletableFuture.completedFuture(null) }
    }

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): PrometheusSystemMetricsGateway =
            PrometheusSystemMetricsGateway(
                environment("PROMETHEUS_URL").orEmpty().ifBlank { DEFAULT_PROMETHEUS_URL },
            )
    }
}

internal fun parsePrometheusInstantValue(body: String, json: Json = Json): Double? = runCatching {
    val root = json.parseToJsonElement(body).jsonObject
    if (root["status"]?.jsonPrimitive?.contentOrNull != "success") return@runCatching null
    val data = root["data"]?.jsonObject ?: return@runCatching null
    if (data["resultType"]?.jsonPrimitive?.contentOrNull != "vector") return@runCatching null
    val results = data["result"]?.jsonArray ?: return@runCatching null
    if (results.size != 1) return@runCatching null
    val value = results.single().jsonObject["value"] as? JsonArray ?: return@runCatching null
    val number = value.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    number?.takeIf(Double::isFinite)
}.getOrNull()

internal const val CPU_QUERY =
    "100 - (avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)"
internal const val RAM_QUERY =
    "100 * (1 - (sum(node_memory_MemAvailable_bytes) / sum(node_memory_MemTotal_bytes)))"
internal const val API_LATENCY_QUERY =
    "histogram_quantile(0.95, sum by (le) (rate(ktor_http_server_requests_seconds_bucket{route!=\"/metrics\"}[5m])))"
internal const val ERRORS_24H_QUERY =
    "(round(sum(increase(ktor_http_server_requests_seconds_count{status=~\"5..\"}[24h]))) " +
        "or on() vector(0)) and on() " +
        "(count(ktor_http_server_requests_seconds_count offset 24h) > 0)"
