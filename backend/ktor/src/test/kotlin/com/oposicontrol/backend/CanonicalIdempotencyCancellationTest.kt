package com.oposicontrol.backend

import io.ktor.util.Attributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASTER3-IDEM-001: los hooks que liberan/completan el claim corrian en la corrutina de la llamada, posiblemente ya
 * cancelada (cliente desconectado), sin NonCancellable: el `withContext(Dispatchers.IO)` del almacen lanzaba
 * CancellationException, el `catch (Exception)` se la tragaba, la marca de "terminado" ya estaba puesta (la red de
 * seguridad de ResponseSent no actuaba) y la clave quedaba `in_progress` hasta que caducaba el lease: 409 al reintentar.
 *
 * Se prueba el punto unico de finalizacion que usan los hooks CallFailed y ResponseBodyReadyForSend. (El motor de
 * pruebas de Ktor ejecuta esos hooks con la llamada aun activa aunque el handler cancele su Job, asi que una prueba de
 * extremo a extremo con testApplication NO reproduce la desconexion real de Netty; por eso no se incluye.)
 */
class CanonicalIdempotencyCancellationTest {

    @Test
    fun finalizationRunsEvenWhenTheCallIsAlreadyCancelledAndTheCancellationIsNotSwallowed() = runBlocking {
        val attributes = Attributes()
        val ran = AtomicBoolean()
        val continuedAfter = AtomicBoolean()
        val job = launch {
            currentCoroutineContext().job.cancel()
            finalizeIdempotencyClaim(attributes, "req-1", "release") { withContext(Dispatchers.IO) { ran.set(true) } }
            continuedAfter.set(true)
        }
        job.join()
        assertTrue(ran.get(), "el almacen se toca aunque la llamada este cancelada")
        assertEquals(true, attributes.getOrNull(IdempotencyFinishedKey))
        assertFalse(continuedAfter.get(), "la cancelacion se propaga, no se traga")
        assertTrue(job.isCancelled)
    }

    @Test
    fun aStoreFailureLeavesTheFinishedFlagUnsetSoTheSafetyNetStillReleases() = runBlocking {
        val attributes = Attributes()
        finalizeIdempotencyClaim(attributes, "req-2", "release") { error("store down") }
        assertNull(attributes.getOrNull(IdempotencyFinishedKey), "sin respuesta del almacen no se marca como terminado")
    }

}
