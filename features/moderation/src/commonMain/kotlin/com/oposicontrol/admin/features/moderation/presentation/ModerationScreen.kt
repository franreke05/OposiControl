package com.oposicontrol.admin.features.moderation.presentation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
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
                    text = "Nueva advertencia",
                    modifier = Modifier.width(168.dp),
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
            val selected = state.selectedId?.let { id -> (state.actions as? UiAsync.Success)?.value?.firstOrNull { it.id == id } }
            if (state.selectedId != null) {
                FormSection(
                    title = "Detalle de moderación",
                    description = "Acción registrada en el historial. Solo lectura.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip(state.editorActionType)
                        FieldLabel("Usuario")
                        DetailText(state.editorTargetUser)
                        FieldLabel("Ejecutado por")
                        DetailText(selected?.executor.orEmpty())
                        FieldLabel("Fecha")
                        DetailText(selected?.createdAt.orEmpty())
                        FieldLabel("Razón")
                        DetailText(state.editorReason)
                    }
                }
            } else {
                FormSection(
                    title = "Nueva advertencia",
                    description = "La advertencia es la única medida que el servidor aplica hoy. Queda registrada en Auditoría con su motivo.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FieldLabel("Tipo de acción")
                        StatusChip("Advertencia")
                        FieldLabel("Usuario objetivo (UUID)")
                        GlassTextField(
                            value = state.editorTargetUser,
                            onValueChange = { viewModel.dispatch(ModerationIntent.UpdateTargetUser(it)) },
                            placeholder = "00000000-0000-0000-0000-000000000000",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FieldLabel("Ejecutado por")
                        DetailText(state.editorExecutor.ifBlank { "Tu sesión de administrador" })
                        FieldLabel("Motivo")
                        GlassTextField(
                            value = state.editorReason,
                            onValueChange = { viewModel.dispatch(ModerationIntent.UpdateReason(it)) },
                            placeholder = "Motivo de la advertencia",
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        GlassActionButton(
                            text = "Registrar advertencia",
                            enabled = state.actionState !is UiAsync.Loading,
                            loading = state.actionState is UiAsync.Loading,
                            onClick = { viewModel.dispatch(ModerationIntent.Save) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FormFeedback(
                            state.actionState,
                            successText = "Advertencia registrada. Consúltala en Auditoría: este historial muestra las " +
                                "acciones sobre reportes.",
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DetailText(text: String) {
    Text(
        text = text.ifBlank { "—" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.tokens.textSecondary,
    )
}
