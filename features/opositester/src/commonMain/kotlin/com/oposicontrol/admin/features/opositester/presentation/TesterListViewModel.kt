package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.TesterReport
import com.oposicontrol.admin.domain.repository.TesterRepository

/** "pendiente" es el estado real por defecto de feedback_pregunta (columna
 * `estado default 'pendiente'`) — no una inferencia. */
private const val PENDING_STATUS = "pendiente"

class TesterListViewModel(
    dispatcherProvider: DispatcherProvider,
    private val testerRepository: TesterRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<TesterListState, TesterIntent>(
    initialState = TesterListState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("opositester_list")
        dispatch(TesterIntent.Load)
    }

    override suspend fun handleIntent(intent: TesterIntent) {
        when (intent) {
            TesterIntent.Load -> loadFirstPage()
            TesterIntent.PullToRefresh -> refresh()
            TesterIntent.LoadMore -> loadMore()
            TesterIntent.RetryLoadMore -> {
                updateState { it.copy(loadMoreError = null) }
                loadMore()
            }
            is TesterIntent.SetMainFilter -> {
                if (intent.filter == currentState().mainFilter) return
                updateState { it.copy(mainFilter = intent.filter, knownTypes = emptyList()) }
                loadFirstPage()
            }
            is TesterIntent.SetTypeFilter -> {
                if (intent.type == currentState().typeFilter) return
                updateState { it.copy(typeFilter = intent.type) }
                loadFirstPage()
            }
        }
    }

    /**
     * [preserveContent] mantiene la lista ya visible mientras se recarga. Solo
     * lo usa el pull-to-refresh: ahí el indicador de `PullToRefreshBox` ya
     * comunica la carga, así que poner además `reports = Loading` borraba el
     * contenido y mostraba el esqueleto *debajo* del propio spinner (doble
     * loader). Mismo criterio que ReportsViewModel.load(asPullToRefresh).
     */
    private suspend fun loadFirstPage(preserveContent: Boolean = false) {
        updateState {
            it.copy(
                reports = if (preserveContent) it.reports else UiAsync.Loading,
                nextCursor = null,
                loadMoreError = null,
            )
        }
        val state = currentState()
        val status = if (state.mainFilter == TesterMainFilter.PENDING) PENDING_STATUS else null
        when (val result = testerRepository.listReports(status = status, cursor = null, type = state.typeFilter?.value)) {
            is AppResult.Failure -> updateState { it.copy(reports = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    reports = if (result.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value.items),
                    nextCursor = result.value.nextCursor,
                    knownTypes = mergeTypes(it.knownTypes, result.value.items),
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
        // Guardas antes de cualquier punto de suspensión: mismo razonamiento que
        // ForumViewModel.loadMore — suficiente en un dispatcher confinado a un
        // solo hilo para que dos LoadMore casi simultáneos no dupliquen página.
        // Tras un fallo solo reintenta [TesterIntent.RetryLoadMore]: el desplazamiento no relanza la pagina en bucle.
        if (cursor == null || state.loadingMore || state.loadMoreError != null || state.reports !is UiAsync.Success) return
        updateState { it.copy(loadingMore = true) }
        val status = if (state.mainFilter == TesterMainFilter.PENDING) PENDING_STATUS else null
        when (val result = testerRepository.listReports(status = status, cursor = cursor, type = state.typeFilter?.value)) {
            is AppResult.Failure -> updateState { it.copy(loadingMore = false, loadMoreError = result.error) }
            is AppResult.Success -> updateState {
                val existing = (it.reports as? UiAsync.Success)?.value.orEmpty()
                val merged = (existing + result.value.items).distinctBy(TesterReport::id)
                it.copy(
                    reports = UiAsync.Success(merged),
                    nextCursor = result.value.nextCursor,
                    loadingMore = false,
                    knownTypes = mergeTypes(it.knownTypes, result.value.items),
                )
            }
        }
    }

    private fun mergeTypes(known: List<TesterTypeOption>, items: List<TesterReport>): List<TesterTypeOption> {
        val discovered = items.mapNotNull { report ->
            val value = report.type.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TesterTypeOption(value, humanizeTesterType(value))
        }
        if (discovered.isEmpty()) return known
        return (known + discovered).distinctBy { it.value }
    }
}
