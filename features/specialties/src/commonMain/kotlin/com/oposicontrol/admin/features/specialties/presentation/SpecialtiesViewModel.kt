package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviEffectViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class SpecialtiesViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSpecialtiesUseCase: ObserveSpecialtiesUseCase,
    private val refreshSpecialtiesUseCase: RefreshSpecialtiesUseCase,
    private val upsertSpecialtyUseCase: UpsertSpecialtyUseCase,
    private val deleteSpecialtyUseCase: DeleteSpecialtyUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviEffectViewModel<SpecialtiesState, SpecialtiesIntent, SpecialtiesEffect>(
    initialState = SpecialtiesState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allItems: List<Specialty> = emptyList()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var lastSavedDraft: SpecialtyDraft? = null

    init {
        analyticsTracker.trackScreen("specialties")
        scope.launch {
            observeSpecialtiesUseCase().collectLatest { specialties ->
                allItems = specialties
                publish()
                val state = currentState()
                if (!state.isCreating && state.selectedId == null && specialties.isNotEmpty()) {
                    select(SpecialtyId(specialties.first().id))
                } else if (
                    state.selectedId != null &&
                    specialties.none { it.id == state.selectedId.value }
                ) {
                    specialties.firstOrNull()?.let { select(SpecialtyId(it.id)) } ?: createNew()
                }
            }
        }
        dispatch(SpecialtiesIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SpecialtiesIntent) {
        when (intent) {
            SpecialtiesIntent.CreateNew -> createNew()
            SpecialtiesIntent.DeleteSelected -> deleteSelected()
            SpecialtiesIntent.Refresh -> refresh()
            SpecialtiesIntent.Save -> save()
            is SpecialtiesIntent.Select -> select(intent.id)
            is SpecialtiesIntent.ToggleActive -> editorChanged {
                it.copy(editorIsActive = intent.value)
            }

            is SpecialtiesIntent.UpdateDescription -> editorChanged {
                it.copy(editorDescription = intent.value)
            }

            is SpecialtiesIntent.UpdateName -> editorChanged {
                it.copy(editorName = intent.value)
            }

            is SpecialtiesIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is SpecialtiesIntent.UpdateSortOrder -> editorChanged {
                it.copy(editorSortOrder = intent.value)
            }
        }
    }

    private fun createNew() {
        lastSavedDraft = null
        updateState {
            it.copy(
                selectedId = null,
                isCreating = true,
                editorName = "",
                editorDescription = "",
                editorSortOrder = allItems.size.toString(),
                editorIsActive = true,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun editorChanged(transform: (SpecialtiesState) -> SpecialtiesState) {
        lastSavedDraft = null
        updateState { transform(it).copy(actionState = UiAsync.Idle) }
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        updateState {
            it.copy(
                items = if (it.items is UiAsync.Success || it.items is UiAsync.Empty) it.items else UiAsync.Loading,
                isRefreshing = true,
                refreshError = null,
            )
        }
        refreshJob = scope.launch {
            val result = refreshSpecialtiesUseCase()
            // WHY: keep stale transport completions from replacing the newest refresh result.
            if (generation != refreshGeneration) return@launch
            when (result) {
                is AppResult.Failure -> updateState {
                    it.copy(
                        items = if (it.items is UiAsync.Success || it.items is UiAsync.Empty) {
                            it.items
                        } else {
                            UiAsync.Error(result.error)
                        },
                        isRefreshing = false,
                        refreshError = result.error,
                    )
                }

                is AppResult.Success -> {
                    publish()
                    updateState { it.copy(isRefreshing = false, refreshError = null) }
                }
            }
        }
    }

    private fun select(id: SpecialtyId) {
        val item = allItems.firstOrNull { it.id == id.value } ?: return
        lastSavedDraft = null
        updateState {
            it.copy(
                selectedId = SpecialtyId(item.id),
                isCreating = false,
                editorName = item.name,
                editorDescription = item.description,
                editorSortOrder = item.sortOrder.toString(),
                editorIsActive = item.isActive,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allItems.filter { item ->
            query.isBlank() ||
                item.name.lowercase().contains(query) ||
                item.description.lowercase().contains(query)
        }
        updateState {
            it.copy(items = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered))
        }
    }

    private suspend fun save() {
        val state = currentState()
        val existing = allItems.firstOrNull { it.id == state.selectedId?.value }
        val sortOrder = state.editorSortOrder.toIntOrNull()
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("El orden debe ser numerico.")))
            }
        val draft = SpecialtyDraft(
            name = state.editorName.trim(),
            description = state.editorDescription.trim(),
            sortOrder = sortOrder,
            isActive = state.editorIsActive,
        )
        if (draft == lastSavedDraft && state.actionState is UiAsync.Success) return

        updateState { it.copy(actionState = UiAsync.Loading) }
        val specialty = Specialty(
            id = existing?.id.orEmpty(),
            name = draft.name,
            description = draft.description,
            isActive = draft.isActive,
            sortOrder = sortOrder,
            linkedExams = existing?.linkedExams ?: 0,
            createdAt = existing?.createdAt ?: Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        when (val result = upsertSpecialtyUseCase(specialty)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_save", mapOf("specialty_id" to result.value.id))
                lastSavedDraft = draft
                allItems = allItems.filterNot { it.id == result.value.id } + result.value
                updateState {
                    it.copy(
                        selectedId = SpecialtyId(result.value.id),
                        isCreating = false,
                        editorName = result.value.name,
                        editorDescription = result.value.description,
                        editorSortOrder = result.value.sortOrder.toString(),
                        editorIsActive = result.value.isActive,
                        actionState = UiAsync.Success(Unit),
                    )
                }
                publish()
                emitEffect(SpecialtiesEffect.Saved(SpecialtyId(result.value.id)))
            }
        }
    }

    private suspend fun deleteSelected() {
        val selectedId = currentState().selectedId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una especialidad para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteSpecialtyUseCase(selectedId.value)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_delete", mapOf("specialty_id" to selectedId.value))
                allItems = allItems.filterNot { it.id == selectedId.value }
                lastSavedDraft = null
                updateState {
                    it.copy(
                        selectedId = null,
                        isCreating = true,
                        editorName = "",
                        editorDescription = "",
                        editorSortOrder = "0",
                        editorIsActive = true,
                        actionState = UiAsync.Success(Unit),
                    )
                }
                publish()
                emitEffect(SpecialtiesEffect.Deleted(selectedId))
            }
        }
    }

    private data class SpecialtyDraft(
        val name: String,
        val description: String,
        val sortOrder: Int,
        val isActive: Boolean,
    )
}
