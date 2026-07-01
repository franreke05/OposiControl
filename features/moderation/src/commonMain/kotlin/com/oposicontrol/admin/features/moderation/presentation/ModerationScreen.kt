package com.oposicontrol.admin.features.moderation.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun ModerationScreen(
    viewModel: ModerationViewModel,
) {
    val state by viewModel.state.collectAsState()
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(ModerationIntent.UpdateSearch(it)) },
                    placeholder = "Buscar usuario o acción",
                )
                GlassActionButton(
                    text = "Nueva acción",
                    modifier = Modifier.width(136.dp),
                    onClick = { viewModel.dispatch(ModerationIntent.CreateNew) },
                )
                AsyncStatePane(state.actions, emptyMessage = "No hay acciones de moderación.") { actions ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Acción", 0.9f),
                            AdminTableColumn("Usuario", 1.1f),
                            AdminTableColumn("Ejecutor", 1.1f),
                            AdminTableColumn("Fecha", 0.9f),
                        ),
                        rows = actions.map { action ->
                            AdminTableRow(
                                key = action.id,
                                cells = listOf(action.actionType, action.targetUser, action.executor, action.createdAt),
                                selected = action.id == state.selectedId,
                                supporting = action.reason,
                            )
                        },
                        onRowClick = { viewModel.dispatch(ModerationIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nueva acción" else "Detalle de moderación",
                description = "Warnings, mutes, cierres de chat y notas internas.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.editorActionType.isNotBlank()) {
                        StatusChip(state.editorActionType)
                    }
                    FieldLabel("Tipo de acción")
                    ActionTypeSelector(
                        selected = state.editorActionType,
                        onSelected = { viewModel.dispatch(ModerationIntent.UpdateActionType(it)) },
                    )
                    FieldLabel("Usuario objetivo")
                    GlassTextField(
                        value = state.editorTargetUser,
                        onValueChange = { viewModel.dispatch(ModerationIntent.UpdateTargetUser(it)) },
                        placeholder = "ID o nombre del usuario",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldLabel("Ejecutado por")
                    Text(
                        text = state.editorExecutor.ifBlank { "Cargando..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FieldLabel("Razón")
                    GlassTextField(
                        value = state.editorReason,
                        onValueChange = { viewModel.dispatch(ModerationIntent.UpdateReason(it)) },
                        placeholder = "Motivo de la medida",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassActionButton(
                        text = "Registrar acción",
                        onClick = { viewModel.dispatch(ModerationIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormFeedback(state.actionState, successText = "Acción registrada.")
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionTypeSelector(selected: String, onSelected: (String) -> Unit) {
    val actionTypes = listOf("ocultar", "restaurar", "eliminar", "advertir", "bloquear_usuario", "descartar")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actionTypes.forEach { type ->
            GlassActionButton(
                text = type,
                modifier = Modifier.width(148.dp),
                tint = if (selected == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                textColor = if (selected == type) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onSelected(type) },
            )
        }
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
