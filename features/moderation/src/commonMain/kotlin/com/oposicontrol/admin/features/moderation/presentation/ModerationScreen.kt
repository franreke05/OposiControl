package com.oposicontrol.admin.features.moderation.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.oposicontrol.admin.core.ui.components.StatusChip

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
                    placeholder = "Buscar usuario o accion",
                )
                AsyncStatePane(state.actions, emptyMessage = "No hay acciones de moderacion.") { actions ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Accion", 0.9f),
                            AdminTableColumn("Usuario", 1.1f),
                            AdminTableColumn("Executor", 1.1f),
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
                title = if (state.selectedId == null) "Nueva accion" else "Detalle de moderacion",
                description = "Base para warnings, mutes, cierres de chat y notas internas.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(state.editorActionType)
                    OutlinedTextField(
                        value = state.editorActionType,
                        onValueChange = { viewModel.dispatch(ModerationIntent.UpdateActionType(it)) },
                        label = { Text("Tipo de accion") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorTargetUser,
                        onValueChange = { viewModel.dispatch(ModerationIntent.UpdateTargetUser(it)) },
                        label = { Text("Usuario objetivo") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorExecutor,
                        onValueChange = { viewModel.dispatch(ModerationIntent.UpdateExecutor(it)) },
                        label = { Text("Ejecutado por") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorReason,
                        onValueChange = { viewModel.dispatch(ModerationIntent.UpdateReason(it)) },
                        label = { Text("Razon") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.dispatch(ModerationIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Registrar accion")
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando...")
                        is UiAsync.Success -> Text("Accion preparada para persistencia posterior.")
                        else -> Unit
                    }
                }
            }
        },
    )
}
