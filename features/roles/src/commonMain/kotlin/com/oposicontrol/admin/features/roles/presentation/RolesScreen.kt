package com.oposicontrol.admin.features.roles.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
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
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassChipGroup
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun RolesScreen(
    viewModel: RolesViewModel,
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val selectedRole = (state.roles as? UiAsync.Success)?.value?.firstOrNull { it.id == state.selectedRoleId }

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
                                supporting = role.permissions.joinToString { it.label }.ifBlank { "Sin permisos" },
                            )
                        },
                        onRowClick = { viewModel.dispatch(RolesIntent.SelectRole(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = selectedRole?.name ?: "Detalle de rol",
                description = "Catálogo de roles gestionado en el backend (solo lectura desde el panel).",
            ) {
                if (selectedRole == null) {
                    EmptyStateCard(
                        title = "Selecciona un rol",
                        message = "Elige un rol de la lista para ver sus permisos asignados.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Permisos",
                            style = MaterialTheme.typography.labelMedium,
                            color = GlassTheme.tokens.textSecondary,
                        )
                        if (selectedRole.permissions.isEmpty()) {
                            Text(
                                text = "Este rol no tiene permisos granulares definidos.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTheme.tokens.textSecondary,
                            )
                        } else {
                            GlassChipGroup(
                                options = selectedRole.permissions.map { it.key to it.label },
                                selected = selectedRole.permissions.map { it.key }.toSet(),
                                onToggle = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
    )
}
