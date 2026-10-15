package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.repository.ModerationRepository

/**
 * Informes paginados en el servidor (D5-25, keyset): la primera página al entrar o cambiar de filtro y las siguientes con
 * [ReportsIntent.LoadMore]. Antes se pedía una sola lectura sin `limit` y el servidor cortaba en 200 sin decirlo.
 */
class ReportsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ModerationRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ReportsState, ReportsIntent>(
    initialState = ReportsState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("reports_list")
        dispatch(ReportsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ReportsIntent) {
        when (intent) {
            ReportsIntent.Refresh -> load(asPullToRefresh = false)
            ReportsIntent.PullToRefresh -> load(asPullToRefresh = true)
            ReportsIntent.LoadMore -> loadMore()
            is ReportsIntent.SetMainFilter -> {
                if (intent.filter == currentState().mainFilter) return
                updateState { it.copy(mainFilter = intent.filter, secondaryFilters = ReportSecondaryFilters()) }
                load(asPullToRefresh = false)
            }
            is ReportsIntent.ApplySecondaryFilters -> updateState { it.copy(secondaryFilters = intent.filters) }
            ReportsIntent.ClearSecondaryFilters -> updateState { it.copy(secondaryFilters = ReportSecondaryFilters()) }
        }
    }

    private suspend fun load(asPullToRefresh: Boolean) {
        val filter = currentState().mainFilter
        updateState {
            if (asPullToRefresh) {
                it.copy(refreshing = true, loadMoreError = null)
            } else {
                it.copy(reports = UiAsync.Loading, nextCursor = null, loadMoreError = null)
            }
        }
        val result = repository.listReportsPage(includeResolved = filter == ReportsMainFilter.ALL, cursor = null)
        updateState { state ->
            // Respuesta de un filtro que ya no es el activo: se descarta.
            if (state.mainFilter != filter) return@updateState state.copy(refreshing = false)
            when (result) {
                is AppResult.Failure -> state.copy(reports = UiAsync.Error(result.error), refreshing = false)
                is AppResult.Success -> state.copy(
                    reports = if (result.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.items),
                    nextCursor = result.value.nextCursor,
                    pendingTotal = result.value.pendingTotal,
                    refreshing = false,
                    loadingMore = false,
                )
            }
        }
    }

    private suspend fun loadMore() {
        val state = currentState()
        val cursor = state.nextCursor
        // Guardas antes de suspender: en un dispatcher de un solo hilo dos LoadMore seguidos no piden dos páginas.
        if (cursor == null || state.loadingMore || state.reports !is UiAsync.Success) return
        val filter = state.mainFilter
        updateState { it.copy(loadingMore = true, loadMoreError = null) }
        val result = repository.listReportsPage(includeResolved = filter == ReportsMainFilter.ALL, cursor = cursor)
        updateState { current ->
            if (current.mainFilter != filter || current.nextCursor != cursor) return@updateState current.copy(loadingMore = false)
            when (result) {
                is AppResult.Failure -> current.copy(loadingMore = false, loadMoreError = result.error)
                is AppResult.Success -> {
                    val existing = (current.reports as? UiAsync.Success)?.value.orEmpty()
                    current.copy(
                        reports = UiAsync.Success((existing + result.value.items).distinctBy(ForumReport::id)),
                        nextCursor = result.value.nextCursor,
                        pendingTotal = result.value.pendingTotal ?: current.pendingTotal,
                        loadingMore = false,
                    )
                }
            }
        }
    }
}
