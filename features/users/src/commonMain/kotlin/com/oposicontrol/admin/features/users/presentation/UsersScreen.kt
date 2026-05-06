package com.oposicontrol.admin.features.users.presentation

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
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection

@Composable
fun UsersScreen(
    viewModel: UsersViewModel,
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterBar(
                    searchValue = searchQuery,
                    onSearchChange = { searchQuery = it },
                    placeholder = "Buscar por nombre o email",
                )
                AsyncStatePane(state.users, emptyMessage = "No hay usuarios disponibles.") { users ->
                    val filtered = users.filter {
                        searchQuery.isBlank() ||
                            it.fullName.contains(searchQuery, ignoreCase = true) ||
                            it.email.contains(searchQuery, ignoreCase = true)
                    }
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Email", 1.3f),
                            AdminTableColumn("Activo", 0.7f),
                        ),
                        rows = filtered.map { user ->
                            AdminTableRow(
                                key = user.id,
                                cells = listOf(user.fullName, user.email, if (user.isActive) "active" else "inactive"),
                                selected = user.id == state.selectedUserId,
                                supporting = user.roleIds.joinToString(),
                            )
                        },
                        onRowClick = { viewModel.dispatch(UsersIntent.SelectUser(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedUserId == null) "Nuevo usuario" else "Editor de usuario",
                description = "Base administrativa con usuarios reales, roles asignados y estado de acceso.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(UsersIntent.UpdateName(it)) },
                        label = { Text("Nombre completo") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorEmail,
                        onValueChange = { viewModel.dispatch(UsersIntent.UpdateEmail(it)) },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorRoleIds,
                        onValueChange = { viewModel.dispatch(UsersIntent.UpdateRoleIds(it)) },
                        label = { Text("Roles CSV") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Usuario activo")
                        Switch(
                            checked = state.editorIsActive,
                            onCheckedChange = { viewModel.dispatch(UsersIntent.ToggleActive(it)) },
                        )
                    }
                    Button(onClick = { viewModel.dispatch(UsersIntent.Save) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Guardar")
                    }
                    Button(
                        onClick = { viewModel.dispatch(UsersIntent.DeleteSelected) },
                        enabled = state.selectedUserId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Eliminar")
                    }
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando usuario...")
                        is UiAsync.Success -> Text("Usuario actualizado.")
                        else -> Unit
                    }
                }
            }
        },
    )
}
