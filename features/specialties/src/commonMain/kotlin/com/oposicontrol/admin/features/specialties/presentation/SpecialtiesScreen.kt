package com.oposicontrol.admin.features.specialties.presentation

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
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.StatusChip

@Composable
fun SpecialtiesScreen(
    viewModel: SpecialtiesViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(SpecialtiesIntent.UpdateSearch(it)) },
                    placeholder = "Buscar por nombre o descripción",
                )
                GlassActionButton(
                    text = "Nueva especialidad",
                    modifier = Modifier.width(168.dp),
                    onClick = { viewModel.dispatch(SpecialtiesIntent.CreateNew) },
                )
                AsyncStatePane(state.items, emptyMessage = "No hay especialidades para este filtro.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Estado", 0.7f),
                            AdminTableColumn("Orden", 0.5f),
                            AdminTableColumn("Exámenes", 0.6f),
                        ),
                        rows = items.map { item ->
                            AdminTableRow(
                                key = item.id,
                                cells = listOf(
                                    item.name,
                                    if (item.isActive) "active" else "inactive",
                                    item.sortOrder.toString(),
                                    item.linkedExams.toString(),
                                ),
                                selected = item.id == state.selectedId,
                                supporting = item.description.ifBlank { "Sin descripción" },
                            )
                        },
                        onRowClick = { viewModel.dispatch(SpecialtiesIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nueva especialidad" else "Detalle de especialidad",
                description = "El admin solo gestiona estructura de contenido y exámenes asociados.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(if (state.editorIsActive) "active" else "inactive")
                    Text(
                        text = "Nombre",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateName(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Auxiliar Administrativo",
                    )
                    Text(
                        text = "Descripción",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorDescription,
                        onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateDescription(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Resumen operativo de la especialidad",
                        singleLine = false,
                    )
                    Text(
                        text = "Orden",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorSortOrder,
                        onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateSortOrder(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "0",
                    )
                    GlassToggleRow(
                        title = "Activa para el catálogo",
                        description = "Solo las activas quedan visibles para contenido nuevo.",
                        checked = state.editorIsActive,
                        onCheckedChange = { viewModel.dispatch(SpecialtiesIntent.ToggleActive(it)) },
                    )
                    GlassActionButton(
                        text = "Guardar especialidad",
                        onClick = { viewModel.dispatch(SpecialtiesIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selectedId != null) {
                        GlassActionButton(
                            text = "Eliminar especialidad",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FormFeedback(state.actionState, successText = "Especialidad sincronizada.")
                }
            }

            if (state.selectedId != null) {
                SpecialtyCapabilitiesSection(state = state, viewModel = viewModel)
            }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "Eliminar especialidad",
            message = "Se eliminará la especialidad seleccionada. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                viewModel.dispatch(SpecialtiesIntent.DeleteSelected)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Modos disponibles de la especialidad seleccionada.
 *
 * Va en su propia seccion y con su propio guardado porque escribe en otra tabla
 * y, sobre todo, porque cambia lo que ve el usuario final en OposiBot: apagar
 * "inmersivos" aqui hace desaparecer ese modo de la app. Mezclarlo con el boton
 * de guardar el nombre invitaria a cambiarlo sin querer.
 */
@Composable
private fun SpecialtyCapabilitiesSection(
    state: SpecialtiesState,
    viewModel: SpecialtiesViewModel,
) {
    FormSection(
        title = "Modos disponibles",
        description = "OposiBot solo muestra los modos habilitados aquí. No todas las especialidades " +
            "tienen el mismo contenido.",
        help = Help.capacidadesEspecialidad,
    ) {
        AsyncStatePane(
            state.capabilities,
            emptyMessage = "Sin configuración de modos para esta especialidad.",
        ) { _ ->
            val draft = state.capabilitiesDraft
            if (draft == null) {
                Text(
                    text = "Selecciona una especialidad para ver sus modos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@AsyncStatePane
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!draft.configured) {
                    // Nadie ha decidido esto todavia: lo mostrado son los valores por
                    // defecto del esquema, no una eleccion. Decirlo evita que se lea
                    // como "ya revisado".
                    Text(
                        text = "Todavía sin configurar. Se muestran los valores por defecto; " +
                            "guarda para fijarlos explicitamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Vocabulario canonico: rafaga / razonado / inmersivo.
                GlassToggleRow(
                    title = "Ráfagas",
                    description = "Preguntas sueltas con solucionario de opción correcta.",
                    checked = draft.permiteRafagas,
                    onCheckedChange = {
                        viewModel.dispatch(
                            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.RAFAGAS, it),
                        )
                    },
                )
                GlassToggleRow(
                    title = "Razonados",
                    description = "Solucionario con explicación escrita.",
                    checked = draft.permiteRazonados,
                    onCheckedChange = {
                        viewModel.dispatch(
                            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.RAZONADOS, it),
                        )
                    },
                )
                GlassToggleRow(
                    title = "Inmersivos",
                    description = "Único modo que admite recursos multimedia.",
                    checked = draft.permiteInmersivos,
                    onCheckedChange = {
                        viewModel.dispatch(
                            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.INMERSIVOS, it),
                        )
                    },
                )
                GlassToggleRow(
                    title = "Demos",
                    description = "Permite probar modos no incluidos en el plan del usuario.",
                    checked = draft.permiteDemos,
                    onCheckedChange = {
                        viewModel.dispatch(
                            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.DEMOS, it),
                        )
                    },
                )
                GlassToggleRow(
                    title = "OposiMarket",
                    description = "Acceso a packs, potenciadores y protectores.",
                    checked = draft.permiteOposimarket,
                    onCheckedChange = {
                        viewModel.dispatch(
                            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.OPOSIMARKET, it),
                        )
                    },
                )

                Text(
                    text = "Modos de examen activos: " +
                        draft.enabledExamModes.joinToString(", ").ifBlank { "ninguno" },
                    style = MaterialTheme.typography.labelMedium,
                )

                if (draft.hasNoExamModes) {
                    // El backend rechaza guardar esto sin mensaje; se avisa antes de
                    // que el admin pulse Guardar, no despues.
                    Text(
                        text = "Sin ningun modo de examen el usuario no tendra nada que hacer. " +
                            "Explica por que abajo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(text = "Mensaje de limitacion", style = MaterialTheme.typography.labelMedium)
                GlassTextField(
                    value = draft.limitationMessage.orEmpty(),
                    onValueChange = { viewModel.dispatch(SpecialtiesIntent.UpdateLimitationMessage(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Ej. Contenido inmersivo en preparacion para esta especialidad",
                    singleLine = false,
                )

                GlassActionButton(
                    text = if (state.capabilitiesDirty) "Guardar modos" else "Modos guardados",
                    enabled = state.capabilitiesDirty,
                    onClick = { viewModel.dispatch(SpecialtiesIntent.SaveCapabilities) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FormFeedback(state.capabilitiesActionState, successText = "Modos actualizados.")
            }
        }
    }
}
