package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeSource

/**
 * Observability foundation (logs estrategicos + request correlation +
 * health/readiness). NO es Sentry, NO es Prometheus -- ver
 * [StrategicEvent] y las secciones "future Sentry"/"future Prometheus" mas
 * abajo para los puntos de enganche futuros.
 *
 * IMPORTANTE -- no duplica lo que ya existe:
 * `OposiBotsAuthRoutes.kt`/`OposiBotsAuthGateway.kt` (OposiBot, fuera de
 * alcance de esta tarea) ya implementan su PROPIO request id
 * (`X-Request-Id` entrante o `UUID.randomUUID()`, mismo formato de validacion
 * que aqui) y su propio logger estructurado (`OC-KTOR-AUTH`). Esta foundation
 * cubre el RESTO del backend (admin/app/content/health), que no tenia
 * ningun mecanismo. Deliberadamente NO se tocan esos dos archivos: si el
 * cliente no manda `X-Request-Id`, el id que ve `AuthLogger` en las rutas
 * las rutas de OposiBot puede diferir del que esta foundation genera para esa misma
 * request (ambos son UUIDs propios, no hay forma de unificarlos sin tocar
 * OposiBot). Cuando SI llega un `X-Request-Id` valido desde el cliente,
 * ambos mecanismos lo adoptan igual y coinciden. Unificar queda como
 * follow-up documentado, no como parte de esta tarea.
 */

// ---------------------------------------------------------------------
// Configuracion (env vars, sin secrets)
// ---------------------------------------------------------------------

object ObservabilityConfig {
    val environment: String = System.getenv("ENVIRONMENT")?.trim()?.ifBlank { null }
        ?: System.getenv("APP_ENV")?.trim()?.ifBlank { null }
        ?: "unknown"

    /** Umbral de "slow request" en ms. Sin umbral razonable de industria
     * conocido para este backend concreto -- se deja configurable y con un
     * default conservador (1000 ms) documentado aqui en vez de disperso. */
    val slowRequestThresholdMs: Long =
        System.getenv("SLOW_REQUEST_THRESHOLD_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 1000L

    /** Nivel de log real lo controla logback.xml via `${LOG_LEVEL}` (Logback
     * ya resuelve variables de entorno con ese nombre); se expone aqui solo
     * para que quede visible como parte de la configuracion documentada. */
    val logLevel: String = System.getenv("LOG_LEVEL")?.trim()?.ifBlank { null } ?: "INFO"

    /** Tope del handshake TCP hacia Supabase. Corto a proposito -- si no
     * conecta en unos segundos, no va a conectar; fallar rapido libera el
     * hilo de la request. */
    val upstreamConnectTimeoutMs: Long =
        System.getenv("UPSTREAM_CONNECT_TIMEOUT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 5_000L

    /** Tope de connect+respuesta completa para UNA llamada a Supabase (REST
     * o Storage). Mas generoso que el connect timeout porque cubre uploads
     * de recursos/portadas e imports de PDF, que legitimamente tardan mas
     * que un GET simple -- pero sigue siendo un limite finito: ninguna
     * llamada puede colgar el hilo de la request indefinidamente. */
    val upstreamRequestTimeoutMs: Long =
        System.getenv("UPSTREAM_REQUEST_TIMEOUT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 20_000L
}

// ---------------------------------------------------------------------
// HTTP client compartido para las gateways de Supabase (P1: timeouts)
// ---------------------------------------------------------------------

/** Duracion (java.time, la que usan HttpClient/HttpRequest) del timeout de
 * request a Supabase -- ver [ObservabilityConfig.upstreamRequestTimeoutMs]. */
val UPSTREAM_REQUEST_TIMEOUT: Duration = Duration.ofMillis(ObservabilityConfig.upstreamRequestTimeoutMs)

/**
 * Unico punto de construccion del `HttpClient` que usan TODAS las gateways
 * de Supabase (Admin, AdminV1, News, V1, TagSearch, App, Content). Antes
 * cada una creaba su propio `HttpClient.newHttpClient()` sin ningun
 * timeout -- una respuesta colgada de Supabase podia bloquear el hilo de la
 * request indefinidamente (P1 confirmado, ver ObservabilityTest y el
 * informe de auditoria previo). `connectTimeout` aqui cubre el handshake;
 * el timeout de respuesta completa se aplica POR REQUEST con
 * `.timeout(UPSTREAM_REQUEST_TIMEOUT)` en cada `HttpRequest.Builder` (un
 * client-level timeout de Java no cubre eso, solo el connect).
 */
fun newUpstreamHttpClient(): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(ObservabilityConfig.upstreamConnectTimeoutMs))
        .build()

// ---------------------------------------------------------------------
// Taxonomia de eventos estrategicos
// ---------------------------------------------------------------------

/**
 * Taxonomia pequena y deliberada -- NO es "un evento por cada accion".
 * Cada entrada esta wireada en al menos un punto real del codigo, EXCEPTO
 * las marcadas explicitamente como "contrato futuro" (documentado en el
 * KDoc de cada una, nunca se emiten hoy).
 */
enum class StrategicEvent {
    /** JWT ausente/invalido en cualquier endpoint autenticado (AppUnauthorized).
     * Reutiliza este nombre tambien fuera del login literal de OposiBot
     * (que vive en OposiBot, fuera de alcance): la accion operativa es la
     * misma -- credencial rechazada -- y crear un segundo evento solo por
     * la ruta de origen no aporta senal nueva. */
    AUTH_LOGIN_FAILED,

    /** Principal autenticado pero sin rol admin/superadmin (AppForbidden). */
    AUTHORIZATION_DENIED,

    IMPORT_STARTED,

    /** Contrato futuro: `parseRafagaText` parsea y valida en el mismo paso
     * hoy: no hay un momento observable "parseado pero aun no validado"
     * sin cambiar esa funcion. No se emite todavia. */
    IMPORT_PARSED,

    IMPORT_VALIDATION_FAILED,
    IMPORT_PUBLISHED,

    /** Contrato futuro: el import de rafagas actual es todo-o-nada (si hay
     * errores de validacion no se publica nada). No existe hoy un modo
     * parcial real que emitir sin inventarlo. */
    IMPORT_PARTIAL,

    STORAGE_UPLOAD_FAILED,
    REPORT_RESOLVED,
    FEEDBACK_RESOLVED,

    /** Generico: cualquier escritura admin sensible que ya pasa por
     * `AdminV1Gateway.registrarAuditoria` (que ya escribe a `auditoria_log`
     * en Supabase). Este evento es el equivalente operacional en logs, no
     * un segundo sistema de auditoria. */
    ADMIN_ACTION,

    SUPABASE_REQUEST_FAILED,
    UPSTREAM_TIMEOUT,

    /** Cualquier excepcion no controlada que llega a StatusPages sin un
     * handler mas especifico. */
    UNCAUGHT_EXCEPTION,

    SLOW_REQUEST,

    // WORKER_JOB_FAILED: contrato futuro. No existe ningun worker/job queue
    // en este backend hoy (sin evidencia en el codigo) -- no se anade al
    // enum hasta que exista algo real que pueda fallar.
}

// ---------------------------------------------------------------------
// Logging estructurado
// ---------------------------------------------------------------------

private val ObservabilityLogger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY")

/**
 * Unico punto de emision de eventos estrategicos. Campos minimos segun la
 * tarea (timestamp/level los pone Logback; environment sale de
 * [ObservabilityConfig]); el resto son opcionales y NO todos se rellenan en
 * cada evento -- null se representa como "-" para que la linea sea
 * grep-able sin quedar vacia. [requestId] por defecto lee el MDC que
 * [installRequestCorrelation] ya dejo puesto para el hilo de la request
 * (evita tener que pasar el id manualmente por cada capa/gateway).
 *
 * PROHIBIDO pasar aqui: password, JWT completo, refresh token,
 * service_role, api keys, cuerpo de PDF/request, respuestas de examen,
 * contenido de mensajes/hilos, emails o cualquier dato personal
 * innecesario. Preferir IDs internos + metadata tecnica -- ver
 * [redactBackendMessage] para mensajes de error libres.
 */
fun logStrategicEvent(
    event: StrategicEvent,
    requestId: String? = MDC.get(MDC_REQUEST_ID),
    operation: String? = null,
    result: String? = null,
    durationMs: Long? = null,
    entityType: String? = null,
    entityId: String? = null,
    errorType: String? = null,
    userId: String? = null,
    count: Int? = null,
) {
    ObservabilityLogger.info(
        "event={} request_id={} operation={} result={} duration_ms={} entity_type={} entity_id={} " +
            "error_type={} user_id={} count={} environment={}",
        event.name,
        requestId ?: "-",
        operation ?: "-",
        result ?: "-",
        durationMs?.toString() ?: "-",
        entityType ?: "-",
        entityId ?: "-",
        errorType ?: "-",
        userId ?: "-",
        count?.toString() ?: "-",
        ObservabilityConfig.environment,
    )
}

/**
 * Redaction reusable para mensajes de error de Supabase/backend. Delega en
 * [safeBackendMessage] (Application.kt) -- que ya redacta Bearer/apikey y
 * trunca -- para no duplicar la logica de redaccion en dos sitios.
 */
fun Throwable.redactedMessage(defaultMessage: String, maxLength: Int = 300): String =
    safeBackendMessage(defaultMessage, maxLength)

/** true si el mensaje de la causa sugiere un timeout de red real (java.net.http),
 * no un fallo de negocio. Best-effort sobre el texto ya que las gateways
 * envuelven la excepcion original en IllegalStateException (ver
 * AdminV1Gateway.request). No inventa una clasificacion si no hay evidencia. */
internal fun Throwable.looksLikeUpstreamTimeout(): Boolean {
    val text = "${this::class.simpleName} ${message.orEmpty()}"
    return TIMEOUT_MARKERS.any { text.contains(it, ignoreCase = true) }
}

private val TIMEOUT_MARKERS = listOf("HttpTimeoutException", "HttpConnectTimeoutException", "SocketTimeoutException", "ConnectException", "timed out")

// ---------------------------------------------------------------------
// Request correlation (id + duration + slow-request)
// ---------------------------------------------------------------------

internal const val MDC_REQUEST_ID = "request_id"
private const val REQUEST_ID_HEADER = "X-Request-Id"

/** Mismo formato de validacion que OposiBotsAuthRoutes.AuthRequestIdKey
 * (no se importa desde alli para no acoplarse a un archivo de OposiBot). */
private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,128}")

val RequestIdKey = AttributeKey<String>("oc-observability-request-id")
private val RequestStartKey = AttributeKey<TimeSource.Monotonic.ValueTimeMark>("oc-observability-request-start")

/**
 * Instala correlacion de request para TODO el backend excepto OposiBot
 * (ver KDoc del archivo). Flujo: acepta un `X-Request-Id` entrante valido
 * o genera un UUID; lo expone en `call.attributes`, en el MDC (para que
 * cualquier log de esta misma request/hilo -- incluidas las gateways, que
 * no reciben el id por parametro -- lo incluya automaticamente via
 * `%mdc` en logback.xml) y lo devuelve en el response header. Tambien mide
 * la duracion total y emite [StrategicEvent.SLOW_REQUEST] por encima del
 * umbral configurado -- nunca loguea cada request como evento de negocio.
 *
 * Caveat documentado: MDC es ThreadLocal. Mientras las gateways sigan
 * siendo llamadas de forma sincrona desde el mismo hilo del handler (como
 * hoy), el MDC se propaga correctamente. Si en el futuro se introduce
 * `withContext(Dispatchers.IO)` u otro cambio de hilo entre el handler y la
 * gateway, hara falta `kotlinx-coroutines-slf4j` (`MDCContext`) para seguir
 * propagando -- no aplica hoy, se documenta para no sorprender a quien lo
 * toque despues.
 */
fun Application.installRequestCorrelation() {
    intercept(ApplicationCallPipeline.Setup) {
        val incoming = call.request.headers[REQUEST_ID_HEADER]?.takeIf { it.matches(REQUEST_ID_PATTERN) }
        val requestId = incoming ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdKey, requestId)
        call.attributes.put(RequestStartKey, TimeSource.Monotonic.markNow())
        call.response.header(REQUEST_ID_HEADER, requestId)
        MDC.put(MDC_REQUEST_ID, requestId)
        try {
            proceed()
        } finally {
            val elapsedMs = call.attributes.getOrNull(RequestStartKey)?.elapsedNow()?.inWholeMilliseconds
            if (elapsedMs != null && elapsedMs >= ObservabilityConfig.slowRequestThresholdMs) {
                logStrategicEvent(
                    StrategicEvent.SLOW_REQUEST,
                    requestId = requestId,
                    operation = "${call.request.httpMethod.value} ${call.request.path()}",
                    result = call.response.status()?.value?.toString(),
                    durationMs = elapsedMs,
                )
            }
            MDC.remove(MDC_REQUEST_ID)
        }
    }
}

/** Duracion transcurrida desde el inicio de la request hasta ahora mismo,
 * util desde un handler de StatusPages (ver Application.kt). Null si por
 * algun motivo el interceptor de correlacion no llego a ejecutarse. */
fun ApplicationCall.elapsedMsOrNull(): Long? = attributes.getOrNull(RequestStartKey)?.elapsedNow()?.inWholeMilliseconds

fun ApplicationCall.requestIdOrNull(): String? = attributes.getOrNull(RequestIdKey)

// ---------------------------------------------------------------------
// Health / readiness
// ---------------------------------------------------------------------

@Serializable
data class ReadinessResponse(
    val status: String,
    val checks: Map<String, String>,
    val timestamp: String,
)

/** Timeout corto y explicito para la unica llamada de red que hace
 * readiness (probe de alcanzabilidad, no una query de negocio). No viene de
 * config (la tarea solo pide configurar LOG_LEVEL/SLOW_REQUEST_THRESHOLD_MS/
 * ENVIRONMENT) -- 2s es un limite generoso para un GET raiz de PostgREST sin
 * bloquear el probe de orquestacion, documentado aqui en vez de disperso. */
private val READINESS_TIMEOUT: Duration = Duration.ofSeconds(2)

private val readinessHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(READINESS_TIMEOUT)
    .build()

/**
 * Comprueba que Supabase es alcanzable y que la apikey autentica, SIN
 * ejecutar ninguna query de negocio: GET a la raiz de PostgREST
 * (`/rest/v1/`) con la service role key -- responde rapido con su OpenAPI
 * spec, no toca ninguna tabla, no hace INSERT. [url]/[apiKey] por defecto
 * leen las mismas env vars que ya usan el resto de gateways
 * (SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY) -- no introduce una fuente de
 * config nueva; son parametros (en vez de leer env directamente) solo para
 * que ObservabilityTest pueda apuntar a un servidor local de prueba.
 */
internal fun checkSupabaseReachable(
    url: String? = System.getenv("SUPABASE_URL")?.trimEnd('/'),
    apiKey: String? = System.getenv("SUPABASE_SERVICE_ROLE_KEY"),
): String {
    if (url.isNullOrBlank() || apiKey.isNullOrBlank()) return "unknown"
    return try {
        val request = HttpRequest.newBuilder(URI.create("$url/rest/v1/"))
            .header("apikey", apiKey)
            .timeout(READINESS_TIMEOUT)
            .GET()
            .build()
        val response = readinessHttpClient.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() in 200..499) "up" else "down"
    } catch (error: Exception) {
        if (error.looksLikeUpstreamTimeout()) {
            logStrategicEvent(StrategicEvent.UPSTREAM_TIMEOUT, operation = "health_ready_supabase_check", errorType = error::class.simpleName)
        }
        "down"
    }
}

/**
 * `/health/live`: el proceso esta vivo y sirviendo. Nunca depende de
 * dependencias externas -- si esta respondiendo, ya es verdad.
 * `/health/ready`: ademas comprueba dependencias criticas razonables
 * (Supabase) sin operacion pesada. Sin worker: no existe ningun worker/job
 * queue en este backend hoy, asi que no se evalua (ver StrategicEvent
 * KDoc). Ambas rutas quedan fuera de `authenticate("gateway-auth")` a
 * proposito -- igual que la ya existente `/health` -- porque orquestadores
 * externos (load balancer, k8s) no pueden mandar un Bearer token.
 */
fun Route.observabilityHealthRoutes(supabaseCheck: () -> String = ::checkSupabaseReachable) {
    get("/health/live") {
        call.respond(mapOf("status" to "ok"))
    }
    get("/health/ready") {
        val supabaseStatus = supabaseCheck()
        val checks = mapOf("ktor" to "up", "supabase" to supabaseStatus)
        val ready = checks.values.all { it == "up" }
        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            ReadinessResponse(
                status = if (ready) "ready" else "not_ready",
                checks = checks,
                timestamp = Instant.now().toString(),
            ),
        )
    }
}
