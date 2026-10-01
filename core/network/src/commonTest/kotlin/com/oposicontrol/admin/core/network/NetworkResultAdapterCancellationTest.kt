package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.result.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** MASTER6-QUALITY-012: la cancelacion estructurada atraviesa `safeCall`: ni AppResult.Failure ni log de error. */
class NetworkResultAdapterCancellationTest {
    private class RecordingLogger : Logger {
        var errors = 0
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) {
            errors++
        }
    }

    @Test fun cancellationExceptionIsRethrownNotWrappedAsNetworkFailure() = runTest {
        val logger = RecordingLogger()
        assertFailsWith<CancellationException> {
            NetworkResultAdapter(logger).safeCall<Unit> { throw CancellationException("cancelled") }
        }
        assertEquals(0, logger.errors, "una cancelacion no es un error de red: no se registra")
    }

    @Test fun cancellingTheCallerCancelsTheCallInsteadOfYieldingAFailure() = runTest {
        val logger = RecordingLogger()
        val started = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            NetworkResultAdapter(logger).safeCall<Unit> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, logger.errors)
    }

    @Test fun otherThrowablesStillMapToFailure() = runTest {
        val r = NetworkResultAdapter(RecordingLogger()).safeCall<Unit> { error("boom") }
        assertTrue(r is AppResult.Failure)
    }
}
