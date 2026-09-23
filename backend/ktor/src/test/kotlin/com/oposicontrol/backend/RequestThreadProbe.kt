package com.oposicontrol.backend

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El arnés ÚNICO para afirmar «esto no corre en el hilo de la petición».
 *
 * ### Por qué existe, y qué falló antes
 *
 * Cinco pruebas afirmaban ese salto y las cinco seguían en VERDE con el salto
 * QUITADO (mutaciones MUT-F03a, F03b, F10, F15 y F12a). Todas compartían la misma
 * forma: creaban un executor de un hilo con NOMBRE propio, suponían que el hilo de
 * la petición sería ése, y comprobaban `nombreDelHilo != "mi-nombre"`. Esa
 * comparación es verdadera **también cuando la suposición es falsa**: si el arnés
 * no consigue que el pipeline corra en ese hilo —y el motor de `testApplication` no
 * respeta siempre `engine { dispatcher = … }`—, el trabajo corre en un hilo
 * cualquiera, que tampoco se llama así, y la aserción pasa sin haber medido nada.
 * Una prueba que no puede fallar no es una prueba.
 *
 * Aquí se cierran los dos huecos a la vez:
 *
 * 1. **El hilo de la petición se REGISTRA, no se supone.** [rememberRequestThread]
 *    lo anota desde dentro de la propia petición, así que es el hilo real pase lo
 *    que pase con el motor.
 * 2. **Se comparan IDENTIDADES de hilo, no nombres.** Dos hilos pueden llamarse
 *    igual y un nombre puede reutilizarse; una identidad, no.
 * 3. **Hay control de vacío.** Si no se registró ningún hilo de petición, o el
 *    trabajo no llegó a ejecutarse, la aserción falla diciendo que el arnés está
 *    roto en vez de dar por buena una medición que no existe.
 *
 * Un solo patrón para las cinco: quien mida otro salto de hilo usa esto y no
 * inventa un sexto arnés.
 */
internal class RequestThreadProbe(name: String = "oc-request-thread") : AutoCloseable {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }

    /** El hilo de la petición, para fijar ahí el pipeline ([pinPipelineTo]) o una llamada directa. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val requestThreads: MutableSet<Thread> = Collections.newSetFromMap(ConcurrentHashMap())
    private val offloaded = CopyOnWriteArrayList<Pair<String, Thread>>()

    /** Corre [block] COMO SI fuera el hilo de la petición, y lo registra como tal. */
    fun <T> onRequestThread(block: suspend CoroutineScope.() -> T): T = runBlocking(dispatcher) {
        rememberRequestThread()
        block()
    }

    /** Anota el hilo actual como hilo de la petición. Se llama DENTRO de la petición. */
    fun rememberRequestThread() {
        requestThreads += Thread.currentThread()
    }

    /** Anota dónde corrió de verdad un trabajo que debería estar fuera del hilo de la petición. */
    fun record(label: String) {
        offloaded += label to Thread.currentThread()
    }

    fun clear() = offloaded.clear()

    /**
     * Afirma que TODO lo registrado con [record] corrió fuera del hilo de la petición.
     *
     * [context] entra en el mensaje para que un fallo diga qué llamada fue, que es lo
     * que hace falta cuando la prueba recorre diez rutas.
     */
    fun assertRanOffTheRequestThread(context: String) {
        assertTrue(
            requestThreads.isNotEmpty(),
            "$context · arnés roto: no se registró ningún hilo de petición, así que no hay nada con lo que comparar",
        )
        assertTrue(
            offloaded.isNotEmpty(),
            "$context · el trabajo que debía saltar al pool IO no llegó a ejecutarse",
        )
        offloaded.forEach { (label, thread) ->
            assertFalse(
                thread in requestThreads,
                "$context · «$label» corrió en el hilo de la petición (${thread.name}): " +
                    "una llamada bloqueante ahí agota el pool y la réplica deja de aceptar peticiones",
            )
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * Fija TODO el pipeline —plugins, autorización y handler— al hilo de [probe], y lo
 * registra como hilo de la petición.
 *
 * Se instala con `intercept(Setup)` y `withContext { proceed() }`, que es exactamente
 * lo que hace `installRequestCorrelation` en producción, así que no introduce ninguna
 * forma de ejecución que el servidor real no tenga. Va SIEMPRE el primero: lo que se
 * instale después queda dentro de este contexto.
 */
internal fun Application.pinPipelineTo(probe: RequestThreadProbe) {
    intercept(ApplicationCallPipeline.Setup) {
        withContext(probe.dispatcher) {
            probe.rememberRequestThread()
            proceed()
        }
    }
}
