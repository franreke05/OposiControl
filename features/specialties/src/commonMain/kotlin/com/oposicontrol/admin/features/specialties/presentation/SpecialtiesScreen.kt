package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.StatusChip

@Composable
fun SpecialtiesScreen(
    viewModel: SpecialtiesViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(SpecialtiesIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por nombre o descripcion",
                )
                GlassActionButton(
                    text = "Nueva especialidad",
                    modifier = Modifier.width(168.dp),
                    onClick = { viewModel.dispatch(SpecialtiesIntent.CreateNew) },
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
                    GlassToggleRow(
                        title = "Activa para el catálogo",
                        description = "Solo las activas quedan visibles para contenido nuevo.",
                        checked = state.editorIsActive,
                        onCheckedChange = { viewModel.dispatch(SpecialtiesIntent.ToggleActive(it)) },
                    )
                    GlassActionButton(
                        text = "Guardar especialidad",
                        onClick = { viewModel.dispatch(SpecialtiesIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selectedId != null) {
                        GlassActionButton(
                            text = "Eliminar especialidad",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormFeedback(state.actionState, successText = "Especialidad sincronizada.")
                }
            }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar especialidad",
            message = "Se eliminará la especialidad seleccionada. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                viewModel.dispatch(SpecialtiesIntent.DeleteSelected)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
