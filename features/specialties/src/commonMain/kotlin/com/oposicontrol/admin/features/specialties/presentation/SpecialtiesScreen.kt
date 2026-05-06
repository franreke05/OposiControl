package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip

@Composable
fun SpecialtiesScreen(
    viewModel: SpecialtiesViewModel,
) {
    val state by viewModel.state.collectAsState()
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(SpecialtiesIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por nombre o descripcion",
                )
                AsyncStatePane(state.items, emptyMessage = "No hay especialidades para este filtro.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Estado", 0.7f),
                            AdminTableColumn("Orden", 0.5f),
                            AdminTableColumn("Examenes", 0.6f),
                        ),
                        rows = items.map { item ->
                            AdminTableRow(
                                key = item.id,
                                cells = listOf(
                                    item.name,
                                    if (item.isActive) "active" else "inactive",
                                    item.sortOrder.toString(),
                                    item.linkedExams.toString(),
                                ),
                                selected = item.id == state.selectedId,
                                supporting = item.description.ifBlank { "Sin descripcion" },
                            )
                        },
                        onRowClick = { viewModel.dispatch(SpecialtiesIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nueva especialidad" else "Detalle de especialidad",
                description = "El admin solo gestiona estructura de contenido y examenes asociados.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(if (state.editorIsActive) "active" else "inactive")
                    Text(
                        text = "Nombre",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateName(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Auxiliar Administrativo",
                    )
                    Text(
                        text = "Descripcion",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorDescription,
                        onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateDescription(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Resumen operativo de la especialidad",
                        singleLine = false,
                    )
                    Text(
                        text = "Orden",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorSortOrder,
                        onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateSortOrder(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "0",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Activa para el catalogo")
                            Text(
                                "Solo las activas deberian quedar visibles para contenido nuevo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.editorIsActive,
                            onCheckedChange = { viewModel.dispatch(SpecialtiesIntent.ToggleActive(it)) },
                        )
                    }
                    GlassActionButton(
                        text = "Guardar especialidad",
                        onClick = { viewModel.dispatch(SpecialtiesIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selectedId != null) {
                        GlassActionButton(
                            text = "Eliminar especialidad",
                            onClick = { viewModel.dispatch(SpecialtiesIntent.DeleteSelected) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando...")
                        is UiAsync.Success -> Text("Especialidad sincronizada.")
                        else -> Unit
                    }
                }
            }
        },
    )
}
