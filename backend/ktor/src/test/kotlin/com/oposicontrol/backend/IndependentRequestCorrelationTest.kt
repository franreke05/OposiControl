package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * HASHIRA C — verificación independiente de FASE 1. **SE ESPERA QUE EL SEGUNDO
 * CASO FALLE HOY.** Documenta una regresión silenciosa introducida por §6.2.
 *
 * ### El mecanismo
 *
 * `installRequestCorrelation` (`Observability.kt:361-384`) publica el id de la
 * petición con `MDC.put(MDC_REQUEST_ID, requestId)` en la fase `Setup`, sobre el
 * hilo de request. `MDC` es un `ThreadLocal` y **no** viaja con la corrutina: no
 * hay ningún `MDCContext` instalado, y `kotlinx-coroutines-slf4j` ni siquiera
 * está en `gradle/libs.versions.toml`.
 *
 * `logStrategicEvent` (`Observability.kt:281-283`) resuelve su `requestId` por
 * defecto leyendo **ese** `MDC`.
 *
 * Los 30 `withContext(Dispatchers.IO)` de FASE 1 saltan a otro hilo de forma
 * incondicional. Antes, un handler como
 * `get { call.respond(AdminExamenesResponse(gateway.listExamenes())) }` no tenía
 * ni un punto de suspensión entre `Setup` y el cuerpo, así que corría en el
 * mismo hilo y el `MDC` estaba puesto. Ahora nunca lo está.
 *
 * ### Qué se pierde, en concreto
 *
 * - `registrarAuditoria` (`AdminV1Routes.kt:4299-4308`) emite
 *   `StrategicEvent.ADMIN_ACTION` desde ~20 mutaciones del gateway, **todas**
 *   dentro del bloque IO en las cuatro verticales de esta fase: crear/editar/
 *   publicar/despublicar/archivar examen, archivar pregunta, desvincular,
 *   reordenar, importar lote, recursos. Todas pasan a registrar `request_id=-`.
 * - `StrategicEvent.STORAGE_UPLOAD_FAILED`
 *   (`SupabaseRequestTransport.kt:234` y `:238`) igual, vía
 *   `uploadExamenPortada`.
 *
 * El 500 del cliente **no** se ve afectado: `requestIdOrNull()`
 * (`Observability.kt:392`) lee `call.attributes`, no el `MDC`, y los handlers de
 * `StatusPages` corren fuera del bloque. Por eso el fallo es silencioso: la
 * respuesta sigue diciendo «Referencia: `<id>`» y la traza de auditoría con la
 * que habría que correlacionarla ya no lleva ese id. Que es justo el uso para el
 * que existe el mecanismo (§5.3 del contrato: «es lo único que correlaciona
 * respuesta y log»).
 *
 * ### Arreglo
 *
 * `withContext(Dispatchers.IO + MDCContext())`, que exige añadir
 * `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` al catálogo y al módulo. La
 * alternativa sin dependencia nueva —capturar `call.requestIdOrNull()` y
 * pasarlo a mano— toca ~20 llamadores del gateway y es peor.
 */
class IndependentRequestCorrelationTest {

    @Test fun theRequestIdReachesTheCodeThatRunsOnTheIoDispatcher() = testApplication {
        // Centinelas DISTINTOS a propósito: si el handler no llegara a correr,
        // la comparación de abajo tampoco podría pasar por casualidad.
        val onThePipeline = AtomicReference<String?>("<pipeline no ejecutado>")
        val insideIo = AtomicReference<String?>("<bloque IO no ejecutado>")
        // El hilo que reanuda al handler, como hace Netty cuando el handler se
        // suspende leyendo el cuerpo.
        val resumer = Executors.newSingleThreadExecutor { Thread(it, "correlation-probe-resumer") }

        application {
            installRequestCorrelation()
            routing {
                get("/probe") {
                    // UNA SUSPENSIÓN REAL ANTES DE MIRAR EL MDC, Y NO ES DECORADO.
                    //
                    // Sin ella, el handler corría en el mismo hilo que la fase `Setup`,
                    // donde un `MDC.put` directo dejaba el id puesto aunque el mecanismo
                    // de contexto no existiera: el caso pasaba en verde con
                    // `withContext(RequestMdcContext(...))` quitado (MUT-F12a). Lo que
                    // hay que medir es justo lo contrario: que el id vuelve SOLO tras
                    // reanudar en un hilo que nunca lo tuvo, que es lo que le pasa a
                    // cualquier handler que lea el cuerpo de la petición.
                    suspendCoroutine<Unit> { continuation -> resumer.execute { continuation.resume(Unit) } }
                    onThePipeline.set(MDC.get(MDC_REQUEST_ID))
                    // Exactamente la forma que FASE 1 aplicó en 30 handlers.
                    withContext(Dispatchers.IO) { insideIo.set(MDC.get(MDC_REQUEST_ID)) }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        try {
            assertEquals(HttpStatusCode.OK, client.get("/probe").status)
        } finally {
            resumer.shutdownNow()
        }

        // Control primero: sin esto, si el `MDC` tampoco estuviera puesto en el
        // hilo de pipeline, la comparación de abajo sería `null == null` y el
        // caso pasaría en vacío afirmando lo contrario de lo que dice su nombre.
        // Es la lección de SEC-001, aplicada a este propio test.
        assertNotNull(
            onThePipeline.get(),
            "installRequestCorrelation no repuso el request_id en el MDC tras reanudar en otro hilo: " +
                "desde ahí hasta el final de la petición, todo log sale con request_id=-",
        )

        assertEquals(
            onThePipeline.get(),
            insideIo.get(),
            "el request_id no sobrevive al salto a Dispatchers.IO: " +
                "logStrategicEvent lo resuelve por defecto desde el MDC, así que todo ADMIN_ACTION " +
                "emitido dentro del bloque IO se registra con request_id=-",
        )
    }
}
