package com.oposicontrol.admin.features.communities.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip

@Composable
fun CommunitiesScreen(
    viewModel: CommunitiesViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val actionInProgress = state.actionState is UiAsync.Loading
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(CommunitiesIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por nombre o codigo",
                )
                GlassActionButton(
                    text = "Nueva comunidad",
                    modifier = Modifier.width(152.dp),
                    onClick = { viewModel.dispatch(CommunitiesIntent.CreateNew) },
                )
                AsyncStatePane(state.items, emptyMessage = "No hay comunidades. Aplica la migracion comunidad_autonoma.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Codigo", 0.5f),
                            AdminTableColumn("Estado", 0.7f),
                            AdminTableColumn("Orden", 0.5f),
                        ),
                        rows = items.map { item ->
                            AdminTableRow(
                                key = item.id,
                                cells = listOf(
                                    item.name,
                                    item.code.ifBlank { "-" },
                                    if (item.isActive) "active" else "inactive",
                                    item.sortOrder.toString(),
                                ),
                                selected = item.id == state.selectedId,
                                supporting = item.slug.ifBlank { "Sin slug" },
                            )
                        },
                        onRowClick = { viewModel.dispatch(CommunitiesIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nueva comunidad" else "Detalle de comunidad",
                description = "Catalogo territorial para acotar examenes por comunidad autonoma.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(if (state.editorIsActive) "active" else "inactive")
                    Text(
                        text = "Nombre",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(CommunitiesIntent.UpdateName(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Comunidad de Madrid",
                    )
                    Text(
                        text = "Codigo",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorCode,
                        onValueChange = { viewModel.dispatch(CommunitiesIntent.UpdateCode(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "MD",
                    )
                    Text(
                        text = "Slug (opcional, se genera del nombre)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorSlug,
                        onValueChange = { viewModel.dispatch(CommunitiesIntent.UpdateSlug(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "madrid",
                    )
                    Text(
                        text = "Orden",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorSortOrder,
                        onValueChange = { viewModel.dispatch(CommunitiesIntent.UpdateSortOrder(it)) },
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
                            onCheckedChange = { viewModel.dispatch(CommunitiesIntent.ToggleActive(it)) },
                        )
                    }
                    GlassActionButton(
                        text = "Guardar comunidad",
                        enabled = !actionInProgress,
                        onClick = { viewModel.dispatch(CommunitiesIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selectedId != null) {
                        GlassActionButton(
                            text = "Eliminar comunidad",
                            enabled = !actionInProgress,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando...")
                        is UiAsync.Success -> Text("Comunidad sincronizada.")
                        else -> Unit
                    }
                }
            }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar comunidad",
            message = "Se eliminará la comunidad seleccionada. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                viewModel.dispatch(CommunitiesIntent.DeleteSelected)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
