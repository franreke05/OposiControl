package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumThread
import com.oposicontrol.admin.domain.repository.ForumRepository
import com.oposicontrol.admin.domain.repository.ModerationRepository
import kotlinx.coroutines.launch

class ForumViewModel(
    dispatcherProvider: DispatcherProvider,
    private val forumRepository: ForumRepository,
    private val moderationRepository: ModerationRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ForumState, ForumIntent>(
    initialState = ForumState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("forum")
        dispatch(ForumIntent.Load)
        // Misma fuente semántica que Home y el filtro "Pendientes" de Informes
        // (ModerationRepository.listPendingReports): nunca un contador propio,
        // para que los tres sitios concuerden siempre. No bloqueante: si falla,
        // la banner simplemente no se muestra (pendingReportsCount se queda null).
        scope.launch {
            when (val result = moderationRepository.listPendingReports()) {
                is AppResult.Success -> updateState { it.copy(pendingReportsCount = result.value.size) }
                is AppResult.Failure -> Unit
            }
        }
    }

    override suspend fun handleIntent(intent: ForumIntent) {
        when (intent) {
            ForumIntent.Load -> loadFirstPage()
            ForumIntent.PullToRefresh -> refresh()
            ForumIntent.LoadMore -> loadMore()
            is ForumIntent.SetMainFilter -> {
                if (intent.filter == currentState().mainFilter) return
                updateState { it.copy(mainFilter = intent.filter, knownCategories = emptyList()) }
                loadFirstPage()
            }
            is ForumIntent.SetCategoryFilter -> {
                if (intent.category == currentState().categoryFilter) return
                updateState { it.copy(categoryFilter = intent.category) }
                loadFirstPage()
            }
        }
    }

    /**
     * [preserveContent] mantiene la lista ya visible mientras se recarga. Solo
     * lo usa el pull-to-refresh: ahí el indicador de `PullToRefreshBox` ya
     * comunica la carga, así que poner además `threads = Loading` borraba el
     * contenido y mostraba el esqueleto *debajo* del propio spinner (doble
     * loader). Mismo criterio que ReportsViewModel.load(asPullToRefresh).
     */
    private suspend fun loadFirstPage(preserveContent: Boolean = false) {
        updateState {
            it.copy(
                threads = if (preserveContent) it.threads else UiAsync.Loading,
                nextCursor = null,
            )
        }
        val state = currentState()
        when (val result = forumRepository.listThreads(cursor = null, reportedOnly = state.mainFilter == ForumMainFilter.REPORTED, categoryId = state.categoryFilter?.id)) {
            is AppResult.Failure -> updateState { it.copy(threads = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    threads = if (result.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.items),
                    nextCursor = result.value.nextCursor,
                    knownCategories = mergeCategories(it.knownCategories, result.value.items),
                )
            }
        }
    }

    private suspend fun refresh() {
        updateState { it.copy(refreshing = true) }
        loadFirstPage(preserveContent = true)
        updateState { it.copy(refreshing = false) }
    }

    private suspend fun loadMore() {
        val state = currentState()
        val cursor = state.nextCursor
        // Guardas antes de cualquier punto de suspensión: en un dispatcher
        // confinado a un solo hilo (Main real, o StandardTestDispatcher en
        // tests) esto basta para que dos LoadMore casi simultáneos nunca lancen
        // dos páginas a la vez, sin necesitar un Mutex adicional.
        if (cursor == null || state.loadingMore || state.threads !is UiAsync.Success) return
        updateState { it.copy(loadingMore = true) }
        when (val result = forumRepository.listThreads(cursor = cursor, reportedOnly = state.mainFilter == ForumMainFilter.REPORTED, categoryId = state.categoryFilter?.id)) {
            is AppResult.Failure -> updateState { it.copy(loadingMore = false) }
            is AppResult.Success -> updateState {
                val existing = (it.threads as? UiAsync.Success)?.value.orEmpty()
                val merged = (existing + result.value.items).distinctBy(ForumThread::id)
                it.copy(
                    threads = UiAsync.Success(merged),
                    nextCursor = result.value.nextCursor,
                    loadingMore = false,
                    knownCategories = mergeCategories(it.knownCategories, result.value.items),
                )
            }
        }
    }

    private fun mergeCategories(known: List<ForumCategoryOption>, items: List<ForumThread>): List<ForumCategoryOption> {
        val discovered = items.mapNotNull { thread ->
            val id = thread.categoryId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ForumCategoryOption(id, thread.categoryName.ifBlank { return@mapNotNull null })
        }
        if (discovered.isEmpty()) return known
        return (known + discovered).distinctBy { it.id }
    }
}
