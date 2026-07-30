package com.oposicontrol.admin.core.common.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

abstract class MviViewModel<State : Any, Intent : Any>(
    initialState: State,
    dispatcherProvider: DispatcherProvider,
) {
    private val job = SupervisorJob()
    protected val scope = CoroutineScope(dispatcherProvider.main + job)
    private val intentQueue = Channel<Intent>(capacity = Channel.UNLIMITED)

    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()

    init {
        scope.launch {
            supervisorScope {
                for (intent in intentQueue) {
                    // WHY: every intent keeps failure isolation while join enforces FIFO completion.
                    launch { handleIntent(intent) }.join()
                }
            }
        }
    }

    fun dispatch(intent: Intent) {
        // An unlimited producer-side queue avoids dropping bursts of UI input.
        intentQueue.trySend(intent)
    }

    protected fun currentState(): State = mutableState.value

    protected fun updateState(reducer: (State) -> State) {
        mutableState.update(reducer)
    }

    protected abstract suspend fun handleIntent(intent: Intent)

    open fun clear() {
        intentQueue.cancel()
        scope.cancel()
    }
}

/**
 * MVI state holder with a buffered, single-consumer stream for transient UI effects.
 *
 * Effects are intentionally separate from [state] so navigation and feedback are not replayed as
 * durable screen state.
 */
abstract class MviEffectViewModel<State : Any, Intent : Any, Effect : Any>(
    initialState: State,
    dispatcherProvider: DispatcherProvider,
) : MviViewModel<State, Intent>(
    initialState = initialState,
    dispatcherProvider = dispatcherProvider,
) {
    private val effectQueue = Channel<Effect>(capacity = Channel.BUFFERED)
    val effects: Flow<Effect> = effectQueue.receiveAsFlow()

    protected suspend fun emitEffect(effect: Effect) {
        effectQueue.send(effect)
    }

    override fun clear() {
        effectQueue.cancel()
        super.clear()
    }
}
