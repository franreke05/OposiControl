package com.oposicontrol.admin.core.common.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {
    @Test
    fun `dispatch handles intents in FIFO order without overlap`() = runTest {
        val firstCanFinish = CompletableDeferred<Unit>()
        val viewModel = RecordingViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        )

        viewModel.dispatch(RecordingIntent.Record(id = 1, waitFor = firstCanFinish))
        viewModel.dispatch(RecordingIntent.Record(id = 2))
        runCurrent()

        assertEquals(listOf("start-1"), viewModel.transitions)

        firstCanFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("start-1", "end-1", "start-2", "end-2"),
            viewModel.transitions,
        )
        assertEquals(1, viewModel.maximumConcurrentIntents)
        viewModel.clear()
    }

    @Test
    fun `clear cancels the active intent and discards queued intents`() = runTest {
        val viewModel = CancellationViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        )

        viewModel.dispatch(1)
        viewModel.dispatch(2)
        runCurrent()

        viewModel.clear()
        advanceUntilIdle()

        assertEquals(listOf(1), viewModel.startedIntents)
        assertTrue(viewModel.cancellationObserved.isCompleted)
    }

    @Test
    fun `effect emitted before collection is delivered once`() = runTest {
        val viewModel = RecordingEffectViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        )

        viewModel.dispatch("saved")
        advanceUntilIdle()

        assertEquals("saved", viewModel.effects.first())
        viewModel.clear()
    }
}

private sealed interface RecordingIntent {
    data class Record(
        val id: Int,
        val waitFor: CompletableDeferred<Unit>? = null,
    ) : RecordingIntent
}

private class RecordingViewModel(
    dispatcherProvider: DispatcherProvider,
) : MviViewModel<Unit, RecordingIntent>(
    initialState = Unit,
    dispatcherProvider = dispatcherProvider,
) {
    val transitions = mutableListOf<String>()
    var maximumConcurrentIntents = 0
        private set

    private var activeIntents = 0

    override suspend fun handleIntent(intent: RecordingIntent) {
        when (intent) {
            is RecordingIntent.Record -> {
                activeIntents += 1
                maximumConcurrentIntents = maxOf(maximumConcurrentIntents, activeIntents)
                transitions += "start-${intent.id}"
                try {
                    intent.waitFor?.await()
                } finally {
                    transitions += "end-${intent.id}"
                    activeIntents -= 1
                }
            }
        }
    }
}

private class CancellationViewModel(
    dispatcherProvider: DispatcherProvider,
) : MviViewModel<Unit, Int>(
    initialState = Unit,
    dispatcherProvider = dispatcherProvider,
) {
    val startedIntents = mutableListOf<Int>()
    val cancellationObserved = CompletableDeferred<Unit>()

    override suspend fun handleIntent(intent: Int) {
        startedIntents += intent
        try {
            awaitCancellation()
        } finally {
            cancellationObserved.complete(Unit)
        }
    }
}

private class RecordingEffectViewModel(
    dispatcherProvider: DispatcherProvider,
) : MviEffectViewModel<Unit, String, String>(
    initialState = Unit,
    dispatcherProvider = dispatcherProvider,
) {
    override suspend fun handleIntent(intent: String) {
        emitEffect(intent)
    }
}

private class TestDispatcherProvider(
    dispatcher: TestDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}
