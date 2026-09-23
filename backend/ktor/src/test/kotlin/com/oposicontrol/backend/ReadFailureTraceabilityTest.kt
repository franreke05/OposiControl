package com.oposicontrol.backend

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-12b · Un fallo que se traga a propósito tiene que dejar rastro que se pueda buscar.
 *
 * Una cifra del panel que no se pudo leer, una franja entera que falló y una fila de
 * auditoría que no se pudo escribir NO tumban la respuesta: es deliberado. Por eso
 * mismo el log es su único rastro, y ese rastro era un `System.err.println` sin
 * `request_id` y sin evento: quien buscaba la referencia que vio el administrador no
 * lo encontraba, y nadie podía contar cuántas veces pasaba.
 *
 * Se comprueba lo que emite Logback de verdad: la línea WARN con el `request_id` en
 * su MDC —leído en el hilo del pool IO que la escribe, no en el del test—, el evento
 * `SUPABASE_REQUEST_FAILED` que la hace contable, y que stderr ya no recibe nada.
 */
class ReadFailureTraceabilityTest {

    private val requestId = "req-f12b-0001"

    @AfterTest
    fun clearMdc() {
        MDC.remove(MDC_REQUEST_ID)
    }

    @Test
    fun aFailedDashboardReadAndAFailedBlockLeaveAWarningAndACountableEventWithTheRequestId() {
        val gateway = AdminDashboardOverviewGateway(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({}) { V1StubResponse(500, UPSTREAM_BODY) },
        )

        val trace = trace("OC-KTOR-DASHBOARD") {
            MDC.put(MDC_REQUEST_ID, requestId)
            val overview = runBlocking {
                gateway.overview(setOf(DashboardOverviewBlock.CONTENT, DashboardOverviewBlock.ACTIVITY))
            }
            assertEquals(listOf(DashboardOverviewBlock.ACTIVITY), overview.failedBlocks, "la cronología falla entera")
        }

        // CONTENT: cuatro conteos fallidos, cuatro avisos. ACTIVITY: la franja entera, uno.
        assertEquals(5, trace.warnings.size, trace.warnings.joinToString("\n") { it.message })
        trace.warnings.forEach { warning ->
            assertEquals(requestId, warning.requestId, "aviso sin request_id: ${warning.message}")
        }
        val readEvents = trace.events.filter { "operation=dashboard_read_" in it }
        assertEquals(4, readEvents.size, trace.events.joinToString("\n"))
        readEvents.forEach { event ->
            assertTrue("event=SUPABASE_REQUEST_FAILED" in event, event)
            assertTrue("request_id=$requestId" in event, event)
            assertTrue("result=read_failed" in event, event)
        }
        val blockEvent = trace.events.single { "operation=dashboard_overview_activity" in it }
        assertTrue("result=block_failed" in blockEvent && "request_id=$requestId" in blockEvent, blockEvent)
        assertFalse("[ktor] dashboard" in trace.stderr, "sigue escribiendo en stderr: ${trace.stderr}")
    }

    @Test
    fun aFailedAuditWriteLeavesAWarningAndACountableEventAndTheOperationGoesOn() {
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({}) { V1StubResponse(500, UPSTREAM_BODY) },
        )

        val trace = trace("OC-KTOR-AUDIT") {
            MDC.put(MDC_REQUEST_ID, requestId)
            // No lanza: una auditoría perdida no deshace la operación que ya se hizo.
            gateway.registrarAuditoria("examen_publicado", "examenes", EXAM_ID)
        }

        val warning = trace.warnings.single()
        assertEquals(requestId, warning.requestId)
        assertTrue("examen_publicado" in warning.message, warning.message)
        val failure = trace.events.single { "event=SUPABASE_REQUEST_FAILED" in it }
        assertTrue("request_id=$requestId" in failure, failure)
        assertTrue("operation=auditoria_examen_publicado" in failure, failure)
        assertTrue("result=audit_write_failed" in failure, failure)
        assertTrue("entity_id=$EXAM_ID" in failure, failure)
        assertFalse("auditoria fallida" in trace.stderr, "sigue escribiendo en stderr: ${trace.stderr}")
    }

    // -----------------------------------------------------------------

    private class Recorded(val message: String, val requestId: String?)

    private class Recorder : AppenderBase<ILoggingEvent>() {
        val recorded = CopyOnWriteArrayList<Recorded>()

        override fun append(event: ILoggingEvent) {
            // El MDC se lee AQUÍ, en el hilo que registra: Logback lo resuelve de forma
            // perezosa, y leído después desde el hilo del test sería el MDC de otro hilo.
            recorded += Recorded(event.formattedMessage, event.getMDCPropertyMap()[MDC_REQUEST_ID])
        }
    }

    private class Trace(val warnings: List<Recorded>, val events: List<String>, val stderr: String)

    private fun trace(loggerName: String, block: () -> Unit): Trace {
        val source = LoggerFactory.getLogger(loggerName) as ch.qos.logback.classic.Logger
        val observability = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as ch.qos.logback.classic.Logger
        val warnings = Recorder().apply { start() }
        val events = Recorder().apply { start() }
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        source.addAppender(warnings)
        observability.addAppender(events)
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(originalErr)
            source.detachAppender(warnings)
            observability.detachAppender(events)
        }
        return Trace(
            warnings = warnings.recorded.toList(),
            events = events.recorded.map { it.message },
            stderr = stderr.toString(Charsets.UTF_8),
        )
    }

    private companion object {
        const val EXAM_ID = "11111111-1111-4111-8111-111111111111"

        /** Forma real de un error de PostgREST. */
        const val UPSTREAM_BODY = """{"code":"XX000","details":null,"hint":null,"message":"fallo de prueba"}"""
    }
}
