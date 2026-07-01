package com.oposicontrol.admin.features.users.presentation

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
import com.oposicontrol.admin.core.ui.components.GlassChipGroup
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun UsersScreen(
    viewModel: UsersViewModel,
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val roleName = remember(state.availableRoles) {
        state.availableRoles.associate { it.id to it.name }
    }

    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterBar(
                    searchValue = searchQuery,
                    onSearchChange = { searchQuery = it },
                    placeholder = "Buscar por nombre o email",
                )
                GlassActionButton(
                    text = "Nuevo usuario",
                    modifier = Modifier.width(144.dp),
                    onClick = { viewModel.dispatch(UsersIntent.CreateNew) },
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
                            AdminTableColumn("Estado", 0.7f),
                        ),
                        rows = filtered.map { user ->
                            AdminTableRow(
                                key = user.id,
                                cells = listOf(user.fullName, user.email, if (user.isActive) "activo" else "inactivo"),
                                selected = user.id == state.selectedUserId,
                                supporting = user.roleIds
                                    .map { roleName[it] ?: it }
                                    .joinToString()
                                    .ifBlank { "Sin roles" },
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
                description = "Usuarios reales, roles asignados y estado de acceso.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldLabel("Nombre completo")
                    GlassTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(UsersIntent.UpdateName(it)) },
                        placeholder = "Ej. Ana García",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FieldLabel("Email")
                    GlassTextField(
                        value = state.editorEmail,
                        onValueChange = { viewModel.dispatch(UsersIntent.UpdateEmail(it)) },
                        placeholder = "persona@dominio.com",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!state.isEmailValid) {
                        Text(
                            text = "Formato de email no válido",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    FieldLabel("Roles")
                    if (state.availableRoles.isEmpty()) {
                        Text(
                            text = "Cargando catálogo de roles…",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.tokens.textSecondary,
                        )
                    } else {
                        GlassChipGroup(
                            options = state.availableRoles.map { it.id to it.name },
                            selected = state.editorRoleIds,
                            onToggle = { viewModel.dispatch(UsersIntent.ToggleRole(it)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    GlassToggleRow(
                        title = "Usuario activo",
                        description = "Solo los usuarios activos pueden acceder al sistema.",
                        checked = state.editorIsActive,
                        onCheckedChange = { viewModel.dispatch(UsersIntent.ToggleActive(it)) },
                    )

                    GlassActionButton(
                        text = "Guardar",
                        enabled = state.canSave,
                        onClick = { viewModel.dispatch(UsersIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selectedUserId != null) {
                        GlassActionButton(
                            text = "Eliminar usuario",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    FormFeedback(state.actionState, successText = "Usuario actualizado.")
                }
            }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar usuario",
            message = "Esta acción quitará el usuario seleccionado. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                viewModel.dispatch(UsersIntent.DeleteSelected)
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
