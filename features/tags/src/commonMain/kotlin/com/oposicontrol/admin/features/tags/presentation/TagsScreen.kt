package com.oposicontrol.admin.features.tags.presentation

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
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun TagsScreen(
    viewModel: TagsViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(TagsIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por nombre o slug",
                )
                GlassActionButton(
                    text = "Nueva etiqueta",
                    modifier = Modifier.width(144.dp),
                    onClick = { viewModel.dispatch(TagsIntent.CreateNew) },
                )
                AsyncStatePane(state.items, emptyMessage = "No hay etiquetas para este filtro.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Slug", 1f),
                            AdminTableColumn("Origen", 0.6f),
                        ),
                        rows = items.map { item ->
                            AdminTableRow(
                                key = item.id,
                                cells = listOf(
                                    item.name,
                                    item.slug.ifBlank { "-" },
                                    item.origin,
                                ),
                                selected = item.id == state.selectedId,
                                supporting = "Etiqueta ${item.origin}",
                            )
                        },
                        onRowClick = { viewModel.dispatch(TagsIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nueva etiqueta" else "Detalle de etiqueta",
                description = "Las etiquetas alimentan la busqueda global y la clasificacion de contenido.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldLabel("Nombre")
                    GlassTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(TagsIntent.UpdateName(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Temario 2026",
                    )
                    FieldLabel("Slug (opcional, se genera del nombre)")
                    GlassTextField(
                        value = state.editorSlug,
                        onValueChange = { viewModel.dispatch(TagsIntent.UpdateSlug(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "temario-2026",
                    )
                    GlassActionButton(
                        text = "Guardar etiqueta",
                        enabled = state.editorName.isNotBlank(),
                        onClick = { viewModel.dispatch(TagsIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selectedId != null) {
                        GlassActionButton(
                            text = "Eliminar etiqueta",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormFeedback(state.actionState, successText = "Etiqueta sincronizada.")
                }
            }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar etiqueta",
            message = "Se eliminará la etiqueta seleccionada. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                viewModel.dispatch(TagsIntent.DeleteSelected)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.tokens.textSecondary,
    )
}
