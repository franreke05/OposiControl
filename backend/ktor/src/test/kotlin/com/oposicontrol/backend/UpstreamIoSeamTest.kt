package com.oposicontrol.backend

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La costura [withUpstreamIo] / [withUpstreamIoParallel], probada donde de
 * verdad se rompe: **al cambiar de hilo**.
 *
 * ### Por que este fichero existe y no basta con el arnes de `testApplication`
 *
 * Un test que monte `installRequestCorrelation()` y haga el salto dentro de
 * `testApplication` **pasa incluso con la costura rota**: en ese arnes el
 * handler ya corre en un contexto donde el salto al pool IO no llega a cambiar
 * de hilo, asi que el `MDC` sigue puesto por casualidad y la asercion no
 * distingue "se propaga" de "no hizo falta propagar". El mecanismo es real en
 * produccion —bajo Netty el salto SI cambia de hilo— pero ese arnes no lo
 * reproduce.
 *
 * Aqui el cambio de hilo se fuerza: el papel de "hilo de la peticion" lo hace
 * un executor de un solo hilo con nombre propio, distinto por construccion de
 * cualquier hilo del pool IO. Y cada caso **afirma primero que el hilo
 * cambio**: si algun dia deja de cambiar, el test no pasa en silencio — falla
 * diciendo que ya no reproduce el escenario, que es la unica forma honesta de
 * que un test de concurrencia envejezca.
 */
class UpstreamIoSeamTest {

    private val requestId = "seam-probe-request-0001"

    // -----------------------------------------------------------------
    // withUpstreamIo
    // -----------------------------------------------------------------

    @Test
    fun theRequestIdSurvivesARealThreadSwitchIntoTheIoPool() = onAPipelineThread {
        MDC.put(MDC_REQUEST_ID, requestId)
        val callerThread = Thread.currentThread().name

        val observed = withUpstreamIo { Thread.currentThread().name to MDC.get(MDC_REQUEST_ID) }

        assertThreadReallyChanged(callerThread, observed.first)
        assertEquals(
            requestId,
            observed.second,
            "el request_id no sobrevivio al salto al pool IO: logStrategicEvent lo resuelve por " +
                "defecto desde el MDC, asi que todo ADMIN_ACTION emitido dentro del bloque se " +
                "registraria con request_id=-",
        )
    }

    /**
     * El caso anterior comprueba el mecanismo; este comprueba **la consecuencia
     * que le importa a un humano**: la linea de log que hay que correlacionar
     * con la referencia que el cliente recibio.
     *
     * Se mira el texto real que emitiria Logback, no el `MDC` directamente:
     * entre el `MDC` y la linea esta el `requestId` por defecto de
     * [logStrategicEvent], y ese parametro tambien se puede romper.
     */
    @Test
    fun anAdminActionLoggedInsideTheSeamCarriesTheRequestIdAndNotADash() = onAPipelineThread {
        MDC.put(MDC_REQUEST_ID, requestId)
        val callerThread = Thread.currentThread().name
        var blockThread = callerThread

        val lines = captureStrategicLogs {
            withUpstreamIo {
                blockThread = Thread.currentThread().name
                logStrategicEvent(
                    StrategicEvent.ADMIN_ACTION,
                    operation = "examen_publicado",
                    entityType = "examenes",
                )
            }
        }

        assertThreadReallyChanged(callerThread, blockThread)
        val line = lines.single()
        assertTrue(
            line.contains("request_id=$requestId"),
            "un ADMIN_ACTION emitido desde el pool IO llego sin su request_id. Linea real: $line",
        )
    }

    // -----------------------------------------------------------------
    // Limpieza: los hilos del pool se REUTILIZAN
    // -----------------------------------------------------------------

    /**
     * Un id equivocado es peor que ninguno: `request_id=-` se ve de un vistazo,
     * una correlacion falsa hacia otra peticion no se ve nunca. Por eso la
     * costura restaura, y no solo instala.
     */
    @Test
    fun theSeamLeavesTheBorrowedThreadExactlyAsItFoundIt() {
        MDC.put(MDC_REQUEST_ID, "el-dueno-anterior-de-este-hilo")
        try {
            val insideBlock = runWithMdcContext(mapOf(MDC_REQUEST_ID to requestId)) {
                MDC.get(MDC_REQUEST_ID)
            }

            assertEquals(requestId, insideBlock)
            assertEquals(
                "el-dueno-anterior-de-este-hilo",
                MDC.get(MDC_REQUEST_ID),
                "la costura se dejo puesto el MDC de una peticion ajena en un hilo del pool",
            )
        } finally {
            MDC.remove(MDC_REQUEST_ID)
        }
    }

    /**
     * Sin correlacion de entrada, el bloque tiene que ver el MDC **vacio** y no
     * los restos de quien uso ese hilo antes. Es la otra mitad del caso de
     * arriba, y la que convertiria un `-` honesto en una mentira.
     */
    @Test
    fun aBlockWithoutCorrelationSeesAnEmptyMdcAndNotTheLeftoversOfThePool() {
        MDC.put(MDC_REQUEST_ID, "restos-de-otra-peticion")
        try {
            val insideBlock = runWithMdcContext(null) { MDC.get(MDC_REQUEST_ID) }

            assertNull(insideBlock, "el bloque heredo el request_id de la peticion anterior del hilo")
            assertEquals("restos-de-otra-peticion", MDC.get(MDC_REQUEST_ID))
        } finally {
            MDC.remove(MDC_REQUEST_ID)
        }
    }

    // -----------------------------------------------------------------
    // withUpstreamIoParallel
    // -----------------------------------------------------------------

    /**
     * La variante paralela copia el MDC **antes** de repartir las ramas, porque
     * capturarlo dentro de cada una lo leeria ya desde un hilo que puede no
     * tenerlo. Este caso se pone rojo si alguien mueve esa captura hacia dentro.
     */
    @Test
    fun everyBranchOfTheParallelSeamCarriesTheRequestId() = onAPipelineThread {
        MDC.put(MDC_REQUEST_ID, requestId)
        val callerThread = Thread.currentThread().name

        val observed = withUpstreamIoParallel {
            listOf(
                asyncUpstreamIo { Thread.currentThread().name to MDC.get(MDC_REQUEST_ID) },
                asyncUpstreamIo { Thread.currentThread().name to MDC.get(MDC_REQUEST_ID) },
                asyncUpstreamIo { Thread.currentThread().name to MDC.get(MDC_REQUEST_ID) },
            ).awaitAll()
        }

        observed.forEach { (thread, id) ->
            assertThreadReallyChanged(callerThread, thread)
            assertEquals(requestId, id, "una rama paralela perdio la correlacion de la peticion")
        }
    }

    // -----------------------------------------------------------------
    // Infraestructura
    // -----------------------------------------------------------------

    /**
     * Ejecuta [block] en un hilo dedicado que hace de "hilo de la peticion".
     *
     * Un executor de un solo hilo, y no `Dispatchers.Default`: asi el hilo de
     * partida es distinto de cualquier hilo del pool IO **por construccion**, y
     * el control de [assertThreadReallyChanged] no depende de con cuantos
     * nucleos corra la maquina de CI.
     *
     * El `MDC` se limpia al salir porque el hilo se destruye igualmente, pero
     * dejarlo sucio haria que el orden de los casos importara.
     */
    private fun onAPipelineThread(block: suspend () -> Unit) {
        val pipeline: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "seam-test-pipeline-thread")
        }
        try {
            runBlocking(pipeline.asCoroutineDispatcher()) {
                try {
                    block()
                } finally {
                    MDC.clear()
                }
            }
        } finally {
            pipeline.shutdownNow()
        }
    }

    private fun assertThreadReallyChanged(callerThread: String, observedThread: String) {
        assertNotEquals(
            callerThread,
            observedThread,
            "este test ya no reproduce el escenario: el bloque corrio en el MISMO hilo que el " +
                "llamante ('$callerThread'), asi que el MDC habria sobrevivido solo. Mientras siga " +
                "asi, la asercion de propagacion no demuestra nada.",
        )
    }

    private class CapturingAppender : AppenderBase<ILoggingEvent>() {
        val messages = mutableListOf<String>()
        override fun append(event: ILoggingEvent) {
            messages += event.formattedMessage
        }
    }

    /** Mismo patron que `ObservabilityTest`: se verifica la linea REAL que
     * emitiria Logback, no solo que la funcion no lance. `suspend` para que el
     * bloque pueda hacer el salto de hilo sin un `runBlocking` anidado. */
    private suspend fun captureStrategicLogs(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as ch.qos.logback.classic.Logger
        val appender = CapturingAppender().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.messages.toList()
        } finally {
            logger.detachAppender(appender)
        }
    }
}
