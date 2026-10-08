package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository

class ExamAccessViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ExamAccessState, ExamAccessIntent>(
    initialState = ExamAccessState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allAccesses: List<ExamAccessItem> = emptyList()

    init {
        analyticsTracker.trackScreen("exam_access")
        dispatch(ExamAccessIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ExamAccessIntent) {
        when (intent) {
            ExamAccessIntent.CreateNew -> updateState {
                it.copy(
                    selectedId = null,
                    editorUserName = "",
                    editorExamTitle = "",
                    editorAccessType = ExamAccessTypes.DEFAULT,
                    editorIsActive = true,
                    actionState = UiAsync.Idle,
                )
            }

            ExamAccessIntent.Refresh -> refresh()
            ExamAccessIntent.Save -> save()
            is ExamAccessIntent.Select -> select(intent.id)
            is ExamAccessIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is ExamAccessIntent.UpdateAccessType -> updateState { it.copy(editorAccessType = intent.value) }
            is ExamAccessIntent.UpdateExamTitle -> updateState { it.copy(editorExamTitle = intent.value) }
            is ExamAccessIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is ExamAccessIntent.UpdateUserName -> updateState { it.copy(editorUserName = intent.value) }
        }
    }

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allAccesses.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorUserName = item.userName,
                editorExamTitle = item.examTitle,
                editorAccessType = ExamAccessTypes.normalize(item.accessType),
                editorIsActive = item.status == "active",
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allAccesses.filter { item ->
            query.isBlank() || item.userName.lowercase().contains(query) || item.examTitle.lowercase().contains(query)
        }
        updateState { it.copy(accesses = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        updateState { it.copy(accesses = UiAsync.Loading) }
        when (val result = repository.getExamAccesses()) {
            is AppResult.Failure -> updateState { it.copy(accesses = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                allAccesses = result.value.map { it.toItem() }
                publish()
                if (currentState().selectedId == null) allAccesses.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    private suspend fun save() {
        if (currentState().editorUserName.isBlank() || currentState().editorExamTitle.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Usuario y examen son obligatorios."))) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val selected = allAccesses.firstOrNull { it.id == currentState().selectedId }
        val item = ExamAccess(
            id = currentState().selectedId.orEmpty(),
            userName = currentState().editorUserName,
            examTitle = currentState().editorExamTitle,
            accessType = ExamAccessTypes.normalize(currentState().editorAccessType),
            isActive = currentState().editorIsActive,
            // RC-06: se manda el id de la fila elegida mientras el nombre del editor no haya cambiado; un nombre nuevo va sin id
            // (el servidor lo resuelve y responde 409 si es ambiguo).
            userId = selected?.takeIf { it.userName == currentState().editorUserName }?.userId,
            examId = selected?.takeIf { it.examTitle == currentState().editorExamTitle }?.examId,
        )
        when (val result = repository.upsertExamAccess(item)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value.toItem()
                allAccesses = allAccesses.filterNot { it.id == saved.id } + saved
                analyticsTracker.trackAction("exam_access_save", mapOf("access_id" to saved.id))
                updateState { it.copy(selectedId = saved.id, actionState = UiAsync.Success(Unit)) }
                publish()
                select(saved.id, resetActionState = false)
            }
        }
    }

    private fun ExamAccess.toItem() = ExamAccessItem(
        id = id,
        userName = userName,
        examTitle = examTitle,
        accessType = accessType,
        status = if (isActive) "active" else "inactive",
        userId = userId,
        examId = examId,
    )
}
