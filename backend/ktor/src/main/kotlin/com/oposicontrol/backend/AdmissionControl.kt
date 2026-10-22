package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// =====================================================================
// CONTROL DE ADMISION Y PLAZO POR PETICION (perfil `bot`, CERT-LOAD-1000 2026-10-21)
//
// Problema medido: sin tope de peticiones en vuelo ni plazo, una punta de 1000 usuarios encolaba trabajo en el pool IO hasta
// que Caddy cortaba a los 25 s (504); Ktor seguia trabajando en peticiones abandonadas (escrituras guardadas que el cliente vio
// como error) y, con los cortes, Caddy apartaba las dos replicas ("no upstreams available" para todos).
//
// Que hace:
//  1. ADMISION: como mucho [AdmissionSettings.maxInFlight] peticiones en vuelo por replica y [AdmissionSettings.maxQueued]
//     esperando turno (FIFO, como mucho [AdmissionSettings.queueTimeoutMs]). Lo que no cabe recibe AL INSTANTE 503
//     SERVER_BUSY + Retry-After (con fluctuacion para no sincronizar reintentos). Un SERVER_BUSY de admision NO ha ejecutado
//     nada: reintentar con la misma Idempotency-Key es seguro.
//  2. PLAZO: cada peticion admitida lleva un plazo ([AdmissionSettings.deadlineMs], contado desde que llega, cola incluida),
//     menor que el corte del borde. El transporte de Supabase ([SupabaseRequestTransport]) NO EMPIEZA una llamada nueva con el
//     plazo vencido y acorta el timeout de las LECTURAS al tiempo que queda. Nunca interrumpe una escritura en curso.
//  3. PUNTO SIN RETORNO: en cuanto una ESCRITURA de negocio ha terminado bien ([RequestDeadline.markCommitted]), el plazo deja
//     de aplicarse a esa peticion: se termina (lecturas posteriores, `idem_complete`) y se responde. Asi un 503 por plazo
//     significa siempre "no se aplico ninguna escritura de negocio" (las de idempotencia se liberan, ver CanonicalIdempotency),
//     y no se fabrican "escrituras fantasma" nuevas por el plazo.
//  4. EXENTAS: `/health*` y `/metrics` (la salud activa del balanceador tiene que contestar aunque la replica este llena).
//
// No cancela el trabajo bloqueante ya empezado (un hilo dentro de `HttpClient.send` no se interrumpe: ver withUpstreamIoParallel);
// lo acota el timeout de cada llamada. Es cooperativo por diseno: cortar a mitad una secuencia idem_begin -> RPC -> idem_complete
// es justo lo que no puede pasar.
// =====================================================================

/** Configuracion de admision del perfil bot. Todo por entorno; valores por defecto medidos en CERT-LOAD-1000 (ver RUNTIME_DECISIONS §6). */
internal data class AdmissionSettings(
    val enabled: Boolean = true,
    /** Peticiones ejecutandose a la vez por replica. 32: el codo local fue ~42 req/s por replica con ~0.3 s de servicio
     *  (Little: ~13-17 en vuelo) y el pool IO (64 hilos) se lleno a partir de ahi; 32 deja margen sin llegar a 64. */
    val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    /** Peticiones esperando turno. 64 = ~1.5 s de trabajo al ritmo del codo por replica. */
    val maxQueued: Int = DEFAULT_MAX_QUEUED,
    /** Espera maxima en la cola antes de rechazar con 503. */
    val queueTimeoutMs: Long = DEFAULT_QUEUE_TIMEOUT_MS,
    /** Plazo total de la peticion (cola incluida). Menor que `response_header_timeout` del borde (25 s). */
    val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    /** Base de `Retry-After`; se envia base..2*base segundos (fluctuacion). */
    val retryAfterSeconds: Int = DEFAULT_RETRY_AFTER_SECONDS,
    /**
     * Corte del borde (`response_header_timeout` del pool bot en Caddy, 25 s), contado desde que llega la peticion. Una
     * escritura de negocio solo EMPIEZA si su timeout completo cabe antes de este corte menos [EDGE_MARGIN_MS]: si no, el
     * cliente podria recibir 504 de Caddy mientras la escritura se aplica (escritura fantasma).
     */
    val edgeTimeoutMs: Long = DEFAULT_EDGE_TIMEOUT_MS,
) {
    init {
        require(maxInFlight >= 1) { "BOT_ADMISSION_MAX_IN_FLIGHT >= 1" }
        require(maxQueued >= 0) { "BOT_ADMISSION_MAX_QUEUED >= 0" }
        require(queueTimeoutMs >= 0) { "BOT_ADMISSION_QUEUE_TIMEOUT_MS >= 0" }
        require(deadlineMs >= 1_000) { "BOT_REQUEST_DEADLINE_MS >= 1000" }
        require(retryAfterSeconds in 1..60) { "BOT_ADMISSION_RETRY_AFTER_SECONDS en 1..60" }
        require(edgeTimeoutMs > deadlineMs) { "BOT_EDGE_TIMEOUT_MS > BOT_REQUEST_DEADLINE_MS" }
    }

    /** Hasta cuando (ms desde la llegada) puede terminar una escritura que empiece ahora. */
    val writeBudgetMs: Long get() = edgeTimeoutMs - EDGE_MARGIN_MS

    companion object {
        const val DEFAULT_MAX_IN_FLIGHT = 32
        const val DEFAULT_MAX_QUEUED = 64
        const val DEFAULT_QUEUE_TIMEOUT_MS = 2_000L
        const val DEFAULT_DEADLINE_MS = 15_000L
        const val DEFAULT_RETRY_AFTER_SECONDS = 2
        const val DEFAULT_EDGE_TIMEOUT_MS = 25_000L
        /** Margen para que la respuesta de Ktor llegue a Caddy antes de su corte. */
        const val EDGE_MARGIN_MS = 1_000L

        /** Un valor ilegible NO cae al defecto en silencio: aborta el arranque (como SERVER_PROFILE). */
        fun fromEnvironment(environment: (String) -> String? = System::getenv): AdmissionSettings {
            fun <T> read(name: String, default: T, parse: (String) -> T?): T {
                val raw = environment(name)?.trim().orEmpty()
                if (raw.isEmpty()) return default
                return parse(raw) ?: error("$name='$raw' no es valido.")
            }
            return AdmissionSettings(
                enabled = read("BOT_ADMISSION_ENABLED", true, String::toBooleanStrictOrNull),
                maxInFlight = read("BOT_ADMISSION_MAX_IN_FLIGHT", DEFAULT_MAX_IN_FLIGHT, String::toIntOrNull),
                maxQueued = read("BOT_ADMISSION_MAX_QUEUED", DEFAULT_MAX_QUEUED, String::toIntOrNull),
                queueTimeoutMs = read("BOT_ADMISSION_QUEUE_TIMEOUT_MS", DEFAULT_QUEUE_TIMEOUT_MS, String::toLongOrNull),
                deadlineMs = read("BOT_REQUEST_DEADLINE_MS", DEFAULT_DEADLINE_MS, String::toLongOrNull),
                retryAfterSeconds = read("BOT_ADMISSION_RETRY_AFTER_SECONDS", DEFAULT_RETRY_AFTER_SECONDS, String::toIntOrNull),
                edgeTimeoutMs = read("BOT_EDGE_TIMEOUT_MS", DEFAULT_EDGE_TIMEOUT_MS, String::toLongOrNull),
            )
        }
    }
}

internal enum class AdmissionRejection(val metricTag: String) { QUEUE_FULL("queue_full"), QUEUE_TIMEOUT("queue_timeout") }

/**
 * Semaforo justo (FIFO) + cola acotada. Sin hilos: la espera es una suspension. `null` = admitida (hay que llamar a
 * [release]); si no, el motivo del rechazo.
 */
internal class AdmissionLimiter(
    private val maxInFlight: Int,
    private val maxQueued: Int,
    private val queueTimeoutMs: Long,
) {
    private val permits = Semaphore(maxInFlight)
    private val waiting = AtomicInteger(0)

    val inFlight: Int get() = maxInFlight - permits.availablePermits
    val queued: Int get() = waiting.get()

    suspend fun acquire(): AdmissionRejection? {
        if (permits.tryAcquire()) return null
        if (waiting.incrementAndGet() > maxQueued) {
            waiting.decrementAndGet()
            return AdmissionRejection.QUEUE_FULL
        }
        var acquired = false
        try {
            // `acquired` y no el valor de withTimeoutOrNull: si el plazo vence justo cuando el permiso llega, el bloque ya
            // termino y el permiso es nuestro aunque withTimeoutOrNull devuelva null (sin esto se perderia un permiso).
            withTimeoutOrNull(queueTimeoutMs) {
                permits.acquire()
                acquired = true
            }
        } catch (e: Throwable) {
            // Cancelada justo cuando el permiso llegaba: el permiso es nuestro pero nadie lo soltaria.
            if (acquired) permits.release()
            throw e
        } finally {
            waiting.decrementAndGet()
        }
        return if (acquired) null else AdmissionRejection.QUEUE_TIMEOUT
    }

    fun release() = permits.release()
}

/**
 * Plazo de UNA peticion, visible en los hilos IO a traves de [RequestDeadline] (ThreadContextElement: lo heredan los
 * `withContext(Dispatchers.IO)` y las ramas de withUpstreamIoParallel).
 */
internal class DeadlineState(
    val deadlineNanos: Long,
    /** Instante limite para TERMINAR una escritura (borde menos margen); `null` = sin limite. */
    val writeEndNanos: Long? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile
    var committed: Boolean = false
        private set

    fun markCommitted() {
        committed = true
    }

    /** Milisegundos que quedan (<= 0 = vencido). */
    fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoTime())

    /** Milisegundos hasta el limite de escritura, o `null` si no hay. */
    fun writeRemainingMillis(): Long? = writeEndNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - nanoTime()) }

    /** Vencido y la peticion todavia no ha aplicado ninguna escritura de negocio. */
    fun expiredBeforeCommit(): Boolean = !committed && remainingMillis() <= 0
}

/** Elemento de contexto que publica el [DeadlineState] en un ThreadLocal mientras la corrutina corre en un hilo. `null` = sin plazo. */
internal class RequestDeadline(val state: DeadlineState?) : ThreadContextElement<DeadlineState?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDeadline> {
        private val current = ThreadLocal<DeadlineState?>()

        /** El plazo de la peticion que corre en ESTE hilo, o `null`. */
        fun current(): DeadlineState? = current.get()

        /** Para pruebas y para codigo bloqueante sin corrutina: instala [state] durante [block]. */
        internal fun <T> runWith(state: DeadlineState?, block: () -> T): T {
            val previous = current.get()
            current.set(state)
            try {
                return block()
            } finally {
                current.set(previous)
            }
        }
    }

    override fun updateThreadContext(context: CoroutineContext): DeadlineState? {
        val previous = current.get()
        current.set(state)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: DeadlineState?) {
        current.set(oldState)
    }
}

/**
 * Lo que el transporte de Supabase pregunta antes y despues de cada llamada. Lecturas = GET/COUNT/PAGE/SIGN y las RPC de solo
 * lectura conocidas; todo lo demas (POST/PATCH/DELETE, RPC) es escritura.
 */
internal object UpstreamDeadlineGuard {
    /** RPC que NO aplican nada de negocio: no marcan el punto sin retorno. `idem_begin` reclama una clave que se libera si falla. */
    private val NON_COMMITTING_RPCS = setOf("app_server_now", "idem_begin")

    /** RPC de finalizacion de idempotencia: corren siempre, haya vencido el plazo o no (si no, la clave quedaria en curso). */
    private val ALWAYS_ALLOWED_RPCS = setOf("idem_complete", "idem_release")

    private val READ_OPERATIONS = setOf("GET", "COUNT", "PAGE", "SIGN", "SIGN_BATCH")

    private fun rpcName(path: String): String? = path.substringBefore('?').substringAfter("/rest/v1/rpc/", "").takeIf { it.isNotEmpty() }

    fun isRead(operation: String): Boolean = operation in READ_OPERATIONS

    /**
     * Antes de empezar una llamada: lanza 503 SERVER_BUSY si el plazo vencio sin haber escrito nada; devuelve el timeout
     * (ms) que debe usar una LECTURA (el que queda del plazo si es menor que [defaultTimeoutMs]); las escrituras no se acortan.
     */
    fun beforeCall(operation: String, path: String, defaultTimeoutMs: Long): Long {
        val state = RequestDeadline.current() ?: return defaultTimeoutMs
        if (rpcName(path) in ALWAYS_ALLOWED_RPCS || state.committed) return defaultTimeoutMs
        val remaining = state.remainingMillis()
        if (remaining <= 0) throw deadlineExceeded("before_upstream")
        if (isRead(operation)) return minOf(defaultTimeoutMs, remaining)
        // Escritura antes del punto sin retorno: solo empieza si su timeout entero cabe antes del corte del borde. Si no,
        // 503 sin haber escrito nada (reintentable con la misma Idempotency-Key) en vez de un 504 con la escritura aplicada.
        val writeRemaining = state.writeRemainingMillis()
        if (writeRemaining != null && writeRemaining < defaultTimeoutMs) throw deadlineExceeded("before_write")
        return defaultTimeoutMs
    }

    /** Despues de una llamada correcta: una escritura de negocio marca el punto sin retorno. */
    fun afterSuccess(operation: String, path: String) {
        val state = RequestDeadline.current() ?: return
        if (isRead(operation)) return
        val rpc = rpcName(path)
        if (rpc in NON_COMMITTING_RPCS || rpc in ALWAYS_ALLOWED_RPCS) return
        state.markCommitted()
    }

    /** Una LECTURA cortada por el plazo (no por el timeout normal) es SERVER_BUSY, no un 500. */
    fun timedOutByDeadline(operation: String): Boolean {
        val state = RequestDeadline.current() ?: return false
        return isRead(operation) && !state.committed && state.remainingMillis() <= 0
    }

    fun deadlineExceeded(stage: String): BackendDomainException {
        AdmissionMetrics.deadlineExceeded(stage)
        return CanonicalErrorCode.SERVER_BUSY.exception(retryAfterSeconds = AdmissionMetrics.retryAfterSeconds())
    }
}

/** Metricas de admision (registro Prometheus del backend). Nombres: admission_* y request_deadline_exceeded_total. */
internal object AdmissionMetrics {
    private val logger = LoggerFactory.getLogger("OC-KTOR-ADMISSION")
    private val lastWarnMillis = AtomicLong(0)

    @Volatile
    private var retryAfterBase: Int = AdmissionSettings.DEFAULT_RETRY_AFTER_SECONDS

    @Volatile
    private var registry: MeterRegistry = BackendPrometheusMetrics.registry

    fun bind(registry: MeterRegistry, limiter: AdmissionLimiter?, retryAfterBase: Int) {
        this.registry = registry
        this.retryAfterBase = retryAfterBase
        if (limiter != null) {
            Gauge.builder("admission.in_flight") { limiter.inFlight.toDouble() }
                .description("Peticiones del perfil bot ejecutandose ahora (por replica).").register(registry)
            Gauge.builder("admission.queued") { limiter.queued.toDouble() }
                .description("Peticiones del perfil bot esperando turno de admision.").register(registry)
        }
    }

    /** base..2*base segundos: los rechazados no vuelven todos en el mismo segundo. */
    fun retryAfterSeconds(): Int = retryAfterBase + ThreadLocalRandom.current().nextInt(retryAfterBase + 1)

    fun admitted(waitNanos: Long) {
        Counter.builder("admission.admitted").register(registry).increment()
        Timer.builder("admission.queue.wait").register(registry).record(waitNanos, TimeUnit.NANOSECONDS)
    }

    fun rejected(reason: AdmissionRejection) {
        Counter.builder("admission.rejected").tag("reason", reason.metricTag).register(registry).increment()
        warnRateLimited("admission rejected reason={} (503 SERVER_BUSY)", reason.metricTag)
    }

    fun deadlineExceeded(stage: String) {
        Counter.builder("request.deadline.exceeded").tag("stage", stage).register(registry).increment()
    }

    /** Como mucho una linea cada 10 s: en una punta habria miles. Las cifras exactas estan en las metricas. */
    private fun warnRateLimited(format: String, arg: Any) {
        val now = System.currentTimeMillis()
        val last = lastWarnMillis.get()
        if (now - last >= 10_000 && lastWarnMillis.compareAndSet(last, now)) logger.warn(format, arg)
    }
}

private val ADMISSION_EXEMPT_PATHS = setOf("/health", "/metrics")

internal fun isAdmissionExempt(path: String): Boolean = path in ADMISSION_EXEMPT_PATHS || path.startsWith("/health/")

/**
 * Instala admision + plazo en la aplicacion (perfil `bot` y `combined`). Va DESPUES de [installRequestCorrelation] y de
 * StatusPages: el rechazo lleva `X-Request-Id` y el sobre canonico. Devuelve el limitador (pruebas y metricas).
 */
internal fun Application.installBotAdmissionControl(
    settings: AdmissionSettings,
    registry: MeterRegistry = BackendPrometheusMetrics.registry,
    nanoTime: () -> Long = System::nanoTime,
): AdmissionLimiter? {
    if (!settings.enabled) {
        AdmissionMetrics.bind(registry, null, settings.retryAfterSeconds)
        return null
    }
    val limiter = AdmissionLimiter(settings.maxInFlight, settings.maxQueued, settings.queueTimeoutMs)
    AdmissionMetrics.bind(registry, limiter, settings.retryAfterSeconds)
    val deadlineNanos = TimeUnit.MILLISECONDS.toNanos(settings.deadlineMs)
    val writeBudgetNanos = TimeUnit.MILLISECONDS.toNanos(settings.writeBudgetMs)
    // Con un timeout de escritura que no cabe ni al llegar, toda escritura daria 503: se aborta el arranque.
    require(ObservabilityConfig.upstreamRequestTimeoutMs < settings.writeBudgetMs) {
        "UPSTREAM_REQUEST_TIMEOUT_MS (${ObservabilityConfig.upstreamRequestTimeoutMs}) tiene que ser menor que " +
            "BOT_EDGE_TIMEOUT_MS - ${AdmissionSettings.EDGE_MARGIN_MS} (${settings.writeBudgetMs})"
    }
    intercept(ApplicationCallPipeline.Plugins) {
        if (isAdmissionExempt(call.request.path())) return@intercept
        val arrived = nanoTime()
        val rejection = limiter.runAdmitted {
            AdmissionMetrics.admitted(nanoTime() - arrived)
            val state = DeadlineState(arrived + deadlineNanos, writeEndNanos = arrived + writeBudgetNanos, nanoTime = nanoTime)
            withContext(RequestDeadline(state)) { proceed() }
        }
        if (rejection != null) {
            AdmissionMetrics.rejected(rejection)
            call.response.header(HttpHeaders.RetryAfter, AdmissionMetrics.retryAfterSeconds().toString())
            call.respondErrorEnvelope(CanonicalErrorCode.SERVER_BUSY)
            finish()
        }
    }
    return limiter
}

/**
 * Espera turno y ejecuta [block] con el permiso, que se suelta SIEMPRE (tambien si la peticion se cancela en la cola o
 * con el permiso cogido). Devuelve el rechazo sin ejecutar [block], o `null` si se ejecuto.
 */
internal suspend fun AdmissionLimiter.runAdmitted(block: suspend () -> Unit): AdmissionRejection? {
    acquire()?.let { return it }
    try {
        block()
    } finally {
        release()
    }
    return null
}
