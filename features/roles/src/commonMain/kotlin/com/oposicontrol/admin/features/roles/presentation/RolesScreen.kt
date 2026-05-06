package com.oposicontrol.admin.features.roles.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

@Composable
fun RolesScreen(
    viewModel: RolesViewModel,
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = searchQuery,
                    onSearchChange = { searchQuery = it },
                    placeholder = "Buscar rol",
                )
                AsyncStatePane(state.roles, emptyMessage = "No hay roles.") { roles ->
                    val filtered = roles.filter {
                        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
                    }
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Rol", 1.2f),
                            AdminTableColumn("Permisos", 0.8f),
                        ),
                        rows = filtered.map { role ->
                            AdminTableRow(
                                key = role.id,
                                cells = listOf(role.name, role.permissions.size.toString()),
                                selected = role.id == state.selectedRoleId,
                                supporting = role.permissions.joinToString { it.key },
                            )
                        },
                        onRowClick = { viewModel.dispatch(RolesIntent.SelectRole(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedRoleId == null) "Nuevo rol" else "Editor de rol",
                description = "Permisos provisionales en CSV hasta conectar el modelo real.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(RolesIntent.UpdateName(it)) },
                        label = { Text("Nombre del rol") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorPermissions,
                        onValueChange = { viewModel.dispatch(RolesIntent.UpdatePermissions(it)) },
                        label = { Text("Permisos CSV") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.Button(
                        onClick = { viewModel.dispatch(RolesIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Guardar rol")
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Persistiendo rol...")
                        is UiAsync.Success -> Text("Rol actualizado.")
                        else -> Unit
                    }
                }
            }
        },
    )
}
