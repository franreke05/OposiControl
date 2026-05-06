package com.oposicontrol.admin.core.common.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class MviViewModel<State : Any, Intent : Any>(
    initialState: State,
    dispatcherProvider: DispatcherProvider,
) {
    private val job = SupervisorJob()
    protected val scope = CoroutineScope(dispatcherProvider.main + job)

    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun dispatch(intent: Intent) {
        scope.launch {
            handleIntent(intent)
        }
    }

    protected fun currentState(): State = mutableState.value

    protected fun updateState(reducer: (State) -> State) {
        mutableState.update(reducer)
    }

    protected abstract suspend fun handleIntent(intent: Intent)

    open fun clear() {
        scope.cancel()
    }
}
