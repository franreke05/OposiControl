package com.oposicontrol.admin.features.rankings.presentation

/**
 * Intenciones de la pantalla de rankings.
 *
 * **Todas son de lectura o navegación.** No existe —ni debe existir— una
 * intención que modifique puntos, posición o liga de un usuario: la
 * clasificación la calcula el producto a partir de su actividad real, y una
 * corrección manual la dejaría sin significado.
 */
sealed interface RankingsIntent {
    /** Carga inicial: catálogo de ligas + primera página de la clasificación. */
    data object Load : RankingsIntent

    /** Vuelve a pedir lo que hay en pantalla, sin cambiar de página. */
    data object Refresh : RankingsIntent

    /** Clasificación global. */
    data object SelectGlobalScope : RankingsIntent

    /** Clasificación dentro de un nivel de liga. */
    data class SelectLeague(val leagueId: String) : RankingsIntent

    data object NextPage : RankingsIntent
    data object PreviousPage : RankingsIntent

    data class SearchQueryChanged(val query: String) : RankingsIntent
    data object Search : RankingsIntent
    data object ClearSearch : RankingsIntent

    /** Abre la ficha competitiva de un usuario. */
    data class SelectUser(val userId: String) : RankingsIntent
}
