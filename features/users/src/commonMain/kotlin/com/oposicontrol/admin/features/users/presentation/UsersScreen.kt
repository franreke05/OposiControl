package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.oposicontrol.admin.core.ui.components.GlassSurface
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
                                cells = listOf(user.fullName, user.email, if (user.isActive) "activo" else "bloqueado"),
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
                        FieldLabel("Acciones rápidas")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GlassActionButton(
                                text = if (state.editorIsActive) "Bloquear" else "Desbloquear",
                                tint = if (state.editorIsActive) MaterialTheme.colorScheme.error else GlassTheme.tokens.accent,
                                onClick = {
                                    state.selectedUserId?.let { viewModel.dispatch(UsersIntent.QuickToggleBlock(it)) }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            GlassActionButton(
                                text = "Tickets",
                                onClick = { viewModel.dispatch(UsersIntent.OpenTicketsDialog) },
                                modifier = Modifier.weight(1f),
                            )
                            GlassActionButton(
                                text = "Advertir",
                                onClick = { viewModel.dispatch(UsersIntent.OpenWarningDialog) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        GlassActionButton(
                            text = "Eliminar usuario",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    FormFeedback(state.actionState, successText = "Operación completada.")
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

    if (state.ticketsDialogOpen) {
        TicketsDialog(
            input = state.ticketsInput,
            onInputChange = { viewModel.dispatch(UsersIntent.UpdateTicketsInput(it)) },
            onConfirm = { viewModel.dispatch(UsersIntent.ConfirmTickets) },
            onDismiss = { viewModel.dispatch(UsersIntent.DismissTicketsDialog) },
        )
    }

    if (state.warningDialogOpen) {
        WarningDialog(
            input = state.warningInput,
            onInputChange = { viewModel.dispatch(UsersIntent.UpdateWarningInput(it)) },
            onConfirm = { viewModel.dispatch(UsersIntent.ConfirmWarning) },
            onDismiss = { viewModel.dispatch(UsersIntent.DismissWarningDialog) },
        )
    }
}

@Composable
private fun TicketsDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val amount = input.toIntOrNull()
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 400.dp),
            shape = RoundedCornerShape(24.dp),
            tint = tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Ajustar tickets",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "Número positivo para dar tickets, negativo para quitar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                GlassTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = "Ej. 10 o -5",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    GlassActionButton(text = "Cancelar", tint = tokens.glassFill, textColor = tokens.textPrimary, onClick = onDismiss)
                    GlassActionButton(text = "Confirmar", enabled = amount != null && amount != 0, onClick = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun WarningDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            tint = tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Enviar advertencia",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "Quedará registrado como acción de moderación.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                GlassTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = "Motivo de la advertencia",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    GlassActionButton(text = "Cancelar", tint = tokens.glassFill, textColor = tokens.textPrimary, onClick = onDismiss)
                    GlassActionButton(text = "Enviar", enabled = input.isNotBlank(), onClick = onConfirm)
                }
            }
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
