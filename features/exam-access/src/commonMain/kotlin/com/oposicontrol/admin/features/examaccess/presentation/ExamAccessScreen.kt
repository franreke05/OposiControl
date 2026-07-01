package com.oposicontrol.admin.features.examaccess.presentation

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
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme

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
                GlassActionButton(
                    text = "Nuevo acceso",
                    modifier = Modifier.width(136.dp),
                    onClick = { viewModel.dispatch(ExamAccessIntent.CreateNew) },
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
                description = "Revisa y concede accesos reales por usuario y examen.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusChip(if (state.editorIsActive) "active" else "inactive")
                    FieldLabel("Usuario")
                    GlassTextField(
                        value = state.editorUserName,
                        onValueChange = { viewModel.dispatch(ExamAccessIntent.UpdateUserName(it)) },
                        placeholder = "ID o nombre del usuario",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldLabel("Examen")
                    GlassTextField(
                        value = state.editorExamTitle,
                        onValueChange = { viewModel.dispatch(ExamAccessIntent.UpdateExamTitle(it)) },
                        placeholder = "Título del examen",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldLabel("Tipo de acceso")
                    GlassTextField(
                        value = state.editorAccessType,
                        onValueChange = { viewModel.dispatch(ExamAccessIntent.UpdateAccessType(it)) },
                        placeholder = "compra, regalo, prueba…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassToggleRow(
                        title = "Acceso activo",
                        description = "Si está desactivado, el usuario no podrá abrir el examen.",
                        checked = state.editorIsActive,
                        onCheckedChange = { viewModel.dispatch(ExamAccessIntent.ToggleActive(it)) },
                    )
                    GlassActionButton(
                        text = "Guardar acceso",
                        onClick = { viewModel.dispatch(ExamAccessIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormFeedback(state.actionState, successText = "Acceso guardado.")
                }
            }
        },
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
