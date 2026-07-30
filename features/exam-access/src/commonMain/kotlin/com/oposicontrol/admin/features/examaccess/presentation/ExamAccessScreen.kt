package com.oposicontrol.admin.features.examaccess.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.oposicontrol.admin.core.ui.components.StatusChip
import kotlinx.coroutines.flow.collect

@Composable
fun ExamAccessScreen(
    viewModel: ExamAccessViewModel,
    onEffect: (ExamAccessEffect) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel, onEffect) {
        viewModel.effects.collect(onEffect)
    }

    ExamAccessContent(
        state = state,
        onIntent = viewModel::dispatch,
    )
}

@Composable
fun ExamAccessContent(
    state: ExamAccessState,
    onIntent: (ExamAccessIntent) -> Unit,
) {
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { onIntent(ExamAccessIntent.UpdateSearch(it)) },
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
                                key = access.id.value,
                                cells = listOf(access.userName, access.examTitle, access.accessType, access.status),
                                selected = access.id == state.selectedId,
                            )
                        },
                        onRowClick = { onIntent(ExamAccessIntent.Select(ExamAccessId(it))) },
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
                    CatalogPicker(
                        label = "Usuario",
                        selectedLabel = state.editorUserName,
                        catalog = state.userCatalog.mapItems {
                            it.map { option -> option.id.value to option.displayName }
                        },
                        onSelect = { id, label ->
                            onIntent(ExamAccessIntent.SelectUser(UserId(id), label))
                        },
                    )
                    CatalogPicker(
                        label = "Examen",
                        selectedLabel = state.editorExamTitle,
                        catalog = state.examCatalog.mapItems {
                            it.map { option -> option.id.value to option.title }
                        },
                        onSelect = { id, label ->
                            onIntent(ExamAccessIntent.SelectExam(ExamId(id), label))
                        },
                    )
                    OutlinedTextField(
                        value = state.editorAccessType,
                        onValueChange = { onIntent(ExamAccessIntent.UpdateAccessType(it)) },
                        label = { Text("Tipo de acceso") },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Acceso activo")
                        Switch(
                            checked = state.editorIsActive,
                            onCheckedChange = { onIntent(ExamAccessIntent.ToggleActive(it)) },
                        )
                    }
                    Button(
                        onClick = { onIntent(ExamAccessIntent.Save) },
                        enabled = state.editorUserId != null &&
                            state.editorExamId != null &&
                            state.actionState != UiAsync.Loading,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
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

@Composable
private fun CatalogPicker(
    label: String,
    selectedLabel: String,
    catalog: UiAsync<List<Pair<String, String>>>,
    onSelect: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            enabled = catalog is UiAsync.Success,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(selectedLabel.ifBlank { "Seleccionar $label" })
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            (catalog as? UiAsync.Success)?.value.orEmpty().forEach { (id, displayLabel) ->
                DropdownMenuItem(
                    text = { Text(displayLabel) },
                    onClick = {
                        expanded = false
                        onSelect(id, displayLabel)
                    },
                )
            }
        }
    }
    when (catalog) {
        UiAsync.Loading -> Text("Cargando ${label.lowercase()}s...")
        UiAsync.Empty -> Text("No hay ${label.lowercase()}s disponibles.")
        is UiAsync.Error -> Text(catalog.error.message, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

private fun <T, R> UiAsync<List<T>>.mapItems(transform: (List<T>) -> List<R>): UiAsync<List<R>> =
    when (this) {
        UiAsync.Idle -> UiAsync.Idle
        UiAsync.Loading -> UiAsync.Loading
        UiAsync.Empty -> UiAsync.Empty
        is UiAsync.Error -> UiAsync.Error(error)
        is UiAsync.Success -> transform(value).let { mapped ->
            if (mapped.isEmpty()) UiAsync.Empty else UiAsync.Success(mapped)
        }
    }
