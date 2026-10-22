package com.oposicontrol.admin.features.tags.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.usecase.tags.DeleteTagUseCase
import com.oposicontrol.admin.domain.usecase.tags.ObserveTagsUseCase
import com.oposicontrol.admin.domain.usecase.tags.RefreshTagsUseCase
import com.oposicontrol.admin.domain.usecase.tags.UpsertTagUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TagsViewModel(
    dispatcherProvider: DispatcherProvider,
    observeTagsUseCase: ObserveTagsUseCase,
    private val refreshTagsUseCase: RefreshTagsUseCase,
    private val upsertTagUseCase: UpsertTagUseCase,
    private val deleteTagUseCase: DeleteTagUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<TagsState, TagsIntent>(
    initialState = TagsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allItems: List<Tag> = emptyList()

    init {
        scope.launch {
            observeTagsUseCase().collectLatest { tags ->
                allItems = tags
                publish()
                if (currentState().selectedId == null && tags.isNotEmpty()) {
                    select(tags.first().id)
                }
            }
        }
        dispatch(TagsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: TagsIntent) {
        when (intent) {
            TagsIntent.CreateNew -> updateState {
                it.copy(
                    selectedId = null,
                    editorName = "",
                    editorSlug = "",
                    editorOrigin = "manual",
                    actionState = UiAsync.Idle,
                )
            }

            TagsIntent.DeleteSelected -> deleteSelected()
            TagsIntent.Refresh -> refresh()
            TagsIntent.Save -> save()
            is TagsIntent.Select -> select(intent.id)
            is TagsIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is TagsIntent.UpdateSlug -> updateState { it.copy(editorSlug = intent.value) }
            is TagsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("tags")
        when (val result = refreshTagsUseCase()) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(result.error)) }
            is AppResult.Success -> publish()
        }
    }

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allItems.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorName = item.name,
                editorSlug = item.slug,
                editorOrigin = item.origin,
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allItems.filter { item ->
            query.isBlank() ||
                item.name.lowercase().contains(query) ||
                item.slug.lowercase().contains(query)
        }
        updateState {
            it.copy(
                items = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered),
                totalCount = allItems.size,
                activeCount = allItems.count { tag -> tag.active },
            )
        }
    }

    private suspend fun save() {
        val existing = allItems.firstOrNull { it.id == currentState().selectedId }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val tag = Tag(
            id = existing?.id.orEmpty(),
            name = currentState().editorName.trim(),
            slug = currentState().editorSlug.trim(),
            origin = existing?.origin ?: "manual",
        )
        when (val result = upsertTagUseCase(tag)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("tag_save", mapOf("tag_id" to result.value.id))
                updateState { it.copy(selectedId = result.value.id, actionState = UiAsync.Success(Unit)) }
                select(result.value.id, resetActionState = false)
            }
        }
    }

    private suspend fun deleteSelected() {
        val selectedId = currentState().selectedId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una etiqueta para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteTagUseCase(selectedId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("tag_delete", mapOf("tag_id" to selectedId))
                updateState {
                    it.copy(
                        selectedId = null,
                        editorName = "",
                        editorSlug = "",
                        editorOrigin = "manual",
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }
}
