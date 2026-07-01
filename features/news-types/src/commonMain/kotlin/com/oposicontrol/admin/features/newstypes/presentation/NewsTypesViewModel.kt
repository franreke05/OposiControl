package com.oposicontrol.admin.features.newstypes.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository

class NewsTypesViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<NewsTypesState, NewsTypesIntent>(
    initialState = NewsTypesState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allItems: List<NewsTypeItem> = emptyList()

    init {
        analyticsTracker.trackScreen("news_types")
        dispatch(NewsTypesIntent.Refresh)
    }

    override suspend fun handleIntent(intent: NewsTypesIntent) {
        when (intent) {
            NewsTypesIntent.CreateNew -> updateState {
                it.copy(selectedId = null, editorName = "", editorSlug = "", editorIsActive = true, actionState = UiAsync.Idle)
            }

            NewsTypesIntent.Refresh -> refresh()
            NewsTypesIntent.Save -> save()
            is NewsTypesIntent.Select -> select(intent.id)
            is NewsTypesIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is NewsTypesIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is NewsTypesIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is NewsTypesIntent.UpdateSlug -> updateState { it.copy(editorSlug = intent.value) }
        }
    }

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allItems.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedId = item.id,
                editorName = item.name,
                editorSlug = item.slug,
                editorIsActive = item.status == "active",
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allItems.filter { item ->
            query.isBlank() || item.name.lowercase().contains(query) || item.slug.lowercase().contains(query)
        }
        updateState { it.copy(items = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        updateState { it.copy(items = UiAsync.Loading) }
        when (val result = repository.getNewsTypes()) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                allItems = result.value.map { it.toItem() }
                publish()
                if (currentState().selectedId == null) allItems.firstOrNull()?.let { select(it.id) }
            }
        }
    }

    private suspend fun save() {
        val name = currentState().editorName.trim()
        if (name.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("El tipo de noticia necesita nombre."))) }
            return
        }
        val slug = currentState().editorSlug.trim().ifBlank { name.lowercase().replace(" ", "-") }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val item = NewsType(
            id = currentState().selectedId.orEmpty(),
            name = name,
            slug = slug,
            isActive = currentState().editorIsActive,
        )
        when (val result = repository.upsertNewsType(item)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value.toItem()
                allItems = allItems.filterNot { it.id == saved.id } + saved
                analyticsTracker.trackAction("news_type_save", mapOf("type_id" to saved.id))
                updateState { it.copy(selectedId = saved.id, actionState = UiAsync.Success(Unit)) }
                publish()
                select(saved.id, resetActionState = false)
            }
        }
    }

    private fun NewsType.toItem() = NewsTypeItem(
        id = id,
        name = name,
        slug = slug,
        status = if (isActive) "active" else "inactive",
    )
}
