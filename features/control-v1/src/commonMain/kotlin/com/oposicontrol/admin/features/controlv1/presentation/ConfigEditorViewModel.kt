package com.oposicontrol.admin.features.controlv1.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ConfigEntry
import com.oposicontrol.admin.domain.model.ConfigValidator
import com.oposicontrol.admin.domain.repository.ControlV1Repository

/** Un interruptor que espera confirmación: encender algo que paga o escribe tiene consecuencias. */
data class PendingSwitch(val key: String, val label: String, val on: Boolean)

data class ConfigEditorState(
    val entries: UiAsync<List<ConfigEntry>> = UiAsync.Loading,
    /** Texto en edición por clave; una clave sin borrador muestra el valor guardado. */
    val drafts: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val pendingSwitch: PendingSwitch? = null,
    val savingKey: String? = null,
    val feedback: UiAsync<Unit> = UiAsync.Idle,
) {
    fun textFor(entry: ConfigEntry): String = drafts[entry.key] ?: entry.value?.toString().orEmpty()
}

sealed interface ConfigEditorIntent {
    data object Refresh : ConfigEditorIntent
    data class Edit(val key: String, val text: String) : ConfigEditorIntent
    data class Save(val key: String) : ConfigEditorIntent
    data class RequestSwitch(val key: String, val on: Boolean) : ConfigEditorIntent
    data object ConfirmSwitch : ConfigEditorIntent
    data object DismissSwitch : ConfigEditorIntent
}

/**
 * Editor de los números de `configuracion_v1` de un grupo (`referido`, `foro`, `ranking`).
 *
 * La validación de aquí es una ayuda del formulario (rango visible, error inmediato); el servidor repite el rango y la
 * base de datos lo hace una tercera vez con un trigger que además audita cada cambio con el autor.
 */
class ConfigEditorViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
    private val group: String,
) : MviViewModel<ConfigEditorState, ConfigEditorIntent>(ConfigEditorState(), dispatcherProvider) {
    private var writing = false

    init {
        dispatch(ConfigEditorIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ConfigEditorIntent) {
        when (intent) {
            ConfigEditorIntent.Refresh -> refresh()
            is ConfigEditorIntent.Edit -> updateState {
                it.copy(drafts = it.drafts + (intent.key to intent.text), errors = it.errors - intent.key, feedback = UiAsync.Idle)
            }
            is ConfigEditorIntent.Save -> save(intent.key)
            is ConfigEditorIntent.RequestSwitch -> updateState { state ->
                val entry = state.entries.entry(intent.key)
                if (entry == null || !entry.isSwitch) state else state.copy(pendingSwitch = PendingSwitch(entry.key, entry.label, intent.on))
            }
            ConfigEditorIntent.DismissSwitch -> updateState { it.copy(pendingSwitch = null) }
            ConfigEditorIntent.ConfirmSwitch -> {
                val pending = currentState().pendingSwitch ?: return
                updateState { it.copy(pendingSwitch = null) }
                write(pending.key, if (pending.on) 1 else 0)
            }
        }
    }

    private suspend fun refresh() {
        if (currentState().entries !is UiAsync.Success) updateState { it.copy(entries = UiAsync.Loading) }
        when (val result = repository.getConfig(group)) {
            is AppResult.Failure -> updateState { it.copy(entries = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(entries = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value), drafts = emptyMap(), errors = emptyMap())
            }
        }
    }

    private suspend fun save(key: String) {
        val state = currentState()
        val entry = state.entries.entry(key) ?: return
        val text = state.textFor(entry)
        val error = ConfigValidator.check(entry, text)
        if (error != null) {
            updateState { it.copy(errors = it.errors + (key to error)) }
            return
        }
        write(key, text.trim().toInt())
    }

    private suspend fun write(key: String, value: Int) {
        if (writing) return
        writing = true
        try {
            updateState { it.copy(savingKey = key, feedback = UiAsync.Loading) }
            when (val result = repository.setConfig(key, value)) {
                is AppResult.Failure -> updateState { it.copy(savingKey = null, feedback = UiAsync.Error(result.error)) }
                is AppResult.Success -> updateState { state ->
                    val list = (state.entries as? UiAsync.Success)?.value.orEmpty().map { if (it.key == key) result.value else it }
                    state.copy(
                        entries = UiAsync.Success(list),
                        drafts = state.drafts - key,
                        errors = state.errors - key,
                        savingKey = null,
                        feedback = UiAsync.Success(Unit),
                    )
                }
            }
        } finally {
            writing = false
        }
    }
}

private fun UiAsync<List<ConfigEntry>>.entry(key: String): ConfigEntry? = (this as? UiAsync.Success)?.value?.firstOrNull { it.key == key }
