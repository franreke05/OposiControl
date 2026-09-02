package com.oposicontrol.admin.features.rankings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.domain.model.LeagueLevel
import com.oposicontrol.admin.domain.model.RankingEntry
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingSection

/**
 * Rankings y ligas.
 *
 * **Pantalla de consulta.** No hay ni un botón que modifique una clasificación:
 * la calcula el producto a partir de la actividad real del opositor, y una
 * corrección manual la dejaría sin significado. Lo que sí hace la pantalla es
 * explicar el dato — sobre qué ventana de preguntas está calculada la nota, si
 * la evolución es reciente o lleva tiempo congelada, y qué es exactamente el
 * percentil.
 *
 * Estado real de los datos: el catálogo de ligas está poblado; la clasificación
 * todavía no. La pantalla lo dice en vez de enseñar una tabla vacía sin
 * explicación, y distingue "nadie compite aún" de "no se pudo leer".
 */
@Composable
fun RankingsScreen(viewModel: RankingsViewModel) {
    val state by viewModel.state.collectAsState()

    CrudSplitLayout(
        listPane = {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ScopeBar(state, viewModel)
                LeagueCatalogueSection(state, viewModel)
                UserSearchSection(state, viewModel)
            }
        },
        detailPane = {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RankingTableSection(state, viewModel)
                UserStandingPanel(state)
            }
        },
    )
}

@Composable
private fun ScopeBar(state: RankingsState, viewModel: RankingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassActionButton(
            text = "Global",
            tint = if (state.scope == RankingScope.GLOBAL) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            onClick = { viewModel.dispatch(RankingsIntent.SelectGlobalScope) },
        )
        GlassActionButton(
            text = "Actualizar",
            onClick = { viewModel.dispatch(RankingsIntent.Refresh) },
        )
    }
}

/**
 * Catálogo de niveles de liga: el dato real de esta pantalla.
 *
 * Cada fila es además el selector de la clasificación de esa liga — comparar
 * dentro de una liga es lo que tiene sentido, porque todos sus miembros
 * promedian sobre la misma ventana de preguntas.
 */
@Composable
private fun LeagueCatalogueSection(state: RankingsState, viewModel: RankingsViewModel) {
    FormSection(
        title = "Ligas",
        description = "Umbral de entrada y ventana de preguntas sobre la que se promedia cada liga.",
    ) {
        AsyncStatePane(
            state.leagues,
            emptyMessage = "No hay niveles de liga configurados.",
        ) { leagues ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Liga", 1.0f),
                        AdminTableColumn("Mínimo", 0.6f),
                        AdminTableColumn("Ventana", 0.7f),
                        AdminTableColumn("Estado", 0.6f),
                    ),
                    rows = leagues.map { league ->
                        AdminTableRow(
                            key = league.id,
                            cells = listOf(
                                league.name,
                                "${league.minimumQuestions} preg.",
                                "${league.calculationWindow} preg.",
                                if (league.active) "activa" else "inactiva",
                            ),
                            selected = league.id == state.selectedLeagueId,
                            supporting = league.code,
                        )
                    },
                    onRowClick = { leagueId -> viewModel.dispatch(RankingsIntent.SelectLeague(leagueId)) },
                )
                // Una ventana que no cuadra con la regla de producto rompe la
                // comparabilidad de las notas medias: se avisa, no se disimula.
                state.divergentLeagues.forEach { league ->
                    leagueWindowWarning(league)?.let { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserSearchSection(state: RankingsState, viewModel: RankingsViewModel) {
    FormSection(
        title = "Buscar opositor",
        description = "Por nombre visible o username. Abre su ficha competitiva.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterBar(
                searchValue = state.searchQuery,
                onSearchChange = { viewModel.dispatch(RankingsIntent.SearchQueryChanged(it)) },
                placeholder = "Nombre o username",
                filters = {
                    GlassActionButton(
                        text = "Buscar",
                        enabled = state.searchQuery.isNotBlank(),
                        onClick = { viewModel.dispatch(RankingsIntent.Search) },
                    )
                    GlassActionButton(
                        text = "Limpiar",
                        onClick = { viewModel.dispatch(RankingsIntent.ClearSearch) },
                    )
                },
            )
            AsyncStatePane(
                state.searchResults,
                emptyMessage = "Ningún opositor coincide con la búsqueda.",
            ) { page ->
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Opositor", 1.2f),
                        AdminTableColumn("Liga", 0.8f),
                        AdminTableColumn("Nota", 0.6f),
                        AdminTableColumn("Global", 0.6f),
                    ),
                    rows = page.entries.map { entry ->
                        AdminTableRow(
                            key = entry.userId,
                            cells = listOf(
                                rowLabel(entry.displayName, entry.username, entry.userId),
                                entry.leagueName ?: NO_DATA_TEXT,
                                formatScoreOrNoData(entry.currentAverage),
                                formatPosition(entry.globalRank),
                            ),
                            selected = entry.userId == state.selectedUserId,
                            supporting = if (entry.hasNoCompetitiveState) {
                                "Todavía no compite: sin estado competitivo calculado."
                            } else {
                                entry.username
                            },
                        )
                    },
                    onRowClick = { userId -> viewModel.dispatch(RankingsIntent.SelectUser(userId)) },
                )
            }
        }
    }
}

@Composable
private fun RankingTableSection(state: RankingsState, viewModel: RankingsViewModel) {
    val league = state.selectedLeague
    FormSection(
        title = if (state.scope == RankingScope.LIGA && league != null) {
            "Clasificación · ${league.name}"
        } else {
            "Clasificación global"
        },
        description = rankingDescription(state.scope, league),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncStatePane(
                state.page,
                emptyMessage = "Todavía no hay nadie clasificado: el estado competitivo se crea " +
                    "al alcanzar el mínimo de preguntas de la primera liga.",
            ) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RankingTable(page, state.selectedUserId, viewModel)
                    PaginationBar(page, viewModel)
                    if (page.isUnavailable(RankingSection.NOMBRES)) {
                        Text(
                            text = "$NOT_AVAILABLE_TEXT — no se pudieron resolver los nombres; " +
                                "las posiciones sí son correctas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankingTable(page: RankingPage, selectedUserId: String?, viewModel: RankingsViewModel) {
    AdminTable(
        columns = listOf(
            AdminTableColumn("#", 0.4f),
            AdminTableColumn("Opositor", 1.2f),
            AdminTableColumn("Liga", 0.8f),
            AdminTableColumn("Nota", 0.6f),
            AdminTableColumn("Evolución", 0.7f),
            AdminTableColumn("Preg.", 0.5f),
            AdminTableColumn("Liga #", 0.6f),
            AdminTableColumn("Global #", 0.6f),
        ),
        rows = page.entries.map { entry ->
            AdminTableRow(
                key = entry.userId,
                cells = listOf(
                    formatPosition(entry.globalRank),
                    rowLabel(entry.displayName, entry.username, entry.userId),
                    entry.leagueName ?: NO_DATA_TEXT,
                    formatScoreOrNoData(entry.currentAverage),
                    formatSignedScoreOrNoData(entry.evolution),
                    formatCount(entry.rankingEligibleQuestions),
                    formatPosition(entry.leagueRank),
                    formatPosition(entry.globalRank),
                ),
                selected = entry.userId == selectedUserId,
                supporting = entry.supportingLine(),
            )
        },
        onRowClick = { userId -> viewModel.dispatch(RankingsIntent.SelectUser(userId)) },
    )
}

/**
 * Línea de contexto de cada fila: sin saber cuántas preguntas lleva el usuario
 * desde la última evolución no se puede saber si la cifra de evolución es
 * reciente o lleva tiempo congelada esperando al siguiente bloque.
 */
private fun RankingEntry.supportingLine(): String = buildList {
    add(formatWindow(calculationWindow))
    questionsSinceLastEvolution?.let { add("$it preg. desde la última evolución") }
    updatedAt?.let { add("actualizado $it") }
}.joinToString(" · ")

@Composable
private fun PaginationBar(page: RankingPage, viewModel: RankingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassActionButton(
            text = "Anterior",
            enabled = page.hasPreviousPage,
            onClick = { viewModel.dispatch(RankingsIntent.PreviousPage) },
        )
        GlassActionButton(
            // Sin total fiable no se ofrece "siguiente": invitaría a pedir una
            // página que podría no existir y a leer el vacío como el final.
            text = "Siguiente",
            enabled = page.hasNextPage,
            onClick = { viewModel.dispatch(RankingsIntent.NextPage) },
        )
        Text(
            text = "Página ${page.pageNumber} · ${page.entries.size} de ${formatTotal(page.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (page.isUnavailable(RankingSection.TOTAL)) {
            StatusChip("incompleta")
        }
    }
}

private fun rankingDescription(scope: RankingScope, league: LeagueLevel?): String = when {
    scope == RankingScope.LIGA && league != null ->
        "Nota media sobre las últimas ${league.calculationWindow} preguntas válidas. " +
            "Entrada a partir de ${league.minimumQuestions} preguntas. Solo lectura."
    else ->
        "Posición global entre todos los opositores. Cada liga promedia sobre su propia " +
            "ventana de preguntas, así que la comparación fina se hace dentro de una liga. Solo lectura."
}
