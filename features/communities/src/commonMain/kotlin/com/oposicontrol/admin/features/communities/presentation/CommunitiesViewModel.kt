package com.oposicontrol.admin.features.communities.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.usecase.communities.DeleteCommunityUseCase
import com.oposicontrol.admin.domain.usecase.communities.ObserveCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.RefreshCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.UpsertCommunityUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class CommunitiesViewModel(
    dispatcherProvider: DispatcherProvider,
    observeCommunitiesUseCase: ObserveCommunitiesUseCase,
    private val refreshCommunitiesUseCase: RefreshCommunitiesUseCase,
    private val upsertCommunityUseCase: UpsertCommunityUseCase,
    private val deleteCommunityUseCase: DeleteCommunityUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<CommunitiesState, CommunitiesIntent>(
    initialState = CommunitiesState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allItems: List<Community> = emptyList()

    init {
        scope.launch {
            observeCommunitiesUseCase().collectLatest { communities ->
                allItems = communities
                publish()
                if (currentState().selectedId == null && communities.isNotEmpty()) {
                    select(communities.first().id)
                }
            }
        }
        dispatch(CommunitiesIntent.Refresh)
    }

    override suspend fun handleIntent(intent: CommunitiesIntent) {
        when (intent) {
            CommunitiesIntent.CreateNew -> updateState {
                it.copy(
                    selectedId = null,
                    editorName = "",
                    editorCode = "",
                    editorSlug = "",
                    editorSortOrder = (allItems.size).toString(),
                    editorIsActive = true,
                    actionState = UiAsync.Idle,
                )
            }

            CommunitiesIntent.DeleteSelected -> deleteSelected()
            CommunitiesIntent.Refresh -> refresh()
            CommunitiesIntent.Save -> save()
            is CommunitiesIntent.Select -> select(intent.id)
            is CommunitiesIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is CommunitiesIntent.UpdateCode -> updateState { it.copy(editorCode = intent.value) }
            is CommunitiesIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is CommunitiesIntent.UpdateSlug -> updateState { it.copy(editorSlug = intent.value) }
            is CommunitiesIntent.UpdateSortOrder -> updateState { it.copy(editorSortOrder = intent.value) }
            is CommunitiesIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("communities")
        when (val result = refreshCommunitiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(result.error)) }
            is AppResult.Success -> publish()
        }
    }

    private fun select(id: String) {
        val item = allItems.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorName = item.name,
                editorCode = item.code,
                editorSlug = item.slug,
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
                item.code.lowercase().contains(query)
        }
        updateState {
            it.copy(items = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered))
        }
    }

    private suspend fun save() {
        val existing = allItems.firstOrNull { it.id == currentState().selectedId }
        val sortOrder = currentState().editorSortOrder.toIntOrNull()
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("El orden debe ser numerico.")))
            }

        updateState { it.copy(actionState = UiAsync.Loading) }
        val community = Community(
            id = existing?.id.orEmpty(),
            name = currentState().editorName.trim(),
            code = currentState().editorCode.trim(),
            slug = currentState().editorSlug.trim(),
            isActive = currentState().editorIsActive,
            sortOrder = sortOrder,
            createdAt = existing?.createdAt ?: Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        when (val result = upsertCommunityUseCase(community)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("community_save", mapOf("community_id" to result.value.id))
                updateState { it.copy(selectedId = result.value.id, actionState = UiAsync.Success(Unit)) }
                select(result.value.id)
            }
        }
    }

    private suspend fun deleteSelected() {
        val selectedId = currentState().selectedId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una comunidad para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteCommunityUseCase(selectedId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("community_delete", mapOf("community_id" to selectedId))
                updateState {
                    it.copy(
                        selectedId = null,
                        editorName = "",
                        editorCode = "",
                        editorSlug = "",
                        editorSortOrder = "0",
                        editorIsActive = true,
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }
}
