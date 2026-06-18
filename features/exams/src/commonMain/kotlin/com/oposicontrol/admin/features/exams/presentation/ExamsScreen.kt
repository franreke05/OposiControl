package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import com.oposicontrol.admin.core.common.presentation.UiAsync
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
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.domain.model.Specialty

@Composable
fun ExamsScreen(
    viewModel: ExamsViewModel,
) {
    val state by viewModel.state.collectAsState()
    ExamsContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
fun ExamsContent(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    var confirmDeleteExam by remember { mutableStateOf(false) }
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { onIntent(ExamsIntent.UpdateSearch(it)) },
                    placeholder = "Buscar examen o especialidad",
                    filters = {
                        GlassTextField(
                            value = state.statusFilter,
                            onValueChange = { onIntent(ExamsIntent.UpdateStatusFilter(it)) },
                            placeholder = "all | draft | published | archived",
                        )
                    },
                )
                AsyncStatePane(state.exams, emptyMessage = "No hay examenes.") { exams ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Titulo", 1.2f),
                            AdminTableColumn("Especialidad", 0.9f),
                            AdminTableColumn("Tickets", 0.5f),
                            AdminTableColumn("Estado", 0.7f),
                            AdminTableColumn("Preguntas", 0.6f),
                        ),
                        rows = exams.map { exam ->
                            AdminTableRow(
                                key = exam.id,
                                cells = listOf(
                                    exam.title,
                                    exam.specialtyName,
                                    exam.priceTickets.toString(),
                                    exam.status.name.lowercase(),
                                    exam.questionCount.toString(),
                                ),
                                selected = exam.id == state.selectedExamId,
                                supporting = exam.publishFrom?.toString() ?: "Sin fecha de publicacion",
                            )
                        },
                        onRowClick = { onIntent(ExamsIntent.SelectExam(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedExamId == null) "Nuevo examen" else "Detalle de examen",
                description = "Contenido agrupado por especialidad, con estado y fecha opcional de publicacion.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(state.editorStatus)
                    EditorField(
                        label = "Titulo",
                        value = state.editorTitle,
                        onValueChange = { onIntent(ExamsIntent.UpdateTitle(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Simulacro bloque 1",
                    )
                    EditorField(
                        label = "Precio tickets",
                        value = state.editorPriceTickets,
                        onValueChange = { onIntent(ExamsIntent.UpdatePriceTickets(it.filter { char -> char.isDigit() }.take(9))) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "0",
                    )
                    SpecialtySelector(
                        specialtiesState = state.specialties,
                        selectedId = state.editorSpecialtyId,
                        onSelected = { onIntent(ExamsIntent.UpdateSpecialtyId(it)) },
                    )
                    StatusSelectorField(
                        selected = state.editorStatus,
                        onSelected = { onIntent(ExamsIntent.UpdateExamStatus(it)) },
                    )
                    EditorField(
                        label = "Publicar desde (ISO)",
                        value = state.editorPublishFrom,
                        onValueChange = { onIntent(ExamsIntent.UpdatePublishFrom(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "2026-04-15T09:00:00Z",
                    )
                    ResponsiveGlassActions(
                        actions = listOf(
                            FormAction("Guardar examen", 176, true) { onIntent(ExamsIntent.SaveExam) },
                            FormAction("Eliminar examen", 176, state.selectedExamId != null) { confirmDeleteExam = true },
                        ),
                    )
                    FormFeedback(state.actionState, successText = "Examen sincronizado.")
                }
            }
        },
    )

    if (confirmDeleteExam) {
        ConfirmDialog(
            title = "Eliminar examen",
            message = "Se eliminará el examen seleccionado y su contenido asociado. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDeleteExam = false
                onIntent(ExamsIntent.DeleteSelectedExam)
            },
            onDismiss = { confirmDeleteExam = false },
        )
    }
}

private data class FormAction(
    val text: String,
    val width: Int,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResponsiveGlassActions(
    actions: List<FormAction>,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        actions.forEach { action ->
            GlassActionButton(
                text = action.text,
                enabled = action.enabled,
                modifier = Modifier.width(action.width.dp),
                onClick = action.onClick,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSelectorField(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val options = listOf("draft" to "Borrador", "published" to "Publicado", "archived" to "Archivado")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Estado",
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            options.forEach { (value, label) ->
                GlassActionButton(
                    text = label,
                    modifier = Modifier.width(132.dp),
                    tint = if (selected == value) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    textColor = if (selected == value) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = { onSelected(value) },
                )
            }
        }
    }
}

@Composable
fun QuestionsResourcesScreen(
    viewModel: ExamsViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmDeleteQuestion by remember { mutableStateOf(false) }
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormSection(
                    title = "Examen activo",
                    description = "Selecciona el examen y gestiona preguntas con 4 opciones e IDs estables.",
                ) {
                    AsyncStatePane(state.exams, emptyMessage = "No hay examenes cargados.") { exams ->
                        AdminTable(
                            columns = listOf(
                                AdminTableColumn("Titulo", 1.1f),
                                AdminTableColumn("Estado", 0.6f),
                                AdminTableColumn("Preguntas", 0.6f),
                            ),
                            rows = exams.map { exam ->
                                AdminTableRow(
                                    key = exam.id,
                                    cells = listOf(exam.title, exam.status.name.lowercase(), exam.questionCount.toString()),
                                    selected = exam.id == state.selectedExamId,
                                    supporting = exam.specialtyName,
                                )
                            },
                            onRowClick = { viewModel.dispatch(ExamsIntent.SelectExam(it)) },
                        )
                    }
                }
                FormSection(
                    title = "Preguntas",
                    description = "Orden, enunciado y recursos explicativos ligados a cada pregunta.",
                ) {
                    GlassTextField(
                        value = state.questionResourceFilter,
                        onValueChange = { viewModel.dispatch(ExamsIntent.UpdateQuestionResourceFilter(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "all | with_resource | without_resource | pending",
                    )
                    AsyncStatePane(state.questions, emptyMessage = "Este examen aun no tiene preguntas.") { questions ->
                        val filteredQuestions = questions.filter { question ->
                            when (state.questionResourceFilter.trim().lowercase()) {
                                "with_resource" -> question.resources.isNotEmpty()
                                "without_resource" -> question.resources.isEmpty()
                                "pending" -> question.resources.any { resource ->
                                    resource.textBody.isNullOrBlank() && resource.publicUrl.isNullOrBlank()
                                }
                                else -> true
                            }
                        }
                        AdminTable(
                            columns = listOf(
                                AdminTableColumn("Titulo", 1f),
                                AdminTableColumn("Estado", 0.6f),
                                AdminTableColumn("Recursos", 0.6f),
                            ),
                            rows = filteredQuestions.map { question ->
                                val pending = question.resources.any { resource ->
                                    resource.textBody.isNullOrBlank() && resource.publicUrl.isNullOrBlank()
                                }
                                AdminTableRow(
                                    key = question.id,
                                    cells = listOf(
                                        question.title,
                                        if (pending) "pendiente" else question.options.firstOrNull { it.isCorrect }?.label ?: "-",
                                        if (question.resources.isEmpty()) "sin recurso" else "${question.resources.size}",
                                    ),
                                    selected = question.id == state.selectedQuestionId,
                                    supporting = question.statement,
                                )
                            },
                            onRowClick = { viewModel.dispatch(ExamsIntent.SelectQuestion(it)) },
                        )
                    }
                }
                FormSection(
                    title = "Auditoria de recursos",
                    description = "Resumen de preguntas con recurso y recursos detectados sin enlace.",
                ) {
                    when (val audit = state.resourceAudit) {
                        is UiAsync.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip("${audit.value.summary.totalQuestions} preguntas")
                                StatusChip("${audit.value.summary.questionsWithResources} con recurso")
                                StatusChip("${audit.value.summary.questionsWithoutResources} sin recurso")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip("${audit.value.summary.linkedResources} enlazados")
                                StatusChip("${audit.value.summary.pendingResources} pendientes")
                                StatusChip("${audit.value.summary.unlinkedResources} sin enlazar")
                            }
                            if (audit.value.unlinkedResources.isNotEmpty()) {
                                AdminTable(
                                    columns = listOf(
                                        AdminTableColumn("Ruta", 1.4f),
                                        AdminTableColumn("Tipo", 0.6f),
                                        AdminTableColumn("Motivo", 1f),
                                    ),
                                    rows = audit.value.unlinkedResources.map { resource ->
                                        AdminTableRow(
                                            key = resource.id,
                                            cells = listOf(
                                                resource.path,
                                                resource.type.name.lowercase(),
                                                resource.reason.name.lowercase(),
                                            ),
                                            supporting = resource.suggestedQuestionId ?: "Sin sugerencia",
                                        )
                                    },
                                )
                            }
                        }
                        is UiAsync.Error -> Text(audit.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Auditando recursos...")
                        else -> Text("Selecciona un examen para auditar recursos.")
                    }
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedQuestionId == null) "Nueva pregunta" else "Editar pregunta",
                description = "Cada pregunta requiere 4 opciones, una correcta por ID y al menos un recurso.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassActionButton(
                        text = "Nueva pregunta",
                        onClick = { viewModel.dispatch(ExamsIntent.CreateQuestion) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EditorField(
                        label = "Titulo de la pregunta",
                        value = state.editorQuestionTitle,
                        onValueChange = { viewModel.dispatch(ExamsIntent.UpdateQuestionTitle(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Pregunta 1",
                    )
                    EditorField(
                        label = "Enunciado",
                        value = state.editorQuestionStatement,
                        onValueChange = { viewModel.dispatch(ExamsIntent.UpdateQuestionStatement(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Introduce el enunciado completo",
                        singleLine = false,
                    )
                    state.editorOptions.sortedBy { it.sortOrder }.forEach { option ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(if (option.isCorrect) "correcta" else "opcion ${option.label.lowercase()}")
                            EditorField(
                                label = "Opcion ${option.label}",
                                value = option.content,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateOptionContent(option.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "Contenido de la opcion ${option.label}",
                            )
                            GlassActionButton(
                                text = "Marcar ${option.label} como correcta",
                                onClick = { viewModel.dispatch(ExamsIntent.SelectCorrectOption(option.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    state.editorResources.sortedBy { it.sortOrder }.forEach { resource ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(resource.type)
                            EditorField(
                                label = "Tipo recurso",
                                value = resource.type,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateResourceType(resource.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "image | audio | video | text",
                            )
                            EditorField(
                                label = "Titulo recurso",
                                value = resource.title,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateResourceTitle(resource.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "Nombre visible del recurso",
                            )
                            EditorField(
                                label = if (resource.type == "text") "Contenido texto" else "Ruta local o URL",
                                value = resource.inputValue,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateResourceValue(resource.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = if (resource.type == "text") {
                                    "Explicacion, notas o transcripcion"
                                } else {
                                    "C:\\ruta\\archivo.png o https://..."
                                },
                                singleLine = resource.type != "text",
                            )
                            if (resource.type != "text") {
                                GlassActionButton(
                                    text = "Subir recurso",
                                    onClick = { viewModel.dispatch(ExamsIntent.UploadResource(resource.id)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            GlassActionButton(
                                text = "Quitar recurso",
                                onClick = { viewModel.dispatch(ExamsIntent.RemoveResource(resource.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    GlassActionButton(
                        text = "Anadir recurso",
                        onClick = { viewModel.dispatch(ExamsIntent.AddResource) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassActionButton(
                            text = "Guardar pregunta",
                            onClick = { viewModel.dispatch(ExamsIntent.SaveQuestion) },
                            modifier = Modifier.weight(1f),
                        )
                        GlassActionButton(
                            text = "Subir",
                            onClick = { state.selectedQuestionId?.let { viewModel.dispatch(ExamsIntent.MoveQuestionUp(it)) } },
                            modifier = Modifier.weight(1f),
                        )
                        GlassActionButton(
                            text = "Bajar",
                            onClick = { state.selectedQuestionId?.let { viewModel.dispatch(ExamsIntent.MoveQuestionDown(it)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.selectedQuestionId != null) {
                        GlassActionButton(
                            text = "Eliminar pregunta",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDeleteQuestion = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when (val uploadState = state.uploadState) {
                        is UiAsync.Error -> Text(uploadState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Subiendo recurso...")
                        is UiAsync.Success -> Text("Recurso listo: ${uploadState.value}")
                        else -> Unit
                    }
                    FormFeedback(state.actionState, successText = "Pregunta sincronizada.")
                }
            }
        },
    )

    if (confirmDeleteQuestion) {
        ConfirmDialog(
            title = "Eliminar pregunta",
            message = "Se eliminará la pregunta seleccionada y sus recursos. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDeleteQuestion = false
                viewModel.dispatch(ExamsIntent.DeleteSelectedQuestion)
            },
            onDismiss = { confirmDeleteQuestion = false },
        )
    }
}

@Composable
private fun SpecialtySelector(
    specialtiesState: UiAsync<List<Specialty>>,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    when (specialtiesState) {
        is UiAsync.Success -> {
            val selected = specialtiesState.value.firstOrNull { it.id == selectedId }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Especialidad",
                    style = MaterialTheme.typography.labelMedium,
                )
                GlassTextField(
                    value = selected?.name.orEmpty(),
                    onValueChange = { typedValue ->
                        specialtiesState.value.firstOrNull { it.name.equals(typedValue, ignoreCase = true) }?.let {
                            onSelected(it.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Escribe el nombre exacto de la especialidad",
                )
                Text(
                    text = specialtiesState.value.joinToString(prefix = "Disponibles: ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UiAsync.Error -> Text(specialtiesState.error.message, color = MaterialTheme.colorScheme.error)
        UiAsync.Loading -> Text("Cargando especialidades...")
        else -> Text("Sin especialidades")
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    singleLine: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = singleLine,
        )
    }
}
