package com.oposicontrol.admin.features.examaccess.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.oposicontrol.admin.core.ui.components.StatusChip

@Composable
fun ExamAccessScreen(
    viewModel: ExamAccessViewModel,
) {
    val state by viewModel.state.collectAsState()
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(ExamAccessIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por usuario o examen",
                )
                AsyncStatePane(state.accesses, emptyMessage = "No hay accesos registrados.") { accesses ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Usuario", 1.2f),
                            AdminTableColumn("Examen", 1.3f),
                            AdminTableColumn("Tipo", 0.9f),
                            AdminTableColumn("Estado", 0.8f),
                        ),
                        rows = accesses.map { access ->
                            AdminTableRow(
                                key = access.id,
                                cells = listOf(access.userName, access.examTitle, access.accessType, access.status),
                                selected = access.id == state.selectedId,
                            )
                        },
                        onRowClick = { viewModel.dispatch(ExamAccessIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Conceder acceso" else "Detalle de acceso",
                description = "Operacion para revisar y conceder accesos reales por usuario y examen.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusChip(if (state.editorIsActive) "active" else "inactive")
                    OutlinedTextField(
                        value = state.editorUserName,
                        onValueChange = { viewModel.dispatch(ExamAccessIntent.UpdateUserName(it)) },
                        label = { Text("Usuario") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorExamTitle,
                        onValueChange = { viewModel.dispatch(ExamAccessIntent.UpdateExamTitle(it)) },
                        label = { Text("Examen") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorAccessType,
                        onValueChange = { viewModel.dispatch(ExamAccessIntent.UpdateAccessType(it)) },
                        label = { Text("Tipo de acceso") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Acceso activo")
                        Switch(
                            checked = state.editorIsActive,
                            onCheckedChange = { viewModel.dispatch(ExamAccessIntent.ToggleActive(it)) },
                        )
                    }
                    Button(
                        onClick = { viewModel.dispatch(ExamAccessIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Guardar acceso")
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando...")
                        is UiAsync.Success -> Text("Acceso preparado para integracion segura.")
                        else -> Unit
                    }
                }
            }
        },
    )
}
