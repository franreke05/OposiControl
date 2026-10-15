package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class ModerationViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    private val authRepository: AuthRepository,
) : MviViewModel<ModerationState, ModerationIntent>(
    initialState = ModerationState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allActions: List<ModerationActionRow> = emptyList()

    init {
        analyticsTracker.trackScreen("moderation")
        scope.launch {
            val displayName = authRepository.observeSession().firstOrNull()?.displayName.orEmpty()
            if (displayName.isNotBlank()) updateState { it.copy(editorExecutor = displayName) }
        }
        dispatch(ModerationIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ModerationIntent) {
        when (intent) {
            ModerationIntent.CreateNew -> updateState {
                it.copy(
                    selectedId = null,
                    editorActionType = MODERATION_ACTION_WARNING,
                    editorTargetUser = "",
                    editorReason = "",
                    actionState = UiAsync.Idle,
                )
            }

            ModerationIntent.Refresh -> refresh(autoSelect = true)
            ModerationIntent.Save -> save()
            is ModerationIntent.Select -> select(intent.id)
            is ModerationIntent.UpdateReason -> updateState { it.copy(editorReason = intent.value) }
            is ModerationIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is ModerationIntent.UpdateTargetUser -> updateState { it.copy(editorTargetUser = intent.value) }
        }
    }

    /** Una fila del historial se consulta, no se vuelve a enviar: el formulario solo existe en «Nueva acción». */
    private fun select(id: String) {
        val item = allActions.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorActionType = item.actionType,
                editorTargetUser = item.targetUser,
                editorReason = item.reason,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allActions.filter { action ->
            query.isBlank() || action.targetUser.lowercase().contains(query) || action.actionType.lowercase().contains(query)
        }
        updateState { it.copy(actions = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh(autoSelect: Boolean) {
        updateState { it.copy(actions = UiAsync.Loading) }
        when (val result = repository.getModerationActions()) {
            is AppResult.Failure -> updateState { it.copy(actions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                allActions = result.value.map { it.toRow() }
                publish()
                if (autoSelect && currentState().selectedId == null) allActions.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    private suspend fun save() {
        val state = currentState()
        if (state.selectedId != null) return
        val target = state.editorTargetUser.trim()
        val reason = state.editorReason.trim()
        val invalid = when {
            !UUID.matches(target) -> "El usuario objetivo debe ser su identificador (UUID), no un nombre ni un correo."
            reason.isBlank() -> "La advertencia necesita un motivo."
            reason.length > REASON_MAX -> "El motivo no puede superar $REASON_MAX caracteres."
            else -> null
        }
        if (invalid != null) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(invalid))) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val action = ModerationAction(
            id = "",
            actionType = MODERATION_ACTION_WARNING,
            targetUser = target.lowercase(),
            executor = "",
            reason = reason,
            createdAt = "",
        )
        when (val result = repository.appendModerationAction(action)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("moderation_action_save", mapOf("action_type" to MODERATION_ACTION_WARNING))
                updateState { it.copy(editorTargetUser = "", editorReason = "", actionState = UiAsync.Success(Unit)) }
                // Nada de filas locales: lo que se ve es lo que el servidor devuelve.
                refresh(autoSelect = false)
            }
        }
    }

    private fun ModerationAction.toRow() = ModerationActionRow(
        id = id,
        actionType = actionType,
        targetUser = targetUser,
        executor = executor,
        reason = reason,
        createdAt = createdAt,
    )

    private companion object {
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** AUDIT_TEXT_MAX del backend: más largo es un 400. */
        const val REASON_MAX = 1_000
    }
}
