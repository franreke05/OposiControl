package com.oposicontrol.backend

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// =====================================================================
// EX13: metricas del runtime de examen. SIN PII: las unicas etiquetas son `op` (operacion fija), `outcome`
// (ok | 4xx | 5xx) y `code` (codigo canonico del contrato). Nunca texto de pregunta, opcion, respuesta, correccion,
// ids de usuario / intento / pregunta, JWT ni cabeceras.
//
//   exam_operation_seconds{op,outcome}          latencia por operacion (start, question_page, answer, finish, result,
//                                               review, resource_access, get, abandon); histograma para p50/p95/p99
//   exam_db_calls{op}                           viajes al upstream (PostgREST / Storage) por peticion, medidos en
//                                               SupabaseRequestTransport (identidad + idempotencia + negocio)
//   exam_4xx_total{op,code} / exam_5xx_total{op} errores por codigo canonico
//   exam_idempotency_replay_total{op}           respuestas reproducidas por el almacen de idempotencia
//   exam_revision_conflict_total{op}            409 REVISION_CONFLICT (dos dispositivos sobre la misma pregunta)
//   exam_resource_access_error_total{code}      fallos al pedir acceso a un recurso
//   exam_media_metadata_seconds                 lectura de los metadatos congelados de un recurso
// =====================================================================

/** Viajes al upstream por peticion: [record] lo llama el transporte con el `request_id` del MDC. */
internal object UpstreamCallLedger {
    private const val MAX_OPEN = 10_000
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun open(requestId: String) {
        // Cota dura: una peticion cuya respuesta nunca se envio (conexion cortada) no puede acumular entradas sin fin.
        if (counts.size >= MAX_OPEN) counts.clear()
        counts[requestId] = AtomicInteger()
    }

    fun record(requestId: String?) {
        if (requestId != null) counts[requestId]?.incrementAndGet()
    }

    fun close(requestId: String): Int? = counts.remove(requestId)?.get()
}

internal class ExamMetricsConfig {
    var operation: String = ""
    var registry: MeterRegistry = BackendPrometheusMetrics.registry
}

private val ExamStartKey = AttributeKey<Long>("oc-exam-metrics-start")
private val ExamCodeKey = AttributeKey<String>("oc-exam-metrics-code")

/**
 * Instalar ANTES que [CanonicalIdempotency] en la misma ruta: asi tambien cuenta lo que su puerta rechaza (401, 429...)
 * y lo que reproduce sin ejecutar el handler.
 */
internal val ExamMetrics = createRouteScopedPlugin("ExamMetrics", ::ExamMetricsConfig) {
    val op = pluginConfig.operation
    val registry = pluginConfig.registry
    require(op.matches(Regex("^[a-z_]+$"))) { "ExamMetrics necesita una operacion fija" }

    onCall { call ->
        call.attributes.put(ExamStartKey, System.nanoTime())
        UpstreamCallLedger.open(call.ensureRequestId())
    }
    // Una excepcion la responde el StatusPages de la APLICACION, fuera del pipeline de esta ruta: ResponseSent de este
    // plugin no llega a verla. Se mide aqui, con el estado y el codigo de la propia excepcion.
    on(CallFailed) { call, cause ->
        val domain = cause as? BackendDomainException
        recordExamCall(op, registry, call, domain?.status?.value ?: 500, domain?.errorCode)
    }
    on(ResponseSent) { call -> recordExamCall(op, registry, call, call.response.status()?.value ?: 200, null) }
}

private val ExamRecordedKey = AttributeKey<Boolean>("oc-exam-metrics-recorded")
private val UPPER_CODE = Regex("^[A-Z][A-Z0-9_]*$")

private fun recordExamCall(op: String, registry: MeterRegistry, call: io.ktor.server.application.ApplicationCall, status: Int, errorCode: String?) {
    run {
        val start = call.attributes.getOrNull(ExamStartKey) ?: return
        if (call.attributes.getOrNull(ExamRecordedKey) == true) return
        call.attributes.put(ExamRecordedKey, true)
        val elapsed = Duration.ofNanos(System.nanoTime() - start)
        val outcome = when {
            status < 400 -> "ok"
            status < 500 -> "4xx"
            else -> "5xx"
        }
        val code = errorCode?.takeIf { UPPER_CODE.matches(it) } ?: "UNKNOWN"
        Timer.builder("exam_operation_seconds").tag("op", op).tag("outcome", outcome)
            .publishPercentileHistogram().register(registry).record(elapsed)
        call.requestIdOrNull()?.let(UpstreamCallLedger::close)?.let { calls ->
            DistributionSummary.builder("exam_db_calls").tag("op", op).publishPercentileHistogram().register(registry).record(calls.toDouble())
        }
        if (call.response.headers[IdempotencyHeaders.REPLAYED] == "true") registry.counter("exam_idempotency_replay_total", "op", op).increment()
        when (outcome) {
            "4xx" -> registry.counter("exam_4xx_total", "op", op, "code", code).increment()
            "5xx" -> registry.counter("exam_5xx_total", "op", op).increment()
        }
        if (code == CanonicalErrorCode.REVISION_CONFLICT.name) registry.counter("exam_revision_conflict_total", "op", op).increment()
        if (op == ExamOp.RESOURCE_ACCESS && outcome != "ok") registry.counter("exam_resource_access_error_total", "code", code).increment()
    }
}

/** Nombres fijos de operacion (la etiqueta `op`). */
internal object ExamOp {
    const val START = "start"
    const val GET = "get"
    const val QUESTION_PAGE = "question_page"
    const val ANSWER = "answer"
    const val FINISH = "finish"
    const val ABANDON = "abandon"
    const val RESULT = "result"
    const val REVIEW = "review"
    const val RESOURCE_ACCESS = "resource_access"
}
