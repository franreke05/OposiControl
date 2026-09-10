package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ButtonVariant
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionType

@Composable
fun QuestionsScreen(viewModel: QuestionsViewModel) {
    val state by viewModel.state.collectAsState()
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = { QuestionBankPane(state, viewModel) },
        detailPane = { QuestionEditorPane(state, viewModel) },
    )

    if (state.showCancelConfirm) {
        ConfirmDialog(
            title = "Descartar cambios",
            message = "Los cambios no guardados se perderán.",
            confirmLabel = "Descartar",
            onConfirm = { viewModel.dispatch(QuestionsIntent.ConfirmCancel) },
            onDismiss = { viewModel.dispatch(QuestionsIntent.DismissCancelConfirm) },
        )
    }
    if (state.showDeleteConfirm) {
        ConfirmDialog(
            title = "Eliminar pregunta",
            message = "Esta acción afectará al banco de preguntas y puede afectar a los exámenes donde está asociada.",
            confirmLabel = "Eliminar",
            onConfirm = { viewModel.dispatch(QuestionsIntent.ConfirmDelete) },
            onDismiss = { viewModel.dispatch(QuestionsIntent.DismissDeleteConfirm) },
        )
    }
    if (state.showAssociateDialog) {
        AssociateExamDialog(state, viewModel)
    }
}

// ------------------------------------------------------------------ IZQUIERDA

@Composable
private fun QuestionBankPane(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Banco de preguntas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)

        FlatSearchField(
            value = state.searchQuery,
            onValueChange = { viewModel.dispatch(QuestionsIntent.Search(it)) },
            placeholder = "Buscar por ID o enunciado...",
        )

        val exams = (state.exams as? UiAsync.Success)?.value.orEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatDropdown(
                label = "Tipo",
                selectedLabel = state.typeFilter?.let { typeLabel(it) } ?: "Todos",
                options = listOf<Pair<QuestionType?, String>>(null to "Todos") + QuestionType.entries.map { it to typeLabel(it) },
                onSelect = { viewModel.dispatch(QuestionsIntent.SetTypeFilter(it)) },
                modifier = Modifier.weight(1f),
            )
            FlatDropdown(
                label = "Tema",
                selectedLabel = "Todos",
                options = listOf<Pair<Unit, String>>(Unit to "Todos"),
                onSelect = {},
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatDropdown(
                label = "Examen",
                selectedLabel = exams.firstOrNull { it.id == state.examFilter }?.let { "${it.code.ifBlank { it.id.take(8) }}" } ?: "Todos",
                options = listOf<Pair<String?, String>>(null to "Todos") + exams.map { it.id to examOptionLabel(it) },
                onSelect = { viewModel.dispatch(QuestionsIntent.SetExamFilter(it)) },
                modifier = Modifier.weight(1f),
            )
            FlatDropdown(
                label = "Estado",
                selectedLabel = when (state.activeFilter) {
                    true -> "Activa"
                    false -> "Inactiva"
                    null -> "Todos"
                },
                options = listOf<Pair<Boolean?, String>>(null to "Todos", true to "Activa", false to "Inactiva"),
                onSelect = { viewModel.dispatch(QuestionsIntent.SetActiveFilter(it)) },
                modifier = Modifier.weight(1f),
            )
        }

        val filteredCount = state.filteredQuestions().size
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$filteredCount ${if (filteredCount == 1) "pregunta encontrada" else "preguntas encontradas"}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            FlatDropdown(
                label = "",
                selectedLabel = if (state.sort == QuestionsSort.MOST_RECENT) "Más recientes" else "Por ID",
                options = listOf(QuestionsSort.MOST_RECENT to "Más recientes", QuestionsSort.BY_CODE to "Por ID"),
                onSelect = { viewModel.dispatch(QuestionsIntent.SetSort(it)) },
                modifier = Modifier.width(160.dp),
            )
        }

        AsyncStatePane(
            state = state.questions,
            emptyTitle = "No hay preguntas todavía",
            emptyMessage = "Puedes crear una pregunta nueva o importar contenido desde Importación.",
            onRetry = { viewModel.dispatch(QuestionsIntent.Refresh) },
            emptyAction = { PrimaryButton(text = "Crear primera pregunta", onClick = { viewModel.dispatch(QuestionsIntent.StartCreate) }) },
        ) {
            val paged = state.pagedQuestions()
            if (paged.isEmpty()) {
                Text("Sin resultados con los filtros actuales.", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    paged.forEach { item ->
                        QuestionRow(
                            item = item,
                            selected = item.id == state.selectedQuestionId,
                            onClick = { viewModel.dispatch(QuestionsIntent.SelectQuestion(item.id)) },
                        )
                    }
                }
            }
        }

        if (state.filteredQuestions().size > state.pageSize) {
            PaginationRow(state, viewModel)
        }
    }
}

@Composable
private fun QuestionRow(item: QuestionListItem, selected: Boolean, onClick: () -> Unit) {
    val tokens = GlassTheme.tokens
    val background = if (selected) tokens.surfaceSelected else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) tokens.accent else androidx.compose.ui.graphics.Color.Transparent),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.code.ifBlank { item.id.take(8) },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = tokens.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.active) "Activa" else "Inactiva",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.active) tokens.success else tokens.textSecondary,
                )
            }
            Text(
                text = item.statement,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.type?.let(::typeLabel) ?: "Tipo desconocido",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun PaginationRow(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    val totalPages = state.totalPages()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DesktopIconButton(
                label = "Página anterior",
                onClick = { viewModel.dispatch(QuestionsIntent.SetPageIndex((state.pageIndex - 1).coerceAtLeast(0))) },
                enabled = state.pageIndex > 0,
                size = 28.dp,
            ) { AppIconImage(AppIcon.CHEVRON_LEFT, size = 12.dp) }
            Text(
                "${state.pageIndex + 1} / $totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            DesktopIconButton(
                label = "Página siguiente",
                onClick = { viewModel.dispatch(QuestionsIntent.SetPageIndex((state.pageIndex + 1).coerceAtMost(totalPages - 1))) },
                enabled = state.pageIndex < totalPages - 1,
                size = 28.dp,
            ) { AppIconImage(AppIcon.CHEVRON_RIGHT, size = 12.dp) }
        }
        FlatDropdown(
            label = "",
            selectedLabel = "${state.pageSize} por página",
            options = listOf(10, 20, 50).map { it to "$it por página" },
            onSelect = { viewModel.dispatch(QuestionsIntent.SetPageSize(it)) },
            modifier = Modifier.width(150.dp),
        )
    }
}

private fun typeLabel(type: QuestionType) = when (type) {
    QuestionType.RAFAGA -> "Ráfaga"
    QuestionType.RAZONADA -> "Razonada"
    QuestionType.INMERSIVA -> "Inmersiva"
}

private fun examOptionLabel(exam: Exam) = exam.code.ifBlank { exam.id.take(8) } + " · " + exam.title

// -------------------------------------------------------------------- DERECHA

@Composable
private fun QuestionEditorPane(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    if (!state.isCreating && state.selectedQuestionId == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Selecciona una pregunta del banco o crea una nueva.",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textSecondary,
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EditorHeader(state, viewModel)

        if (!state.isCreating) {
            AsyncStatePane(state = state.detail, onRetry = { state.selectedQuestionId?.let { viewModel.dispatch(QuestionsIntent.SelectQuestion(it)) } }) {
                EditorSections(state, viewModel)
            }
        } else {
            EditorSections(state, viewModel)
        }
    }
}

@Composable
private fun EditorHeader(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    val detail = (state.detail as? UiAsync.Success<QuestionDetail>)?.value
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (state.isCreating) "Nueva pregunta" else detail?.code?.ifBlank { state.selectedQuestionId.orEmpty().take(8) } ?: "Cargando...",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                )
                if (!state.isCreating && detail != null) {
                    StatusChip(status = if (state.draftActive) "Activa" else "Inactiva")
                }
            }
            Text(
                text = typeLabel(state.draftType),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.isCreating && state.selectedQuestionId != null) {
                DesktopIconButton(
                    label = "Eliminar pregunta",
                    onClick = { viewModel.dispatch(QuestionsIntent.RequestDelete) },
                    variant = ButtonVariant.DANGER,
                ) { AppIconImage(AppIcon.DELETE, size = 16.dp) }
            }
            SecondaryButton(text = "Cancelar", onClick = { viewModel.dispatch(QuestionsIntent.RequestCancel) })
            PrimaryButton(
                text = "Guardar cambios",
                onClick = { viewModel.dispatch(QuestionsIntent.Save) },
                enabled = (state.dirty || state.isCreating) && state.isDraftValid(),
                loading = state.saveState is UiAsync.Loading,
            )
        }
    }
    if (state.saveState is UiAsync.Error) {
        Text(
            text = (state.saveState as UiAsync.Error).error.message,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.danger,
        )
    }
}

@Composable
private fun EditorSections(state: QuestionsState, viewModel: QuestionsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IdentificationSection(state, viewModel)
        ContentSection(state, viewModel)
        ClassificationSection()
        ExamLinksSection(state, viewModel)
        if (state.draftType != QuestionType.RAFAGA) {
            ExplanationSourceSection(state, viewModel)
        }
        if (state.draftType == QuestionType.INMERSIVA) {
            ResourcesSection(state)
        }
        MetadataSection(state)
    }
}

@Composable
private fun IdentificationSection(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    QuestionSectionCard(number = 1, title = "Identificación") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ID global", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                FlatTextField(
                    value = if (state.isCreating) "Se genera al guardar" else ((state.detail as? UiAsync.Success)?.value?.code?.ifBlank { "—" } ?: "—"),
                    onValueChange = {},
                    enabled = false,
                )
            }
            FlatDropdown(
                label = "Tipo de pregunta",
                selectedLabel = typeLabel(state.draftType),
                options = QuestionType.entries.map { it to typeLabel(it) },
                onSelect = { viewModel.dispatch(QuestionsIntent.UpdateType(it)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.isCreating) {
            val exams = (state.exams as? UiAsync.Success)?.value.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlatDropdown(
                    label = "Examen inicial (obligatorio — el backend exige especialidad)",
                    selectedLabel = exams.firstOrNull { it.id == state.draftCreateExamId }?.let { examOptionLabel(it) } ?: "Selecciona un examen",
                    options = exams.map { it.id to examOptionLabel(it) },
                    onSelect = { viewModel.dispatch(QuestionsIntent.UpdateCreateExamId(it)) },
                )
            }
        }
    }
}

@Composable
private fun ContentSection(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    QuestionSectionCard(number = 2, title = "Contenido") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Enunciado", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                Text("${state.draftStatement.length}/2000", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
            }
            FlatTextField(
                value = state.draftStatement,
                onValueChange = { viewModel.dispatch(QuestionsIntent.UpdateStatement(it)) },
                placeholder = "Introduce el enunciado completo",
                singleLine = false,
                minHeight = 72.dp,
            )
        }
        val rows = state.draftOptions.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { rowOptions ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    rowOptions.forEach { option ->
                        OptionEditorCard(
                            option = option,
                            onContentChange = { viewModel.dispatch(QuestionsIntent.UpdateOptionContent(option.label, it)) },
                            onSelectCorrect = { viewModel.dispatch(QuestionsIntent.SetCorrectOption(option.label)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        val correct = state.draftOptions.firstOrNull { it.isCorrect }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatDropdown(
                label = "Respuesta correcta",
                selectedLabel = correct?.label ?: "—",
                options = state.draftOptions.map { it.label to it.label },
                onSelect = { viewModel.dispatch(QuestionsIntent.SetCorrectOption(it)) },
                modifier = Modifier.width(160.dp),
            )
            if (correct != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.success.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconImage(AppIcon.CHECK, size = 13.dp, tint = tokens.success)
                    Text("La respuesta correcta es la opción ${correct.label}.", style = MaterialTheme.typography.bodySmall, color = tokens.success)
                }
            }
        }
    }
}

@Composable
private fun ClassificationSection() {
    val tokens = GlassTheme.tokens
    QuestionSectionCard(number = 3, title = "Clasificación") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tema", style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
                Text("No disponible", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
            }
            Text(
                "Esta información todavía no está disponible.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun ExamLinksSection(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    QuestionSectionCard(
        number = 4,
        title = "Exámenes asociados",
        action = {
            SecondaryButton(
                text = "Asociar a examen",
                onClick = { viewModel.dispatch(QuestionsIntent.RequestAssociateExam) },
                size = ButtonSize.COMPACT,
                enabled = !state.isCreating,
                icon = { AppIconImage(AppIcon.ADD, size = 13.dp) },
            )
        },
    ) {
        Text(
            "No se puede consultar todavía el historial completo de asociaciones. Las nuevas asociaciones realizadas desde aquí sí aparecerán en esta lista.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        if (state.examLinks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ID examen", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, modifier = Modifier.weight(0.8f))
                    Text("Nombre del examen", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, modifier = Modifier.weight(1.6f))
                    Text("Posición", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, modifier = Modifier.weight(0.6f))
                    Spacer(modifier = Modifier.width(28.dp))
                }
                state.examLinks.forEach { link ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(link.examCode.ifBlank { link.examId.take(8) }, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, modifier = Modifier.weight(0.8f))
                        Text(link.examTitle, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, modifier = Modifier.weight(1.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(link.position.toString(), style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, modifier = Modifier.weight(0.6f))
                        DesktopIconButton(
                            label = "Quitar de este examen (no disponible todavía)",
                            onClick = {},
                            enabled = false,
                            size = 26.dp,
                        ) { AppIconImage(AppIcon.DELETE, size = 13.dp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplanationSourceSection(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    QuestionSectionCard(number = 5, title = "Explicación y fuente") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Explicación", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            FlatTextField(
                value = state.draftExplanation,
                onValueChange = { viewModel.dispatch(QuestionsIntent.UpdateExplanation(it)) },
                placeholder = "Justificación mostrada al usuario tras responder",
                singleLine = false,
                minHeight = 56.dp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Fuente", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            FlatTextField(
                value = state.draftSource,
                onValueChange = { viewModel.dispatch(QuestionsIntent.UpdateSource(it)) },
                placeholder = "Referencia normativa, bibliográfica o documental",
            )
        }
    }
}

@Composable
private fun ResourcesSection(state: QuestionsState) {
    val tokens = GlassTheme.tokens
    val detail = (state.detail as? UiAsync.Success<QuestionDetail>)?.value
    QuestionSectionCard(
        number = 6,
        title = "Recursos",
        action = {
            SecondaryButton(
                text = "Asociar recurso existente",
                onClick = {},
                size = ButtonSize.COMPACT,
                enabled = false,
                icon = { AppIconImage(AppIcon.ADD, size = 13.dp) },
            )
        },
    ) {
        Text(
            "Buscar un recurso existente todavía no está disponible.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        val resources = detail?.resources.orEmpty()
        if (resources.isEmpty()) {
            Text("Sin recursos vinculados.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                resources.forEach { resource ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(resource.title.ifBlank { resource.id.take(8) }, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
                            Text(resource.type.name.lowercase(), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(state: QuestionsState) {
    val tokens = GlassTheme.tokens
    var expanded by remember(state.selectedQuestionId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppIconImage(
                icon = if (expanded) AppIcon.CHEVRON_DOWN else AppIcon.CHEVRON_RIGHT,
                size = 11.dp,
                tint = tokens.textSecondary,
            )
            Text("Información del sistema", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(top = 8.dp, start = 17.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Creada: No disponible", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                Text("Actualizada: No disponible", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
    }
}

@Composable
private fun AssociateExamDialog(state: QuestionsState, viewModel: QuestionsViewModel) {
    val tokens = GlassTheme.tokens
    val exams = (state.exams as? UiAsync.Success)?.value.orEmpty()
    androidx.compose.ui.window.Dialog(onDismissRequest = { viewModel.dispatch(QuestionsIntent.CancelAssociateDialog) }) {
        FlatCard {
            Column(modifier = Modifier.padding(20.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Asociar a examen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                FlatDropdown(
                    label = "Examen",
                    selectedLabel = exams.firstOrNull { it.id == state.associateExamId }?.let { examOptionLabel(it) } ?: "Selecciona un examen",
                    options = exams.map { it.id to examOptionLabel(it) },
                    onSelect = { viewModel.dispatch(QuestionsIntent.UpdateAssociateExamId(it)) },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Posición", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                    FlatTextField(
                        value = state.associatePosition,
                        onValueChange = { viewModel.dispatch(QuestionsIntent.UpdateAssociatePosition(it.filter { c -> c.isDigit() })) },
                        placeholder = "1",
                    )
                }
                if (state.saveState is UiAsync.Error) {
                    Text((state.saveState as UiAsync.Error).error.message, style = MaterialTheme.typography.bodySmall, color = tokens.danger)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SecondaryButton(text = "Cancelar", onClick = { viewModel.dispatch(QuestionsIntent.CancelAssociateDialog) })
                    Spacer(modifier = Modifier.width(8.dp))
                    PrimaryButton(
                        text = "Asociar",
                        onClick = { viewModel.dispatch(QuestionsIntent.ConfirmAssociateExam) },
                        loading = state.saveState is UiAsync.Loading,
                    )
                }
            }
        }
    }
}
