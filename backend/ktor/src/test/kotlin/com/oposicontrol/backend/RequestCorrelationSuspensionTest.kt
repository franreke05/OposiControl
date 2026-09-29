package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * F-12a y F-15: la correlación sobrevive a una SUSPENSIÓN que reanuda en otro
 * hilo, y la sonda de readiness no ocupa el hilo de la petición.
 *
 * `UpstreamIoSeamTest` cubre el salto a IO desde un hilo que YA tiene el MDC. Lo
 * que no cubría es el caso real de un handler: `call.receive()` se suspende,
 * Netty lo reanuda en otro hilo de su pool —sin MDC— y sólo DESPUÉS se salta a IO.
 * `withUpstreamIo` copia el MDC del hilo en el que se le llama, así que la copia
 * salía vacía. `RequestMdcContext` reinstala el id en cada reanudación.
 *
 * Aquí la reanudación en otro hilo se FUERZA: con `Dispatchers.Unconfined` la
 * corrutina continúa en el hilo que la reanuda, y ése es un executor propio.
 */
class RequestCorrelationSuspensionTest {

    private val requestId = "suspension-probe-request-0001"

    @Test
    fun afterResumingOnAnotherThreadTheRequestIdIsStillInTheMdcAndReachesTheIoPool() {
        val resumer: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "correlation-test-resumer") }
        try {
            var startThread = ""
            var resumedThread = ""
            var afterResume: String? = null
            var insideIo: String? = null

            runBlocking {
                withContext(Dispatchers.Unconfined + RequestMdcContext(requestId)) {
                    startThread = Thread.currentThread().name
                    // Lo que hace `call.receive()`: suspenderse y volver en OTRO hilo.
                    suspendCoroutine<Unit> { continuation -> resumer.execute { continuation.resume(Unit) } }
                    resumedThread = Thread.currentThread().name
                    afterResume = MDC.get(MDC_REQUEST_ID)
                    insideIo = withUpstreamIo { MDC.get(MDC_REQUEST_ID) }
                }
            }

            assertNotEquals(
                startThread,
                resumedThread,
                "este test ya no reproduce el escenario: la corrutina no cambió de hilo al reanudar",
            )
            assertEquals(
                requestId,
                afterResume,
                "tras reanudar en otro hilo el MDC llegó vacío: todo log de ese tramo saldría con request_id=-",
            )
            assertEquals(
                requestId,
                insideIo,
                "withUpstreamIo copió un MDC vacío: el ADMIN_ACTION emitido dentro saldría sin su request_id",
            )
            // Y el hilo prestado queda limpio: su siguiente tarea no hereda un id ajeno.
            assertNull(resumer.submit(Callable { MDC.get(MDC_REQUEST_ID) }).get())
        } finally {
            resumer.shutdownNow()
        }
    }

    @Test
    fun theRequestContextRestoresWhateverTheThreadHadBefore() {
        MDC.put(MDC_REQUEST_ID, "el-dueno-anterior-de-este-hilo")
        try {
            val element = RequestMdcContext(requestId)
            val previous = element.updateThreadContext(kotlin.coroutines.EmptyCoroutineContext)

            assertEquals(requestId, MDC.get(MDC_REQUEST_ID))
            element.restoreThreadContext(kotlin.coroutines.EmptyCoroutineContext, previous)
            assertEquals("el-dueno-anterior-de-este-hilo", MDC.get(MDC_REQUEST_ID))
        } finally {
            MDC.remove(MDC_REQUEST_ID)
        }
    }

    /**
     * F-15: la sonda de Supabase de `/health/ready` BLOQUEA hasta 5 s y tiene que
     * correr fuera del hilo de la petición: es la ruta que el balanceador consulta
     * para decidir si la réplica sigue viva.
     */
    @Test
    fun theReadinessProbeRunsOffTheRequestThread() {
        RequestThreadProbe("readiness-request-thread").use { probe ->
            val checks = probe.onRequestThread {
                readinessChecks {
                    probe.record("sonda de Supabase")
                    "UP"
                }
            }

            probe.assertRanOffTheRequestThread("readinessChecks")
            assertEquals(mapOf("ktor" to "UP", "supabase" to "UP"), checks)
        }
    }

    /**
     * Lo mismo a través de la RUTA que consulta el balanceador: que `readinessChecks`
     * salte de hilo no sirve de nada si `/health/ready` deja de llamarla.
     *
     * El pipeline se FIJA al hilo de la sonda ([pinPipelineTo]) en vez de confiar en
     * `engine { dispatcher = … }`: con esa configuración el motor de pruebas podía
     * ejecutar el handler en cualquier otro hilo, y entonces «el hilo no se llama como
     * el mío» era cierto con salto y sin él (MUT-F15).
     */
    @Test
    fun theReadyRouteRunsItsProbeOffTheRequestThread() {
        RequestThreadProbe("readiness-route-request-thread").use { probe ->
            testApplication {
                application {
                    pinPipelineTo(probe)
                    install(ContentNegotiation) { json() }
                    routing {
                        observabilityHealthRoutes {
                            probe.record("sonda de /health/ready")
                            "UP"
                        }
                    }
                }

                assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
            }

            probe.assertRanOffTheRequestThread("/health/ready")
        }
    }
}
