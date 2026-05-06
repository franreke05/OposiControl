package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.theme.AdminTheme

@Composable
fun NewsScreen(
    viewModel: NewsViewModel,
) {
    val state by viewModel.state.collectAsState()
    NewsContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
fun NewsContent(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    NewsListPane(state = state, onIntent = onIntent)
}

@Composable
private fun NewsListPane(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterBar(
            searchValue = state.searchQuery,
            onSearchChange = { onIntent(NewsIntent.UpdateSearch(it)) },
            placeholder = "Buscar noticias",
            filters = {
                NewsSmallFilterField(
                    value = state.statusFilter,
                    label = "Estado",
                    onValueChange = { onIntent(NewsIntent.UpdateStatusFilter(it)) },
                )
                NewsSmallFilterField(
                    value = state.specialtyFilter,
                    label = "Especialidad",
                    onValueChange = { onIntent(NewsIntent.UpdateSpecialtyFilter(it)) },
                )
                NewsSmallFilterField(
                    value = state.typeFilter,
                    label = "Tipo",
                    onValueChange = { onIntent(NewsIntent.UpdateTypeFilter(it)) },
                )
            },
        )
        AsyncStatePane(state.news, emptyMessage = "No hay noticias para este filtro.") { items ->
            AdminTable(
                columns = listOf(
                    AdminTableColumn("Titulo", 1.4f),
                    AdminTableColumn("Especialidad", 0.9f),
                    AdminTableColumn("Tipo", 0.9f),
                    AdminTableColumn("Estado", 0.8f),
                ),
                rows = items.map { item ->
                    AdminTableRow(
                        key = item.id,
                        cells = listOf(item.title, item.specialty, item.type, item.status),
                        selected = item.id == state.selectedId,
                        supporting = "Publicar desde ${item.publishFrom}",
                    )
                },
                onRowClick = { onIntent(NewsIntent.Select(it)) },
            )
        }
    }
}

@Composable
private fun NewsSmallFilterField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        singleLine = true,
    )
}

@Preview
@Composable
private fun NewsScreenPreview() {
    AdminTheme {
        NewsContent(
            state = NewsState(
                news = UiAsync.Success(
                    listOf(
                        NewsItem(
                            id = "news-1",
                            title = "Convocatoria TCAE",
                            specialty = "TCAE",
                            type = "Convocatoria",
                            backgroundType = "A",
                            backgroundPath = "news/backgrounds/cover.png",
                            status = "scheduled",
                            publishFrom = "2026-04-20 08:00",
                            text = "Texto",
                        ),
                    ),
                ),
                selectedId = "news-1",
                editorTitle = "Convocatoria TCAE",
                editorBackgroundType = "A",
                editorBackgroundPath = "news/backgrounds/cover.png",
                editorSpecialty = "TCAE",
                editorType = "Convocatoria",
                editorText = "Texto largo de la noticia.",
                editorStatus = "scheduled",
                editorPublishFrom = "2026-04-20 08:00",
                editorLayers = listOf(
                    NewsLayerDraft(
                        id = "layer-1",
                        name = "Titulo",
                        kind = "titulo",
                        content = "Convocatoria TCAE",
                        x = 24,
                        y = 30,
                        width = 280,
                        height = 64,
                        opacity = 100,
                        visible = true,
                        sortOrder = 0,
                    ),
                ),
            ),
            onIntent = {},
        )
    }
}
