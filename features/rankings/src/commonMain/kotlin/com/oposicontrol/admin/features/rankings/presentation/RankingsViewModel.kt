package com.oposicontrol.admin.features.rankings.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.RankingPageState
import com.oposicontrol.admin.domain.repository.RankingsRepository

/**
 * Rankings y ligas: pantalla de consulta.
 *
 * Dos decisiones que gobiernan todo lo de abajo:
 *
 * 1. **No hay intención de escritura.** La clasificación la calcula el producto;
 *    el panel la lee. Ver [RankingsIntent].
 * 2. **Una lista vacía no siempre significa lo mismo.** Con el conteo confirmado
 *    es `Empty` ("no compite nadie todavía"); sin conteo fiable es `Error`, para
 *    que la pantalla no afirme un vacío que no ha podido comprobar. Hoy esto no
 *    es teórico: `estado_competitivo_usuario` está aprovisionada y vacía.
 */
class RankingsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: RankingsRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<RankingsState, RankingsIntent>(
    initialState = RankingsState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(RankingsIntent.Load)
    }

    override suspend fun handleIntent(intent: RankingsIntent) {
        when (intent) {
            RankingsIntent.Load -> {
                analyticsTracker.trackScreen("rankings")
                loadLeagues()
                loadPage(RankingPageRequest.of())
            }

            RankingsIntent.Refresh -> {
                loadLeagues()
                loadPage(currentState().pageRequest)
            }

            RankingsIntent.SelectGlobalScope -> {
                updateState { it.copy(scope = RankingScope.GLOBAL, selectedLeagueId = null) }
                // Volver a la primera página: el offset de una liga no significa
                // lo mismo en la clasificación global.
                loadPage(RankingPageRequest.of())
            }

            is RankingsIntent.SelectLeague -> {
                updateState { it.copy(scope = RankingScope.LIGA, selectedLeagueId = intent.leagueId) }
                loadPage(RankingPageRequest.of())
            }

            RankingsIntent.NextPage -> {
                // Sólo se avanza si consta que hay filas detrás. Sin total fiable
                // no se ofrece "siguiente" y aquí tampoco se obedece.
                val page = currentState().loadedPage
                if (page?.hasNextPage == true) loadPage(currentState().pageRequest.next())
            }

            RankingsIntent.PreviousPage -> {
                if (currentState().pageRequest.offset > 0) loadPage(currentState().pageRequest.previous())
            }

            is RankingsIntent.SearchQueryChanged -> {
                updateState { it.copy(searchQuery = intent.query) }
            }

            RankingsIntent.Search -> search()

            RankingsIntent.ClearSearch -> {
                updateState { it.copy(searchQuery = "", searchResults = UiAsync.Idle) }
            }

            is RankingsIntent.SelectUser -> loadStanding(intent.userId)
        }
    }

    private suspend fun loadLeagues() {
        updateState { it.copy(leagues = UiAsync.Loading) }
        val result = repository.getLeagueLevels().toListUiAsync()
        updateState { it.copy(leagues = result) }
    }

    private suspend fun loadPage(request: RankingPageRequest) {
        updateState { it.copy(pageRequest = request, page = UiAsync.Loading) }
        val state = currentState()
        val result = when (state.scope) {
            RankingScope.GLOBAL -> repository.getGlobalRanking(request)
            RankingScope.LIGA -> {
                val leagueId = state.selectedLeagueId
                if (leagueId == null) {
                    repository.getGlobalRanking(request)
                } else {
                    repository.getLeagueRanking(leagueId, request)
                }
            }
        }
        updateState { it.copy(page = result.toPageUiAsync()) }
    }

    private suspend fun search() {
        val query = currentState().searchQuery.trim()
        if (query.isBlank()) {
            updateState { it.copy(searchResults = UiAsync.Idle) }
            return
        }
        updateState { it.copy(searchResults = UiAsync.Loading) }
        // La búsqueda usa su propia primera página: el offset de la clasificación
        // no tiene ningún sentido aplicado a otro conjunto de filas.
        val result = repository.searchUsers(query, RankingPageRequest.of())
        updateState { it.copy(searchResults = result.toPageUiAsync()) }
    }

    private suspend fun loadStanding(userId: String) {
        updateState { it.copy(selectedUserId = userId, standing = UiAsync.Loading) }
        val result = when (val standing = repository.getUserStanding(userId)) {
            is AppResult.Failure -> UiAsync.Error(standing.error)
            is AppResult.Success -> UiAsync.Success(standing.value)
        }
        updateState { it.copy(standing = result) }
    }
}

/**
 * Una lista vacía es `Empty`, no `Success(emptyList())`: el catálogo puede estar
 * aprovisionado y sin poblar, y la pantalla debe decirlo en vez de enseñar una
 * tabla vacía sin explicación.
 */
private fun <T> AppResult<List<T>>.toListUiAsync(): UiAsync<List<T>> = when (this) {
    is AppResult.Failure -> UiAsync.Error(error)
    is AppResult.Success -> if (value.isEmpty()) UiAsync.Empty else UiAsync.Success(value)
}

/**
 * Traducción honesta de una página.
 *
 * `UNAVAILABLE` (cero filas y conteo ilegible) se convierte en `Error` y no en
 * `Empty`: decir "no compite nadie" sin haber podido contar sería inventar el
 * dato que precisamente falta. `EMPTY` sí es un vacío comprobado.
 */
internal fun AppResult<RankingPage>.toPageUiAsync(): UiAsync<RankingPage> = when (this) {
    is AppResult.Failure -> UiAsync.Error(error)
    is AppResult.Success -> when (value.state) {
        RankingPageState.CONTENT -> UiAsync.Success(value)
        RankingPageState.EMPTY -> UiAsync.Empty
        RankingPageState.UNAVAILABLE -> UiAsync.Error(
            AppError.Network(
                "No se pudo confirmar el número de clasificados; la lista puede estar incompleta.",
            ),
        )
    }
}
