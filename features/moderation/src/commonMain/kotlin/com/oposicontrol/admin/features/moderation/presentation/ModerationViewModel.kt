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
                    editorActionType = "warning",
                    editorTargetUser = "",
                    editorReason = "",
                    actionState = UiAsync.Idle,
                )
            }

            ModerationIntent.Refresh -> refresh()
            ModerationIntent.Save -> save()
            is ModerationIntent.Select -> select(intent.id)
            is ModerationIntent.UpdateActionType -> updateState { it.copy(editorActionType = intent.value) }
            is ModerationIntent.UpdateExecutor -> updateState { it.copy(editorExecutor = intent.value) }
            is ModerationIntent.UpdateReason -> updateState { it.copy(editorReason = intent.value) }
            is ModerationIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is ModerationIntent.UpdateTargetUser -> updateState { it.copy(editorTargetUser = intent.value) }
        }
    }

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allActions.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorActionType = item.actionType,
                editorTargetUser = item.targetUser,
                editorExecutor = item.executor,
                editorReason = item.reason,
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
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

    private suspend fun refresh() {
        updateState { it.copy(actions = UiAsync.Loading) }
        when (val result = repository.getModerationActions()) {
            is AppResult.Failure -> updateState { it.copy(actions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                allActions = result.value.map { it.toRow() }
                publish()
                if (currentState().selectedId == null) allActions.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    private suspend fun save() {
        if (currentState().editorTargetUser.isBlank() || currentState().editorReason.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Usuario y razon son obligatorios."))) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val row = ModerationAction(
            id = currentState().selectedId.orEmpty(),
            actionType = currentState().editorActionType,
            targetUser = currentState().editorTargetUser,
            executor = currentState().editorExecutor,
            reason = currentState().editorReason,
            createdAt = "",
        )
        when (val result = repository.appendModerationAction(row)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value.toRow()
                allActions = listOf(saved) + allActions.filterNot { it.id == saved.id }
                analyticsTracker.trackAction("moderation_action_save", mapOf("action_id" to saved.id))
                updateState { it.copy(selectedId = saved.id, actionState = UiAsync.Success(Unit)) }
                publish()
                select(saved.id, resetActionState = false)
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
}
