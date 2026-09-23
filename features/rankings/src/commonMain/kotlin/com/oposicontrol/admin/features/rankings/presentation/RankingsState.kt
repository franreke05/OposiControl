package com.oposicontrol.admin.features.rankings.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.LeagueLevel
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.UserStanding

/**
 * Qué clasificación se está mirando.
 *
 * `GLOBAL` es el ranking de toda la plataforma; `LIGA` acota a un nivel, que es
 * la comparación que de verdad tiene sentido — dentro de una liga todos
 * promedian sobre la misma ventana de preguntas.
 */
enum class RankingScope { GLOBAL, LIGA }

data class RankingsState(
    /** Catálogo de niveles de liga. Hoy es el único dato realmente poblado. */
    val leagues: UiAsync<List<LeagueLevel>> = UiAsync.Idle,
    val scope: RankingScope = RankingScope.GLOBAL,
    /** Liga seleccionada cuando [scope] es LIGA. */
    val selectedLeagueId: String? = null,
    val page: UiAsync<RankingPage> = UiAsync.Idle,
    val pageRequest: RankingPageRequest = RankingPageRequest.of(),
    val searchQuery: String = "",
    /** Resultados de la búsqueda por nombre; independiente de la clasificación. */
    val searchResults: UiAsync<RankingPage> = UiAsync.Idle,
    val selectedUserId: String? = null,
    val standing: UiAsync<UserStanding> = UiAsync.Idle,
) {
    val selectedLeague: LeagueLevel?
        get() = (leagues as? UiAsync.Success)?.value?.firstOrNull { it.id == selectedLeagueId }

    /**
     * Ligas cuya ventana de cálculo contradice la regla de producto. Se avisa
     * porque sus notas medias dejan de ser comparables con las del resto.
     */
    val divergentLeagues: List<LeagueLevel>
        get() = (leagues as? UiAsync.Success)?.value.orEmpty().filter { it.windowDivergesFromRule }

    /** Página actual ya cargada, o null mientras carga/falla. */
    val loadedPage: RankingPage?
        get() = (page as? UiAsync.Success)?.value
}
