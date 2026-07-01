package com.oposicontrol.admin.features.newstypes.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.StatusChip

@Composable
fun NewsTypesScreen(
    viewModel: NewsTypesViewModel,
) {
    val state by viewModel.state.collectAsState()
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(NewsTypesIntent.UpdateSearch(it)) },
                    placeholder = "Buscar categoria editorial",
                )
                GlassActionButton(
                    text = "Nuevo tipo",
                    modifier = Modifier.width(128.dp),
                    onClick = { viewModel.dispatch(NewsTypesIntent.CreateNew) },
                )
                AsyncStatePane(state.items, emptyMessage = "No hay tipos de noticia.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Slug", 1f),
                            AdminTableColumn("Estado", 0.8f),
                        ),
                        rows = items.map { item ->
                            AdminTableRow(
                                key = item.id,
                                cells = listOf(item.name, item.slug, item.status),
                                selected = item.id == state.selectedId,
                            )
                        },
                        onRowClick = { viewModel.dispatch(NewsTypesIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nuevo tipo de noticia" else "Detalle de categoria",
                description = "Las noticias heredaran esta categoria para filtros y segmentacion editorial.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(if (state.editorIsActive) "active" else "inactive")
                    OutlinedTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(NewsTypesIntent.UpdateName(it)) },
                        label = { Text("Nombre") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorSlug,
                        onValueChange = { viewModel.dispatch(NewsTypesIntent.UpdateSlug(it)) },
                        label = { Text("Slug") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Visible para creacion de noticias")
                        Switch(
                            checked = state.editorIsActive,
                            onCheckedChange = { viewModel.dispatch(NewsTypesIntent.ToggleActive(it)) },
                        )
                    }
                    Button(
                        onClick = { viewModel.dispatch(NewsTypesIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Guardar categoria")
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando...")
                        is UiAsync.Success -> Text("Categoria editorial preparada.")
                        else -> Unit
                    }
                }
            }
        },
    )
}
