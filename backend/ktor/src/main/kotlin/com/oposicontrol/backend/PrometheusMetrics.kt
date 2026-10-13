package com.oposicontrol.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.header
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
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
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
    registerSupabaseUpGauge(registry)
    registerBuildInfoGauge(registry)
}

/** Serie `build_info{sha="..."} 1`: siempre 1; lo que informa es su etiqueta. Convencion habitual de Prometheus para publicar una version. */
const val BUILD_INFO_METRIC = "build_info"

/** Lo que se publica cuando el build no pudo preguntar a git (o el recurso no esta): nunca un SHA inventado. */
internal const val UNKNOWN_BUILD_SHA = "desconocido"

private const val BUILD_INFO_RESOURCE = "build-info.properties"

/**
 * SHA de git con el que se construyo ESTE jar. Lo escribe la tarea `generateBuildInfo` de `backend/ktor/build.gradle.kts`
 * en un recurso del classpath: horneado en el artefacto, no leido de una variable de entorno que el despliegue podria
 * dejar apuntando a otra version que la que de verdad corre.
 */
internal fun backendBuildSha(
    resource: () -> java.io.InputStream? = { BackendPrometheusMetrics::class.java.classLoader.getResourceAsStream(BUILD_INFO_RESOURCE) },
): String = runCatching {
    resource()?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("sha") }
}.getOrNull()?.trim().orEmpty().ifBlank { UNKNOWN_BUILD_SHA }

/**
 * `build_info{sha="..."} 1`: cada replica dice que version corre, y el estado del sistema puede ver un despliegue a
 * medias (replicas con SHA distinto). Va solo en `/metrics`, que no sale a Internet; NO se publica en `/health`.
 */
internal fun registerBuildInfoGauge(registry: PrometheusMeterRegistry, sha: String = backendBuildSha()) {
    // NO es un `Gauge` de Micrometer: el cliente de Prometheus reserva el sufijo `_info` para las metricas de tipo Info y
    // se lo quita a cualquier otra, asi que un gauge llamado `build_info` se exportaba como `build{sha=...}` (comprobado
    // en PrometheusMetricsTest). Registrada como Info en el registro de Prometheus que Micrometer lleva dentro, sale con
    // el nombre que consulta BUILD_INFO_QUERY.
    // Una sola vez por registro: el registro de Prometheus rechaza un nombre repetido, y el modulo se instala mas de una
    // vez sobre el registro del proceso en las pruebas. La version de un proceso no cambia mientras corre.
    if (!registriesWithBuildInfo.add(registry)) return
    val info = io.prometheus.metrics.core.metrics.Info.builder()
        .name(BUILD_INFO_METRIC)
        .help("Version desplegada: la etiqueta sha es el commit de git con el que se construyo el jar")
        .labelNames("sha")
        .build()
    info.setLabelValues(sha)
    registry.prometheusRegistry.register(info)
}

/** Por identidad y con referencias debiles: un registro de prueba descartado no se queda retenido aqui. */
private val registriesWithBuildInfo: MutableSet<PrometheusMeterRegistry> =
    java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

/** 1 = la ultima sonda a Supabase respondio; 0 = no. Es lo que permite ALERTAR de una caida de Supabase sin trafico. */
const val UPSTREAM_SUPABASE_UP_METRIC = "upstream_supabase_up"

/**
 * Incidente 2026-10-10: Supabase estuvo 11 horas sin responder y nadie lo vio, porque ninguna metrica lo decia. El valor se
 * calcula al raspar `/metrics` con la MISMA sonda cacheada de `/health/ready` (un HEAD sin cuerpo, como mucho uno cada
 * 30 s por replica). Registrar dos veces devuelve el mismo medidor.
 */
internal fun registerSupabaseUpGauge(registry: PrometheusMeterRegistry, readiness: () -> String = ::checkSupabaseReadiness) {
    io.micrometer.core.instrument.Gauge.builder(UPSTREAM_SUPABASE_UP_METRIC) { if (readiness() == "UP") 1.0 else 0.0 }
        .description("1 si la ultima sonda a Supabase respondio, 0 si no")
        .register(registry)
}

/**
 * D8-09 (SEC-007/SEC-003): `METRICS_ENABLED=false` → 404; si `METRICS_SCRAPE_TOKEN`
 * está definido, se exige `Authorization: Bearer <token>` (comparación en tiempo
 * constante) o 401. Sin token configurado, el scrape queda abierto.
 */
internal fun Route.prometheusMetricsRoute(
    registry: PrometheusMeterRegistry = BackendPrometheusMetrics.registry,
    environment: (String) -> String? = System::getenv,
) {
    get("/metrics") {
        // PF3-3-02: nunca cacheable por un proxy intermedio (200, 401 ni 404).
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (environment("METRICS_ENABLED")?.trim()?.equals("false", ignoreCase = true) == true) {
            call.respondText("Not Found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        val token = environment("METRICS_SCRAPE_TOKEN")?.trim().orEmpty()
        if (token.isNotBlank()) {
            val provided = call.request.headers[HttpHeaders.Authorization].orEmpty()
            // Token de scrape ENTRANTE (no firma peticiones a Supabase): concatenado para no
            // confundirse con la firma a mano que vigila SupabaseEnvironmentWiringTest.
            val expected = "Bearer " + token
            if (!MessageDigest.isEqual(
                    provided.toByteArray(StandardCharsets.UTF_8),
                    expected.toByteArray(StandardCharsets.UTF_8),
                )
            ) {
                call.respondText("Unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                return@get
            }
        }
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

/**
 * `NaN`/`+Infinity`/`-Infinity` nunca deben cruzar la frontera del contrato
 * JSON: kotlinx.serialization los rechaza al codificar (lanzaría en el
 * `respond`), y `coerceIn`/`roundToLong` los dejarían pasar o los convertirían
 * en un número inventado en vez de una ausencia. Se recortan aquí, en el punto
 * más cercano a Prometheus, para que ningún consumidor tenga que repetir el
 * chequeo.
 */
internal fun Double?.finiteOrNull(): Double? = this?.takeIf(Double::isFinite)

internal fun interface SystemMetricsProvider {
    fun snapshot(): SystemMetricsSnapshot
}

/** Una replica de Ktor segun `up{job="ktor"}`, con lo que ella misma publica (si esta en pie). */
internal data class ReplicaReading(
    val name: String,
    val profile: String? = null,
    val up: Boolean,
    /** `null` = la replica no publica `upstream_supabase_up` (o esta caida y su serie ha caducado). */
    val supabaseUp: Boolean? = null,
    val sha: String? = null,
    val startedAtEpochSeconds: Double? = null,
)

internal data class AlertReading(
    val name: String,
    val severity: String? = null,
    val summary: String? = null,
    val replica: String? = null,
)

/** Bytes recibidos de Supabase. Cada campo falla por separado: `null` = sin lectura, 0 = medido y a cero. */
internal data class SupabaseEgressReading(
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
    val byTarget24h: Map<String, Long>? = null,
    val byTarget7d: Map<String, Long>? = null,
    /** La serie ya existia hace siete dias: sin esto, `bytes7d` cubre menos de una semana y no vale para proyectar. */
    val hasSevenDaysOfHistory: Boolean = false,
)

/**
 * Lo que Prometheus sabe del despliegue: replicas, alertas y trafico de Supabase.
 *
 * `null` en [replicas] o [alerts] significa «no se ha podido leer» (Prometheus no responde, o no tiene la serie de
 * replicas); una lista vacia de alertas significa «ninguna activa». [prometheusResponded] separa las dos causas de un
 * `null`: si es `false`, Prometheus no contesto a nada.
 */
internal data class SystemTopologySnapshot(
    val prometheusResponded: Boolean,
    val replicas: List<ReplicaReading>? = null,
    val alerts: List<AlertReading>? = null,
    val egress: SupabaseEgressReading = SupabaseEgressReading(),
)

/** Separado de [SystemMetricsProvider] para que los dobles de prueba de uno no tengan que inventar el otro. */
internal fun interface SystemTopologyProvider {
    fun topology(): SystemTopologySnapshot
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
) : SystemMetricsProvider, SystemTopologyProvider {
    private val queryEndpoint = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_PROMETHEUS_URL }

    /**
     * Replicas, alertas y trafico de Supabase. Las consultas son constantes de este fichero: el panel no envia PromQL,
     * solo pide el resumen. Salen todas a la vez, igual que en [snapshot], para esperar un unico timeout.
     */
    override fun topology(): SystemTopologySnapshot {
        val up = queryVectorAsync(REPLICAS_UP_QUERY)
        val supabaseUp = queryVectorAsync(REPLICAS_SUPABASE_UP_QUERY)
        val buildInfo = queryVectorAsync(BUILD_INFO_QUERY)
        val startTime = queryVectorAsync(PROCESS_START_TIME_QUERY)
        val alerts = queryVectorAsync(FIRING_ALERTS_QUERY)
        val egress24h = queryVectorAsync(SUPABASE_EGRESS_24H_QUERY)
        val egress7d = queryVectorAsync(SUPABASE_EGRESS_7D_QUERY)
        val egressByTarget24h = queryVectorAsync(SUPABASE_EGRESS_BY_TARGET_24H_QUERY)
        val egressByTarget7d = queryVectorAsync(SUPABASE_EGRESS_BY_TARGET_7D_QUERY)
        val egressHistory = queryVectorAsync(SUPABASE_EGRESS_HISTORY_7D_QUERY)
        val all = listOf(
            up, supabaseUp, buildInfo, startTime, alerts,
            egress24h, egress7d, egressByTarget24h, egressByTarget7d, egressHistory,
        ).map { it.join() }

        val supabaseByReplica = supabaseUp.join().byReplica { it.value }
        // Si una replica publicara dos SHA a la vez no hay forma de saber cual corre: se queda sin version, no con una al azar.
        val shaByReplica = buildInfo.join().orEmpty()
            .groupBy({ it.labels[REPLICA_LABEL] }, { it.labels["sha"] })
            .mapValues { (_, shas) -> shas.filterNotNull().distinct().singleOrNull() }
        val startByReplica = startTime.join().byReplica { it.value }
        return SystemTopologySnapshot(
            // Un vector vacio TAMBIEN es una respuesta: Prometheus contesto y no tiene esa serie.
            prometheusResponded = all.any { it != null },
            replicas = up.join()
                ?.mapNotNull { sample ->
                    // Sin etiqueta `replica` (scrape antiguo, un solo Ktor) queda el `instance` que pone Prometheus.
                    val name = sample.labels[REPLICA_LABEL] ?: sample.labels["instance"] ?: return@mapNotNull null
                    val key = sample.labels[REPLICA_LABEL]
                    ReplicaReading(
                        name = name,
                        profile = sample.labels["profile"],
                        up = sample.value == 1.0,
                        supabaseUp = supabaseByReplica[key]?.let { it == 1.0 },
                        sha = shaByReplica[key],
                        startedAtEpochSeconds = startByReplica[key]?.takeIf { it > 0.0 },
                    )
                }
                ?.sortedBy { it.name }
                // Prometheus contesto pero no raspa ningun Ktor: sigue siendo «no lo se», no «cero replicas».
                ?.takeIf { it.isNotEmpty() },
            alerts = alerts.join()
                ?.mapNotNull { sample ->
                    val name = sample.labels["alertname"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AlertReading(
                        name = name,
                        severity = sample.labels["severity"]?.takeIf { it.isNotBlank() },
                        summary = sample.labels["summary"]?.takeIf { it.isNotBlank() },
                        replica = sample.labels[REPLICA_LABEL]?.takeIf { it.isNotBlank() },
                    )
                }
                ?.distinct()
                ?.sortedWith(compareBy<AlertReading> { if (it.severity == "critical") 0 else 1 }.thenBy { it.name }.thenBy { it.replica.orEmpty() }),
            egress = SupabaseEgressReading(
                bytes24h = egress24h.join().singleByteCount(),
                bytes7d = egress7d.join().singleByteCount(),
                byTarget24h = egressByTarget24h.join().byteCountByTarget(),
                byTarget7d = egressByTarget7d.join().byteCountByTarget(),
                hasSevenDaysOfHistory = (egressHistory.join()?.singleOrNull()?.value ?: 0.0) > 0.0,
            ),
        )
    }

    private fun queryVectorAsync(promql: String): CompletableFuture<List<PrometheusSample>?> =
        fetchAsync(promql).thenApply { body -> body?.let { parsePrometheusVector(it, json) } }

    override fun snapshot(): SystemMetricsSnapshot {
        // Las cuatro consultas salen a la vez: si Prometheus no responde, el
        // endpoint administrativo espera un único timeout, no cuatro seguidos.
        val cpu = queryAsync(CPU_QUERY)
        val ram = queryAsync(RAM_QUERY)
        val latency = queryAsync(API_LATENCY_QUERY)
        val errors = queryAsync(ERRORS_24H_QUERY)
        // Cada valor pasa por finiteOrNull() antes de cualquier otra operación:
        // NaN/±Infinity nunca deben llegar a coerceIn/roundToLong, que los
        // dejarían pasar (coerceIn) o los convertirían en un Long inventado
        // (roundToLong de +Infinity da Long.MAX_VALUE, no una ausencia).
        return SystemMetricsSnapshot(
            cpuPercent = cpu.join().finiteOrNull()?.coerceIn(0.0, 100.0),
            ramPercent = ram.join().finiteOrNull()?.coerceIn(0.0, 100.0),
            apiLatencyMs = latency.join().finiteOrNull()?.takeIf { it >= 0.0 }?.times(1_000)?.roundToLong(),
            errors24h = errors.join().finiteOrNull()?.takeIf { it >= 0.0 }?.roundToLong(),
        )
    }

    private fun queryAsync(promql: String): CompletableFuture<Double?> =
        fetchAsync(promql).thenApply { body -> body?.let { parsePrometheusInstantValue(it, json) } }

    /** El cuerpo de una consulta instantanea, o `null` si Prometheus no contesto con un 2xx. */
    private fun fetchAsync(promql: String): CompletableFuture<String?> {
        return runCatching {
            val encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8).replace("+", "%20")
            val request = HttpRequest.newBuilder(URI.create("$queryEndpoint/api/v1/query?query=$encoded"))
                .timeout(requestTimeout)
                .GET()
                .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle { response, error ->
                    if (error != null || response.statusCode() !in 200..299) null else response.body()
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
    number.finiteOrNull()
}.getOrNull()

/** Una muestra de un vector instantaneo: sus etiquetas y su valor (siempre finito). */
internal data class PrometheusSample(val labels: Map<String, String>, val value: Double)

/**
 * Todas las muestras de un vector instantaneo, con sus etiquetas.
 *
 * `null` = la respuesta no es un vector valido (Prometheus fallo o el cuerpo esta malformado). Una lista VACIA es otra
 * cosa: Prometheus contesto bien y no hay ninguna serie; para `ALERTS` eso es «ninguna alerta», no un fallo. Las muestras
 * sin valor finito se descartan una a una, igual que hace [parsePrometheusInstantValue] con la unica que lee.
 */
internal fun parsePrometheusVector(body: String, json: Json = Json): List<PrometheusSample>? = runCatching {
    val root = json.parseToJsonElement(body).jsonObject
    if (root["status"]?.jsonPrimitive?.contentOrNull != "success") return@runCatching null
    val data = root["data"]?.jsonObject ?: return@runCatching null
    if (data["resultType"]?.jsonPrimitive?.contentOrNull != "vector") return@runCatching null
    val results = data["result"]?.jsonArray ?: return@runCatching null
    results.mapNotNull { element ->
        val entry = element.jsonObject
        val value = (entry["value"] as? JsonArray)?.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull().finiteOrNull()
            ?: return@mapNotNull null
        val labels = entry["metric"]?.jsonObject.orEmpty()
            .mapNotNull { (name, label) -> (label as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { name to it } }
            .toMap()
        PrometheusSample(labels, value)
    }
}.getOrNull()

private const val REPLICA_LABEL = "replica"

/** Valor por replica; las muestras sin etiqueta `replica` no se pueden atribuir a ninguna y se descartan. */
private fun List<PrometheusSample>?.byReplica(value: (PrometheusSample) -> Double): Map<String?, Double> =
    orEmpty().filter { it.labels[REPLICA_LABEL] != null }.associate { it.labels[REPLICA_LABEL] to value(it) }

/** `sum(increase(...))` devuelve una unica muestra sin etiquetas; cualquier otra forma es «sin lectura». */
private fun List<PrometheusSample>?.singleByteCount(): Long? =
    this?.singleOrNull()?.value?.takeIf { it >= 0.0 }?.roundToLong()

private fun List<PrometheusSample>?.byteCountByTarget(): Map<String, Long>? = this
    ?.mapNotNull { sample ->
        val target = sample.labels["target"] ?: return@mapNotNull null
        sample.value.takeIf { it >= 0.0 }?.let { target to it.roundToLong() }
    }
    ?.toMap()
    // Sin ninguna serie por destino no hay desglose que ensenar; no es un desglose «a cero».
    ?.takeIf { it.isNotEmpty() }

// ---------- Disco de datos de Supabase (endpoint privilegiado) ----------

/**
 * Porcentaje de ocupación del volumen de datos de Postgres en Supabase.
 *
 * Separado de [SystemMetricsProvider] a propósito: no sale de Prometheus, sale
 * del endpoint privilegiado del propio Supabase, y su fallo es independiente
 * del de las series del host.
 */
internal fun interface SupabaseDiskUsageProvider {
    /** `null` = no disponible. NUNCA un cero de relleno. */
    fun diskPercent(): Double?
}

/**
 * Cliente del endpoint Prometheus privilegiado de Supabase.
 *
 * ### Por qué lo consulta Ktor y no Prometheus
 *
 * El endpoint exige la clave de servicio como contraseña HTTP Basic. Si lo
 * raspara Prometheus, esa clave tendría que vivir en un fichero de scrape del
 * servidor de métricas, es decir, en un segundo sitio y con otra política de
 * rotación. Ktor **ya** la tiene en su entorno, así que la credencial no se
 * copia a ninguna parte nueva.
 *
 * ### Por qué es síncrono
 *
 * Es UNA petición, no cuatro como en [PrometheusSystemMetricsGateway], así que
 * no hay nada que solapar entre sí. Lo que sí importa es que no cuelgue la
 * petición administrativa que la contiene: de ahí los timeouts cortos de
 * conexión y de respuesta, que acotan el peor caso a unos pocos segundos.
 *
 * La clave NO aparece nunca en la URL, ni en logs, ni en mensajes de error: el
 * `runCatching` descarta la excepción entera en vez de propagar un mensaje que
 * pudiera arrastrarla.
 */
internal class SupabaseDiskMetricsGateway(
    supabaseUrl: String,
    secretKey: String,
    private val client: HttpClient = CompressedUpstreamHttpClient(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build(),
    ),
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    /**
     * El endpoint devuelve TODAS las metricas del proyecto (cientos de KB) para leer un solo numero que cambia despacio:
     * el resultado se reutiliza este tiempo, por muchas veces que se abra o refresque el estado del sistema.
     */
    private val cacheMillis: Long = 10 * 60_000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : SupabaseDiskUsageProvider {
    private var cachedAtMillis: Long? = null
    private var cached: Double? = null

    @Synchronized
    override fun diskPercent(): Double? {
        val at = cachedAtMillis
        if (at != null && nowMillis() - at < cacheMillis) return cached
        val fresh = fetchDiskPercent()
        // Un fallo no se guarda: la siguiente consulta vuelve a intentarlo.
        if (fresh != null) {
            cached = fresh
            cachedAtMillis = nowMillis()
        }
        return fresh
    }

    private val endpoint = supabaseUrl.trim().trimEnd('/') + PRIVILEGED_METRICS_PATH

    /**
     * El usuario es el literal `service_role`, no el nombre de la clave: así lo
     * exige el endpoint, y la contraseña es la clave de servidor.
     */
    private val basicAuth = "Basic " + Base64.getEncoder()
        .encodeToString("$METRICS_BASIC_USER:$secretKey".toByteArray(StandardCharsets.UTF_8))

    private fun fetchDiskPercent(): Double? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(requestTimeout)
            .header("Authorization", basicAuth)
            .header("Accept", "text/plain")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // 401/403/404/5xx son todos lo mismo para el panel: no disponible.
        if (response.statusCode() !in 200..299) null else parseSupabaseDataDiskPercent(response.body())
    }.getOrNull()

    companion object {
        /**
         * Sin URL o sin clave no hay medición posible, y eso es una ausencia,
         * no un error de arranque: se devuelve un proveedor que siempre dice
         * `null` para que el estado del sistema lo declare no instrumentado.
         */
        fun fromEnvironment(environment: (String) -> String? = System::getenv): SupabaseDiskUsageProvider {
            val url = environment("SUPABASE_URL")?.trim()?.trimEnd('/').orEmpty()
            val secretKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            return if (url.isBlank() || secretKey.isBlank()) {
                SupabaseDiskUsageProvider { null }
            } else {
                SupabaseDiskMetricsGateway(url, secretKey)
            }
        }
    }
}

/**
 * `100 * (1 - avail / size)` sobre el volumen de datos de Postgres.
 *
 * **El filtro por `mountpoint="/data"` es el núcleo de esta función.** El mismo
 * endpoint publica `mountpoint="/"`, que es el disco del sistema operativo de
 * la instancia y ronda el 75 % de ocupación. Tomarlo por el de la base de datos
 * daría un número plausible y falso, que es la peor clase de dato en un panel
 * de estado.
 *
 * Devuelve `null` —no cero— si falta cualquiera de las dos series, si alguna no
 * es finita, o si el par no describe un disco coherente (`size <= 0`, o un
 * disponible negativo o mayor que el total). Un porcentaje negativo recortado a
 * cero se leería como "disco vacío medido", y no es lo que se ha medido.
 */
internal fun parseSupabaseDataDiskPercent(body: String): Double? {
    val size = parsePrometheusSampleValue(body, NODE_FILESYSTEM_SIZE_BYTES, MOUNTPOINT_LABEL, DATA_MOUNTPOINT)
        ?: return null
    val available = parsePrometheusSampleValue(body, NODE_FILESYSTEM_AVAIL_BYTES, MOUNTPOINT_LABEL, DATA_MOUNTPOINT)
        ?: return null
    if (size <= 0.0 || available < 0.0 || available > size) return null
    return (100.0 * (1.0 - available / size)).finiteOrNull()
}

/**
 * Un valor del formato de exposición de Prometheus (texto plano), por nombre de
 * métrica y una etiqueta exacta.
 *
 * No se añade librería: el formato de una muestra es
 * `nombre[{etiquetas}] valor [timestamp]`, y esto es justo lo que hace falta
 * para leer dos series de las ~940 que devuelve el endpoint.
 *
 * Reglas deliberadas:
 * - Las líneas `#` (HELP/TYPE) se ignoran.
 * - El nombre debe coincidir **entero**: `node_filesystem_size_bytes_total`
 *   empieza igual y no es la misma serie.
 * - Las etiquetas se leen respetando comillas y escapes, no partiendo por comas:
 *   un valor de etiqueta puede contener una coma.
 * - Dos muestras coincidentes que NO digan lo mismo son una ambigüedad, no un
 *   dato: se devuelve `null`, igual que [parsePrometheusInstantValue] ante un
 *   vector de tamaño distinto de uno. Repetir el mismo valor sí se tolera,
 *   porque ahí no hay nada que elegir.
 */
internal fun parsePrometheusSampleValue(
    body: String,
    metricName: String,
    labelName: String,
    labelValue: String,
): Double? {
    val matched = mutableListOf<Double?>()
    for (rawLine in body.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || !line.startsWith(metricName)) continue
        val rest = line.substring(metricName.length)
        val valuePart = when {
            rest.startsWith("{") -> {
                val close = rest.labelBlockEnd() ?: continue
                val labels = parsePrometheusLabels(rest.substring(1, close)) ?: continue
                if (labels[labelName] != labelValue) continue
                rest.substring(close + 1)
            }
            // Serie sin etiquetas: sólo puede coincidir si no se pedía ninguna.
            rest.isBlank() || rest.first().isWhitespace() -> {
                if (labelValue.isNotEmpty()) continue
                rest
            }
            else -> continue
        }
        // Una muestra puede llevar timestamp detrás del valor; sólo interesa el
        // primer token. `NaN`/`+Inf` cuentan como coincidencia con valor
        // ausente: la serie existe pero no dice nada utilizable.
        matched += valuePart.trim().substringBefore(' ').substringBefore('\t').toDoubleOrNull().finiteOrNull()
    }
    // `[null]` también tiene un único valor distinto, y ese valor es la ausencia.
    return matched.distinct().singleOrNull()
}

/** Índice de la `}` que cierra el bloque de etiquetas, ignorando las entrecomilladas. */
private fun String.labelBlockEnd(): Int? {
    var insideQuotes = false
    var escaped = false
    for (index in indices) {
        val char = this[index]
        when {
            escaped -> escaped = false
            insideQuotes && char == '\\' -> escaped = true
            char == '"' -> insideQuotes = !insideQuotes
            char == '}' && !insideQuotes -> return index
        }
    }
    return null
}

/** `clave="valor",clave="valor"` → mapa. `null` si el bloque está malformado. */
private fun parsePrometheusLabels(block: String): Map<String, String>? {
    val labels = mutableMapOf<String, String>()
    var index = 0
    while (index < block.length) {
        while (index < block.length && (block[index] == ',' || block[index].isWhitespace())) index++
        if (index >= block.length) break
        val equals = block.indexOf('=', index)
        if (equals < 0 || equals + 1 >= block.length || block[equals + 1] != '"') return null
        val name = block.substring(index, equals).trim()
        if (name.isEmpty()) return null
        val value = StringBuilder()
        var cursor = equals + 2
        var closed = false
        while (cursor < block.length) {
            val char = block[cursor]
            if (char == '\\' && cursor + 1 < block.length) {
                val next = block[cursor + 1]
                value.append(
                    when (next) {
                        'n' -> '\n'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> next
                    },
                )
                cursor += 2
                continue
            }
            if (char == '"') {
                closed = true
                cursor++
                break
            }
            value.append(char)
            cursor++
        }
        if (!closed) return null
        labels[name] = value.toString()
        index = cursor
    }
    return labels
}

internal const val PRIVILEGED_METRICS_PATH = "/customer/v1/privileged/metrics"
internal const val METRICS_BASIC_USER = "service_role"
internal const val NODE_FILESYSTEM_SIZE_BYTES = "node_filesystem_size_bytes"
internal const val NODE_FILESYSTEM_AVAIL_BYTES = "node_filesystem_avail_bytes"
internal const val MOUNTPOINT_LABEL = "mountpoint"

/** El volumen de datos de Postgres. `"/"` es el del sistema operativo. */
internal const val DATA_MOUNTPOINT = "/data"

internal const val CPU_QUERY =
    "100 - (avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)"
internal const val RAM_QUERY =
    "100 * (1 - (sum(node_memory_MemAvailable_bytes) / sum(node_memory_MemTotal_bytes)))"
internal const val API_LATENCY_QUERY =
    "histogram_quantile(0.95, sum by (le) (rate(ktor_http_server_requests_seconds_bucket{route!=\"/metrics\"}[5m])))"
// ---------- Replicas, alertas y trafico de Supabase ----------
// Los nombres de serie son los que de verdad se exportan, no una suposicion:
//  - `up{job="ktor"}` con `replica` y `profile`: ops/monitoring/prometheus/prometheus.production.yml (job `ktor`).
//  - `upstream_supabase_up`: UPSTREAM_SUPABASE_UP_METRIC, un gauge (sin sufijo).
//  - `upstream_response_wire_bytes_total`: UPSTREAM_WIRE_BYTES_METRIC es un contador con unidad `bytes`, y Micrometer le
//    anade `_total` al exportarlo (lo comprueba CompressedUpstreamHttpClientTest y lo usa alert_rules.yml).
//  - `build_info`: BUILD_INFO_METRIC. `process_start_time_seconds`: UptimeMetrics, que Ktor instala por defecto.
//  - `ALERTS{alertstate="firing"}`: la serie sintetica que Prometheus genera por cada regla de alert_rules.yml.
// `job="ktor"` en las dos ultimas del proceso es necesario: Prometheus se raspa a si mismo y tambien publica
// `process_start_time_seconds`.
internal const val REPLICAS_UP_QUERY = "up{job=\"ktor\"}"
internal const val REPLICAS_SUPABASE_UP_QUERY = "upstream_supabase_up"
internal const val BUILD_INFO_QUERY = "build_info{job=\"ktor\"}"
internal const val PROCESS_START_TIME_QUERY = "process_start_time_seconds{job=\"ktor\"}"
internal const val FIRING_ALERTS_QUERY = "ALERTS{alertstate=\"firing\"}"
internal const val SUPABASE_EGRESS_24H_QUERY = "sum(increase(upstream_response_wire_bytes_total[24h]))"
internal const val SUPABASE_EGRESS_7D_QUERY = "sum(increase(upstream_response_wire_bytes_total[7d]))"
internal const val SUPABASE_EGRESS_BY_TARGET_24H_QUERY = "sum by (target) (increase(upstream_response_wire_bytes_total[24h]))"
internal const val SUPABASE_EGRESS_BY_TARGET_7D_QUERY = "sum by (target) (increase(upstream_response_wire_bytes_total[7d]))"

/** Cuantas series del contador existian hace siete dias. Vacio = la serie es mas joven (o la retencion no llega). */
internal const val SUPABASE_EGRESS_HISTORY_7D_QUERY = "count(upstream_response_wire_bytes_total offset 7d)"

internal const val ERRORS_24H_QUERY =
    "(round(sum(increase(ktor_http_server_requests_seconds_count{status=~\"5..\"}[24h]))) " +
        "or on() vector(0)) and on() " +
        "(count(ktor_http_server_requests_seconds_count offset 24h) > 0)"
