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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
 * [StrategicEvent] y las secciones "Sentry"/"Prometheus" mas
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
    /** MASTER3-OBS-003: el MISMO resolvedor que Sentry y el resto del backend ([AppEnvironment]: `APP_ENV`, falla cerrado). */
    val environment: String = AppEnvironment.fromSystemEnvironment()

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
// Sentry / Prometheus — puntos de integracion documentados,
// NADA de esto simula metricas. Ver punto 13/15 de la
// tarea de integracion final: preparar, no montar infraestructura real.
// ---------------------------------------------------------------------

/*
 * Sentry: el SDK YA esta integrado y se inicializa en `initializeSentry` (Application.kt), condicionado a `SENTRY_DSN`;
 * entorno via `sentryEnvironment` (`SENTRY_ENVIRONMENT`, luego [AppEnvironment]) y depuracion via `scrubSentryEvent`/
 * `scrubBreadcrumb`. Reglas de contenido (nunca mandar): JWT, refresh token, service role, password, respuestas de examen,
 * PDFs, bodies completos, contenido de mensajes, emails innecesarios. Si mandar: internal user id, release, environment,
 * platform, request id, error type, operation. Los mensajes de excepcion pasan por [redactedMessage].
 */

/**
 * Prometheus: `/metrics` (D8-09) y `installBackendPrometheusMetrics` YA existen
 * (`PrometheusMetrics.kt`); lo de abajo es lo que sigue SIN instrumentar (no hay
 * gauge de health ni metricas de negocio). Puntos donde
 * se instrumentaria cuando llegue el momento, sin fingir metricas ahora:
 *   - [installRequestCorrelation]: contador de requests y latencia
 *     (p50/p95/p99) por method+path+status -- ya mide duracion, solo falta
 *     alimentar un `Timer`/`Counter` de Micrometer ahi mismo.
 *   - Los handlers de `StatusPages`: contador 4xx/5xx por tipo de error.
 *   - [observabilityHealthRoutes]: NO hay gauge de health (1/0); solo el estado
 *     `UP`/`DOWN`/`UNKNOWN` por check (`ktor`, `supabase`, `worker` cuando exista).
 *   - JVM: heap/GC/threads via `JvmMemoryMetrics`/`JvmGcMetrics` de
 *     Micrometer, sin codigo propio.
 *   - worker queue: no aplica -- no existe ningun worker en este backend.
 * Contrato futuro hacia Screen 10: estas metricas + `/health/ready` +
 * logs/Sentry alimentarian un futuro endpoint de agregacion EN KTOR
 * (nunca Screen 10 leyendo logs/Prometheus directamente) -- ver
 * `docs/brain/CURRENT_STATE.md` para el estado de Screen 10.
 */
internal val prometheusIntegrationDoc: String get() = "" // ver KDoc arriba -- sin uso real, solo ancla la doc a un simbolo compilable.

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
    // Toda respuesta de Supabase se pide comprimida y se cuenta en bytes (ver [CompressedUpstreamHttpClient]).
    CompressedUpstreamHttpClient(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(ObservabilityConfig.upstreamConnectTimeoutMs))
            .build(),
    )

/**
 * La UNICA instancia de `HttpClient` hacia Supabase.
 *
 * [newUpstreamHttpClient] resolvia el timeout pero no la multiplicidad: al ser
 * una FUNCION y llamarse desde el default de constructor de cada gateway,
 * hoy viven 11 instancias distintas contra el MISMO host, cada una con su
 * propio pool de conexiones y su propio executor. Eso no es un fallo de
 * correccion, pero multiplica por once los pools y los hilos sin ninguna
 * ganancia: un `HttpClient` de la JDK ya es thread-safe y esta pensado para
 * compartirse.
 *
 * `by lazy` y no un `val` directo a proposito: [ObservabilityConfig] lee
 * `System.getenv` durante su inicializacion, y `ObservabilityTest` depende de
 * CUANDO ocurre esa lectura. Diferirla hasta el primer uso real conserva ese
 * comportamiento.
 *
 * La fabrica [newUpstreamHttpClient] SE CONSERVA: los tests la usan para
 * obtener un cliente propio, y los gateways con costura de test siguen
 * pudiendo inyectar el suyo.
 */
val sharedUpstreamHttpClient: HttpClient by lazy { newUpstreamHttpClient() }

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

    /** Real-auth gate: GoTrue answered 429 to a signup (60 s per user OR the hourly mail budget): the client got 202 but NO mail may have left. Alert on the rate of this event. */
    AUTH_SIGNUP_THROTTLED,
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
     * handler mas especifico, y todo 500 INTERNAL de dominio (fallo del
     * propio servidor, no del upstream; MASTER4-API-002). */
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
/**
 * MASTER7-API-007: SLF4J parametrizado NO neutraliza CR/LF. Un valor con salto de linea forjaria una linea de log
 * (`event=FORGED`): los caracteres de control pasan a `_` y la longitud se acota.
 */
internal fun sanitizeLogField(value: String): String =
    value.take(LOG_FIELD_MAX).map { if (it.isISOControl()) '_' else it }.joinToString("")

private const val LOG_FIELD_MAX = 128

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
        requestId?.let(::sanitizeLogField) ?: "-",
        operation?.let(::sanitizeLogField) ?: "-",
        result?.let(::sanitizeLogField) ?: "-",
        durationMs?.toString() ?: "-",
        entityType?.let(::sanitizeLogField) ?: "-",
        entityId?.let(::sanitizeLogField) ?: "-",
        errorType?.let(::sanitizeLogField) ?: "-",
        userId?.let(::sanitizeLogField) ?: "-",
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
 * ### MDC y corrutinas (F-12a)
 *
 * MDC es un `ThreadLocal` y no viaja con la corrutina. [withUpstreamIo] copia el
 * MDC del hilo en el que se le llama, pero eso sólo es correcto si ese hilo
 * todavia lo tiene: un handler que se suspende ANTES del salto —`call.receive`,
 * `call.receiveMultipart`— puede reanudar en otro hilo de Netty sin MDC, y la
 * copia sale vacia (`ADMIN_ACTION ... request_id=-`).
 *
 * Por eso el resto del pipeline corre dentro de [RequestMdcContext]: un
 * elemento de contexto que vuelve a poner el `request_id` en el MDC CADA VEZ
 * que la corrutina reanuda en un hilo, y lo retira al salir. Es lo mismo que
 * hace `MDCContext` de `kotlinx-coroutines-slf4j`, sin añadir la dependencia, y
 * cubre tambien los saltos de hilo escritos a mano, que [withUpstreamIo] no
 * puede ver.
 */
fun Application.installRequestCorrelation() {
    intercept(ApplicationCallPipeline.Setup) {
        val incoming = call.request.headers[REQUEST_ID_HEADER]?.takeIf { it.matches(REQUEST_ID_PATTERN) }
        val requestId = incoming ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdKey, requestId)
        call.attributes.put(RequestStartKey, TimeSource.Monotonic.markNow())
        call.response.header(REQUEST_ID_HEADER, requestId)
        // El id lo pone ÚNICAMENTE [RequestMdcContext], en cada reanudación de la
        // corrutina. Aquí había además un `MDC.put` directo sobre el hilo de la
        // petición, y sobraba de una forma peligrosa: el MDC de logback vive en un
        // `ThreadLocal` HEREDABLE, así que un hilo creado desde éste —un worker nuevo
        // del pool IO, por ejemplo— nacía con el id ya puesto. El efecto secundario
        // tapaba el fallo que este mecanismo existe para evitar: quitando el
        // `withContext` de abajo, los logs seguían saliendo correlacionados en las
        // pruebas y dejaban de estarlo en producción, donde los hilos del pool ya
        // existen desde hace rato y no heredan nada.
        try {
            withContext(RequestMdcContext(requestId)) { proceed() }
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
            // Sin `MDC.remove`: ya no hay nada que quitar aquí. `RequestMdcContext`
            // restaura el hilo exactamente como estaba al salir, incluido el id de otra
            // petición si lo tenía; borrarlo a mano se lo llevaría por delante.
        }
    }
}

/**
 * El `request_id` de la peticion, reinstalado en el MDC en cada reanudacion.
 *
 * `updateThreadContext` corre justo antes de que la corrutina reanude en un
 * hilo, y `restoreThreadContext` justo despues de que lo suelte: el hilo queda
 * exactamente como estaba, con el id de otra peticion si lo tenia o sin ninguno.
 * Es la misma garantia de limpieza que [runWithMdcContext], aplicada a cada
 * suspension en vez de a un unico bloque.
 *
 * Lo heredan las corrutinas hijas y los `withContext` del handler, asi que un
 * salto a `Dispatchers.IO` escrito a mano tambien lleva el id.
 */
internal class RequestMdcContext(
    private val requestId: String,
) : ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestMdcContext>

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previous = MDC.get(MDC_REQUEST_ID)
        MDC.put(MDC_REQUEST_ID, requestId)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        if (oldState == null) MDC.remove(MDC_REQUEST_ID) else MDC.put(MDC_REQUEST_ID, oldState)
    }
}

/** Duracion transcurrida desde el inicio de la request hasta ahora mismo,
 * util desde un handler de StatusPages (ver Application.kt). Null si por
 * algun motivo el interceptor de correlacion no llego a ejecutarse. */
fun ApplicationCall.elapsedMsOrNull(): Long? = attributes.getOrNull(RequestStartKey)?.elapsedNow()?.inWholeMilliseconds

fun ApplicationCall.requestIdOrNull(): String? = attributes.getOrNull(RequestIdKey)

/**
 * El request id de la llamada, garantizado (sobre canonico de error, SLICE 0).
 *
 * Con [installRequestCorrelation] instalado es exactamente [requestIdOrNull].
 * Sin el (perfil `consumer`, que solo monta /v1/auth), aplica la MISMA regla:
 * un `X-Request-Id` entrante valido o un UUID nuevo, lo guarda en [RequestIdKey]
 * y lo publica en la cabecera, para que `requestId` del cuerpo y cabecera
 * coincidan siempre.
 */
fun ApplicationCall.ensureRequestId(): String {
    requestIdOrNull()?.let { return it }
    val requestId = request.headers[REQUEST_ID_HEADER]?.takeIf { it.matches(REQUEST_ID_PATTERN) }
        ?: UUID.randomUUID().toString()
    attributes.put(RequestIdKey, requestId)
    if (response.headers[REQUEST_ID_HEADER] == null) response.header(REQUEST_ID_HEADER, requestId)
    return requestId
}

// ---------------------------------------------------------------------
// Salto al pool IO conservando la correlacion
// ---------------------------------------------------------------------

/**
 * Ejecuta [block] con [contextMap] instalado en el MDC del hilo actual y deja
 * el MDC exactamente como estaba antes.
 *
 * Los dos extremos importan y por eso estan los dos:
 *
 * - **Instalar**: sin esto, el codigo que corre en un hilo del pool IO no ve
 *   el `request_id` que [installRequestCorrelation] dejo en el hilo de la
 *   request, y todo `ADMIN_ACTION` sale con `request_id=-`.
 * - **Restaurar**: los hilos del pool IO se REUTILIZAN. Dejar el mapa puesto
 *   al salir haria que la siguiente tarea que caiga en ese mismo hilo herede
 *   el id de una request ajena. Un id equivocado es peor que ninguno: `-` se
 *   ve, una correlacion falsa no.
 *
 * `contextMap == null` significa "el llamante no tenia MDC", y entonces el
 * bloque debe ver el MDC VACIO, no los restos de quien uso antes ese hilo.
 */
internal fun <T> runWithMdcContext(contextMap: Map<String, String>?, block: () -> T): T {
    val previous = MDC.getCopyOfContextMap()
    if (contextMap == null) MDC.clear() else MDC.setContextMap(contextMap)
    return try {
        block()
    } finally {
        if (previous == null) MDC.clear() else MDC.setContextMap(previous)
    }
}

/**
 * **La unica forma de mover trabajo bloqueante de upstream al pool IO.**
 *
 * Sustituye a los `withContext(Dispatchers.IO)` sueltos. Motivo de que tenga
 * nombre propio en vez de repetirse la expresion: el contrato es greppable y
 * testeable en un solo sitio.
 *
 * `grep -rn "withContext(Dispatchers.IO)" backend/ktor/src/main` da hoy
 * **exactamente una** coincidencia de codigo fuera de esta funcion:
 * `OposiBotsAuthRateLimiter`, que pertenece a OposiBot — fuera del alcance de
 * esta foundation y con su PROPIO mecanismo de request id
 * (`OposiBotsAuthRoutes`), asi que no depende de este MDC. Cualquier
 * coincidencia NUEVA en el resto del backend es un defecto: reabre el agujero
 * de correlacion en silencio.
 *
 * Hace dos cosas:
 *
 * 1. Libera el hilo de Netty: los gateways llaman `client.send(...)`, que
 *    BLOQUEA hasta 20 s (`UPSTREAM_REQUEST_TIMEOUT`). El pool de Netty esta
 *    dimensionado para trabajo no bloqueante, y agotarlo deja a Ktor sin
 *    aceptar peticiones nuevas -- `/health/ready` incluida.
 * 2. Conserva la correlacion de la request al otro lado del cambio de hilo
 *    (ver [runWithMdcContext]).
 *
 * ### Por que [block] NO es `suspend`, y es una restriccion de diseño
 *
 * El MDC se instala UNA vez, al entrar en el hilo de IO. Eso solo es correcto
 * si el bloque no se suspende por dentro: una suspension puede reanudar en
 * otro hilo del pool, donde el mapa que acabamos de poner ya no esta, y
 * volveriamos al defecto que esta funcion existe para cerrar. Con un bloque
 * puramente bloqueante, "poner una vez" es correcto por construccion.
 *
 * **Consecuencia practica**: `call.receive()`, `call.receiveMultipart()` y
 * cualquier otra suspension del contexto de la peticion van FUERA. Leer el
 * cuerpo de la peticion pertenece al hilo de la peticion, no al pool de IO; el
 * compilador lo obliga y aqui queda escrito el motivo.
 */
suspend fun <T> withUpstreamIo(block: () -> T): T {
    val correlation = MDC.getCopyOfContextMap()
    return withContext(Dispatchers.IO) { runWithMdcContext(correlation, block) }
}

/**
 * Version concurrente de [withUpstreamIo]: varias llamadas bloqueantes
 * INDEPENDIENTES a la vez, cada una en el pool IO y cada una con la
 * correlacion de la request puesta.
 *
 * El MDC se copia UNA vez aqui -- todavia en el hilo del llamante, antes de
 * que `coroutineScope` reparta nada -- porque capturarlo dentro de cada rama
 * lo leeria ya desde un hilo que puede no tenerlo.
 *
 * Que sea `coroutineScope` y no un scope propio es parte del contrato: las
 * ramas son hijas de la peticion, asi que no pueden sobrevivirla. Si el cliente
 * se va, el scope se cancela con el y ninguna rama nueva llega a arrancar.
 *
 * ### Que la cancelacion NO hace, y hay que decirlo
 *
 * Una version anterior de este KDoc afirmaba que cancelar "deja de consumir
 * hilos de IO". **Es falso para bloques bloqueantes**, y se corrige aqui porque
 * en esta base de codigo una afirmacion mas ancha que la realidad ya ha
 * escondido un defecto tres veces:
 *
 * - `Dispatchers.IO` **no interrumpe** un hilo que ya esta dentro de
 *   `client.send(...)`. Interrumpirlo requeriria `runInterruptible`, que no se
 *   usa aqui. La rama sigue bloqueada hasta su propio timeout de 20 s.
 * - `coroutineScope` **espera** a sus hijas antes de propagar la cancelacion,
 *   asi que la peticion tampoco termina antes.
 *
 * Lo unico que se ahorra al cancelar es el trabajo de las ramas que **todavia
 * no habian arrancado**. Es una mejora real y es toda la mejora que hay.
 */
suspend fun <T> withUpstreamIoParallel(block: suspend UpstreamIoScope.() -> T): T {
    val correlation = MDC.getCopyOfContextMap()
    return coroutineScope { UpstreamIoScope(this, correlation).block() }
}

/** Receptor de [withUpstreamIoParallel]. No se construye desde fuera a proposito:
 * la unica forma de obtener uno es entrar en el scope, y asi no puede existir un
 * [asyncUpstreamIo] huerfano cuyo fallo no espere nadie. */
class UpstreamIoScope internal constructor(
    private val scope: CoroutineScope,
    private val correlation: Map<String, String>?,
) {
    /** Lanza [block] en el pool IO. Mismas reglas que [withUpstreamIo]: bloque NO suspendido. */
    fun <T> asyncUpstreamIo(block: () -> T): Deferred<T> =
        scope.async(Dispatchers.IO) { runWithMdcContext(correlation, block) }

    /**
     * Combina resultados de otras ramas SIN bloquear ningun hilo (solo `await` + calculo puro): corre en el dispatcher de
     * quien llamo, no en el pool IO. Para trabajo bloqueante, [asyncUpstreamIo].
     */
    fun <T> combine(block: suspend () -> T): Deferred<T> = scope.async { block() }
}

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
 * ENVIRONMENT) -- documentado aqui en vez de disperso.
 *
 * Corregido a 5s (era 2s) tras probarlo en real: un curl caliente a
 * Supabase responde en ~0.4s, pero el PRIMER handshake TLS que hace un
 * `HttpClient` de un JVM recien arrancado puede tardar mas de 2s por el
 * coste de arranque en frio (negociacion ALPN/TLS, SecureRandom) -- con 2s
 * el primer /health/ready tras cada arranque fallaba en falso
 * (`UPSTREAM_TIMEOUT`) aunque Supabase estuviera perfectamente arriba;
 * la segunda llamada, ya con el cliente "caliente", pasaba. 5s absorbe
 * ese coste de arranque sin dejar de ser un limite corto para un probe. */
private val READINESS_TIMEOUT: Duration = Duration.ofSeconds(5)

private val readinessHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(READINESS_TIMEOUT)
    .build()

/**
 * Comprueba que Supabase es alcanzable y que la credencial de servidor
 * autentica, SIN ejecutar ninguna query de negocio: GET a la raiz de PostgREST
 * (`/rest/v1/`) -- responde rapido con su OpenAPI spec, no toca ninguna tabla,
 * no hace INSERT. [url]/[apiKey] por defecto leen la misma configuracion que
 * usan el resto de gateways -- no introduce una fuente de config nueva; son
 * parametros (en vez de leer env directamente) solo para que ObservabilityTest
 * pueda apuntar a un servidor local de prueba.
 *
 * ### La sonda manda EXACTAMENTE las cabeceras del trafico real
 *
 * Las cabeceras no se componen aqui: salen de
 * [SupabaseRequestAuthPolicy.elevatedHeaders], que es la misma funcion que
 * firma las peticiones de los gateways. Con la clave legacy eso son
 * `apikey` + `Authorization: Bearer`; con una `sb_secret_...`, solo `apikey`.
 *
 * Esto no es simetria estetica: es la **precondicion** del rango estrecho de
 * abajo. Una sonda que mandara una combinacion de cabeceras que ningun camino
 * de negocio usa estaria midiendo una credencial que nadie ejerce, y un 401 de
 * esa combinacion concreta sacaria de rotacion TODAS las replicas con el
 * backend perfectamente sano -- un fallo correlacionado, inmediato tras el
 * despliegue y apuntando a una dependencia que esta bien.
 */
internal fun probeSupabaseReachable(
    url: String? = System.getenv("SUPABASE_URL")?.trimEnd('/'),
    // La clave la resuelve el UNICO sitio que decide precedencia
    // (`SUPABASE_SECRET_KEY`, y si falta `SUPABASE_SERVICE_ROLE_KEY`). Si la
    // sonda leyera otra variable distinta de la que usan los gateways,
    // comprobaria una credencial que nadie usa: "ready" con la clave real
    // rechazada. Hoy, sin `SUPABASE_SECRET_KEY` en el entorno, resuelve
    // exactamente el mismo valor que antes.
    apiKey: String? = SupabaseRequestAuthPolicy.secretFromEnvironment(),
): String {
    if (url.isNullOrBlank() || apiKey.isNullOrBlank()) return "UNKNOWN"
    return try {
        val builder = HttpRequest.newBuilder(URI.create("$url/rest/v1/"))
            .timeout(READINESS_TIMEOUT)
            // HEAD y no GET (2026-10-10): el GET de la raiz devuelve la especificacion OpenAPI ENTERA del esquema. Sondeado por el
            // balanceador desde tres replicas, eso agoto en un dia la cuota de trafico de salida del proyecto Supabase.
            // HEAD ejerce la misma ruta y la misma credencial y no trae cuerpo.
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
        // `setHeader` y no `header`: `header` ACUMULA, y dos `Authorization` en
        // una peticion no es "gana el ultimo". Misma regla que el transporte.
        SupabaseRequestAuthPolicy(apiKey).elevatedHeaders().forEach { (name, value) ->
            builder.setHeader(name, value)
        }
        val response = readinessHttpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        // 2xx y SOLO 2xx es "up", y el rango estrecho SOLO es legitimo porque la
        // peticion de arriba ejerce la credencial real (ver KDoc).
        //
        // Antes el rango era `200..499`, y eso anulaba la sonda justo en el fallo
        // mas probable: un 401 -- la credencial rechazada -- contaba como sano.
        // Es decir, `/health/ready` decia "ready" con Supabase alcanzable pero
        // sin credencial valida, que para este backend es indistinguible de
        // estar caido: ninguna query de negocio funcionaria. Con una migracion
        // de claves en curso (SUPABASE_SERVICE_ROLE_KEY -> SUPABASE_SECRET_KEY)
        // ese es EL modo de fallo que la sonda tiene que ver, no el que tiene
        // que esconder. Un 4xx de credencial no es "el servicio responde": es
        // "el servicio no me deja entrar".
        //
        // El rango y las cabeceras se mueven JUNTOS. Si alguna vez la sonda deja
        // de mandar lo que manda el trafico real, este rango vuelve a ser una
        // apuesta sobre una combinacion que nadie ejerce y hay que ensancharlo.
        if (response.statusCode() in 200..299) {
            "UP"
        } else {
            // MASTER7-OBS-002: la causa del DOWN deja rastro. SOLO el codigo numerico: nunca cabeceras, URL ni clave.
            logStrategicEvent(
                StrategicEvent.SUPABASE_REQUEST_FAILED,
                operation = "health_ready_supabase_check",
                result = "status_${response.statusCode()}",
            )
            "DOWN"
        }
    } catch (error: Exception) {
        if (error.looksLikeUpstreamTimeout()) {
            logStrategicEvent(StrategicEvent.UPSTREAM_TIMEOUT, operation = "health_ready_supabase_check", errorType = error::class.simpleName)
            return READINESS_TRANSIENT
        } else {
            // MASTER7-OBS-002: DNS/TLS/conexion rechazada: solo la clase de la excepcion, nunca su mensaje.
            logStrategicEvent(
                StrategicEvent.SUPABASE_REQUEST_FAILED,
                operation = "health_ready_supabase_check",
                errorType = error::class.simpleName,
            )
        }
        "DOWN"
    }
}

/** Valor INTERNO de [probeSupabaseReachable]: la sonda agoto su tiempo. Nunca sale por `/health/ready` (D8-08: UP|DOWN|UNKNOWN). */
internal const val READINESS_TRANSIENT = "TRANSIENT"

/** La sonda con el contrato de siempre: un timeout es `DOWN`. La usan los tests y quien no quiera el margen de gracia. */
internal fun checkSupabaseReachable(
    url: String? = System.getenv("SUPABASE_URL")?.trimEnd('/'),
    apiKey: String? = SupabaseRequestAuthPolicy.secretFromEnvironment(),
): String = probeSupabaseReachable(url, apiKey).let { if (it == READINESS_TRANSIENT) "DOWN" else it }

/**
 * Margen de gracia de `/health/ready` (observado en produccion el 2026-10-09): la sonda a Supabase agota sus 5 s de forma
 * esporadica y en las dos replicas `bot` A LA VEZ (la causa esta en la dependencia, no en la replica). Caddy saca del
 * balanceo a una replica con UNA sonda fallida, asi que cada episodio dejaba el plano sin upstream sano unos segundos,
 * sin que sacar la replica arreglara nada. Un TIMEOUT aislado se tolera mientras la ultima sonda correcta sea reciente;
 * una credencial rechazada, una conexion rechazada o un timeout sostenido siguen siendo `DOWN` como antes.
 */
internal class ReadinessGrace(
    private val probe: () -> String,
    private val graceMillis: Long = 90_000,
    /**
     * Un resultado se reutiliza este tiempo: por muchas veces que se consulte `/health/ready` (balanceador, orquestador,
     * una persona), cada replica hace como mucho UNA peticion a Supabase por ventana. Menor que [graceMillis].
     */
    private val cacheMillis: Long = 30_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastUpAtMillis: Long? = null
    private var lastProbeAtMillis: Long? = null
    private var lastAnswer: String = "UNKNOWN"

    @Synchronized
    fun check(): String {
        val now = nowMillis()
        val probedAt = lastProbeAtMillis
        if (probedAt != null && now - probedAt < cacheMillis) return lastAnswer
        val answer = when (val result = probe()) {
            "UP" -> {
                lastUpAtMillis = nowMillis()
                "UP"
            }
            READINESS_TRANSIENT -> {
                val lastUp = lastUpAtMillis
                if (lastUp != null && nowMillis() - lastUp <= graceMillis) "UP" else "DOWN"
            }
            else -> result
        }
        lastProbeAtMillis = nowMillis()
        lastAnswer = answer
        return answer
    }
}

private val productionReadiness = ReadinessGrace(probe = { probeSupabaseReachable() })

/** La sonda que usa produccion: la real con el margen de gracia de [ReadinessGrace]. */
internal fun checkSupabaseReadiness(): String = productionReadiness.check()

/**
 * Los checks de `/health/ready`, con la sonda FUERA del hilo de la peticion.
 *
 * F-15: la sonda bloquea hasta 5 s ([READINESS_TIMEOUT]). En el hilo de Netty eso
 * es justo lo que no puede pasar en la ruta que el balanceador consulta para
 * decidir si esta replica sigue viva: con varias sondas lentas a la vez, el pool
 * se agota y la replica deja de contestar tambien a `/health/live`. Vive en una
 * funcion propia para poder probar el salto de hilo sin el arnes de
 * `testApplication`, que no cambia de hilo (ver `UpstreamIoSeamTest`).
 */
internal suspend fun readinessChecks(supabaseCheck: () -> String): Map<String, String> =
    mapOf("ktor" to "UP", "supabase" to withUpstreamIo(supabaseCheck))

/** D8-07 (openapi/foundation LivenessStatus): `{"status":"UP"}`. Unica definicion, usada por ambos perfiles. */
fun Route.healthLiveRoute() = get("/health/live") {
    call.respond(mapOf("status" to "UP"))
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
fun Route.observabilityHealthRoutes(supabaseCheck: () -> String = ::checkSupabaseReadiness) {
    healthLiveRoute()
    get("/health/ready") {
        val checks = readinessChecks(supabaseCheck)
        // D8-08 (openapi/foundation ReadinessStatus): status UP|DOWN; checks UP|DOWN|UNKNOWN.
        val ready = checks["supabase"] == "UP"
        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            ReadinessResponse(
                status = if (ready) "UP" else "DOWN",
                checks = checks,
                timestamp = Instant.now().toString(),
            ),
        )
    }
}
