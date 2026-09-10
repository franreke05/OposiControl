package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam

@Composable
fun ImportBatchDetail(state: ImportState, viewModel: ImportViewModel) {
    val tokens = GlassTheme.tokens

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column {
            Text("Revisión de lote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tokens.textPrimary)
            val batch = (state.activeBatch as? UiAsync.Success)?.value
            Text(batch?.fileName ?: "Cargando...", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
        }
        SecondaryButton(text = "Cerrar", onClick = { viewModel.dispatch(ImportIntent.CloseBatch) })
    }

    AsyncStatePane(state = state.activeBatch, onRetry = { (state.activeBatch as? UiAsync.Error)?.let { } }) { batch ->
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            WizardStepsRow(batch)

            val metrics = batch.metrics()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Detectadas", "${metrics.detected}", modifier = Modifier.weight(1f))
                MetricTile("Listas", "${metrics.ready}", modifier = Modifier.weight(1f), tint = tokens.success)
                MetricTile("Revisión", "${metrics.review}", modifier = Modifier.weight(1f), tint = tokens.warning)
                MetricTile("Errores", "${metrics.errors}", modifier = Modifier.weight(1f), tint = tokens.danger)
                MetricTile("Duplicadas", "${metrics.duplicates}", modifier = Modifier.weight(1f), tint = tokens.info)
            }

            ReviewToolbar(state, batch, viewModel)

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                StagingTable(state, viewModel, modifier = Modifier.weight(if (state.editDraft != null) 1.6f else 1f))
                if (state.editDraft != null) {
                    StagingEditor(state, viewModel, modifier = Modifier.weight(1f))
                }
            }

            PublishFooter(state, batch, viewModel)
        }
    }
}

@Composable
private fun WizardStepsRow(batch: ContentImportBatch) {
    val hasReviewedAll = batch.staging.isNotEmpty() && batch.staging.none { stagingTone(it) == ReviewFilter.ADVERTENCIA }
    val published = batch.status.equals("publicado", ignoreCase = true) || batch.status.equals("parcial", ignoreCase = true)
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.fillMaxWidth()) {
        WizardStep(1, "Archivo", "Subido", WizardStepState.COMPLETED, modifier = Modifier.weight(1f))
        WizardStep(2, "Procesado", "${batch.detectedCount} detectadas", WizardStepState.COMPLETED, modifier = Modifier.weight(1f))
        WizardStep(
            3,
            "Revisión",
            if (published) "Completada" else "En curso",
            if (published) WizardStepState.COMPLETED else WizardStepState.ACTIVE,
            modifier = Modifier.weight(1f),
        )
        WizardStep(
            4,
            "Publicación",
            if (published) batchStatusLabel(batch.status) else "Pendiente",
            when {
                batch.status.equals("publicado", ignoreCase = true) -> WizardStepState.COMPLETED
                batch.status.equals("parcial", ignoreCase = true) -> WizardStepState.ERROR
                else -> WizardStepState.PENDING
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReviewToolbar(state: ImportState, batch: ContentImportBatch, viewModel: ImportViewModel) {
    val tokens = GlassTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ImportTextField(
            value = state.reviewSearch,
            onValueChange = { viewModel.dispatch(ImportIntent.SetReviewSearch(it)) },
            placeholder = "Buscar por ID o texto...",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val counts = batch.staging.groupingBy { stagingTone(it) }.eachCount()
            FilterChip("Todos", batch.staging.size, state.reviewFilter == ReviewFilter.ALL, { viewModel.dispatch(ImportIntent.SetReviewFilter(ReviewFilter.ALL)) }, tokens.textPrimary)
            FilterChip("Listas", counts[ReviewFilter.LISTA] ?: 0, state.reviewFilter == ReviewFilter.LISTA, { viewModel.dispatch(ImportIntent.SetReviewFilter(ReviewFilter.LISTA)) }, tokens.success)
            FilterChip("Advertencias", counts[ReviewFilter.ADVERTENCIA] ?: 0, state.reviewFilter == ReviewFilter.ADVERTENCIA, { viewModel.dispatch(ImportIntent.SetReviewFilter(ReviewFilter.ADVERTENCIA)) }, tokens.warning)
            FilterChip("Errores", counts[ReviewFilter.ERROR] ?: 0, state.reviewFilter == ReviewFilter.ERROR, { viewModel.dispatch(ImportIntent.SetReviewFilter(ReviewFilter.ERROR)) }, tokens.danger)
            FilterChip("Duplicadas", counts[ReviewFilter.DUPLICADA] ?: 0, state.reviewFilter == ReviewFilter.DUPLICADA, { viewModel.dispatch(ImportIntent.SetReviewFilter(ReviewFilter.DUPLICADA)) }, tokens.info)
        }
    }
}

@Composable
private fun StagingTable(state: ImportState, viewModel: ImportViewModel, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val rows = state.visibleStaging()
    val exams = (state.exams as? UiAsync.Success)?.value.orEmpty()
    val examLabelFor = (state.activeBatch as? UiAsync.Success)?.value?.examId?.let { id -> exams.firstOrNull { it.id == id } }
    ImportCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("#" to 0.3f, "Enunciado" to 2.2f, "Tema" to 0.8f, "Correcta" to 0.6f, "Estado" to 0.9f).forEach { (label, weight) ->
                    Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary, modifier = Modifier.weight(weight))
                }
            }
            if (rows.isEmpty()) {
                Text("Sin filas con este filtro.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height((rows.size.coerceAtMost(12) * 52).dp)) {
                    items(rows, key = { it.id }) { item ->
                        StagingRow(item, selected = item.id == state.selectedStagingId, onClick = { viewModel.dispatch(ImportIntent.SelectStaging(item.id)) })
                    }
                }
            }
        }
    }
    if (examLabelFor != null) {
        Text("Examen destino del lote: ${examLabelFor.code.ifBlank { examLabelFor.id.take(8) }} · ${examLabelFor.title}", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StagingRow(item: ContentImportStagingItem, selected: Boolean, onClick: () -> Unit) {
    val tokens = GlassTheme.tokens
    val toneLabel = when (stagingTone(item)) {
        ReviewFilter.LISTA -> "Lista"
        ReviewFilter.ADVERTENCIA -> "Advertencia"
        ReviewFilter.ERROR -> "Error"
        ReviewFilter.DUPLICADA -> "Duplicada"
        ReviewFilter.ALL -> "—"
    }
    val background = if (selected) tokens.surfaceSelected else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(0.3f)) { Text("${item.position}", style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary) }
        Box(modifier = Modifier.weight(2.2f)) {
            Text(item.title.ifBlank { "(sin enunciado)" }, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(modifier = Modifier.weight(0.8f)) { Text(item.tagText.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Box(modifier = Modifier.weight(0.6f)) { Text(item.correctAnswer.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary) }
        Box(modifier = Modifier.weight(0.9f)) { StatusChip(status = toneLabel) }
    }
}

@Composable
private fun StagingEditor(state: ImportState, viewModel: ImportViewModel, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val draft = state.editDraft ?: return
    ImportCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pregunta ${draft.position}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                DesktopIconButton(label = "Cerrar", onClick = { viewModel.dispatch(ImportIntent.SelectStaging(null)) }, size = 22.dp) {
                    AppIconImage(AppIcon.CLOSE, size = 12.dp)
                }
            }
            Text(
                "Los cambios se aplican al lote en revisión. Todavía no modifican contenido publicado.",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
            draft.parseError?.takeIf { it.isNotBlank() }?.let { error ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(tokens.danger.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIconImage(AppIcon.WARNING, size = 14.dp, tint = tokens.danger)
                    Text(error, style = MaterialTheme.typography.labelSmall, color = tokens.danger)
                }
            }
            ImportTextField(value = draft.title, onValueChange = { viewModel.dispatch(ImportIntent.UpdateEditDraft(draft.copy(title = it))) }, placeholder = "Enunciado", singleLine = false, minHeight = 56.dp)
            listOf(
                "A" to draft.optionA, "B" to draft.optionB, "C" to draft.optionC, "D" to draft.optionD,
            ).forEach { (label, value) ->
                ImportTextField(
                    value = value,
                    onValueChange = { new ->
                        val updated = when (label) {
                            "A" -> draft.copy(optionA = new)
                            "B" -> draft.copy(optionB = new)
                            "C" -> draft.copy(optionC = new)
                            else -> draft.copy(optionD = new)
                        }
                        viewModel.dispatch(ImportIntent.UpdateEditDraft(updated))
                    },
                    placeholder = "Opción $label",
                )
            }
            ImportDropdown(
                label = "Respuesta correcta",
                selectedLabel = draft.correctAnswer.ifBlank { "—" },
                options = listOf("A" to "A", "B" to "B", "C" to "C", "D" to "D"),
                onSelect = { viewModel.dispatch(ImportIntent.UpdateEditDraft(draft.copy(correctAnswer = it))) },
            )
            ImportTextField(value = draft.tagText, onValueChange = { viewModel.dispatch(ImportIntent.UpdateEditDraft(draft.copy(tagText = it))) }, placeholder = "Tema (texto libre)")
            ImportTextField(value = draft.reasoning, onValueChange = { viewModel.dispatch(ImportIntent.UpdateEditDraft(draft.copy(reasoning = it))) }, placeholder = "Explicación", singleLine = false, minHeight = 48.dp)
            ImportTextField(value = draft.sourceText, onValueChange = { viewModel.dispatch(ImportIntent.UpdateEditDraft(draft.copy(sourceText = it))) }, placeholder = "Fuente")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(text = "Omitir", onClick = { viewModel.dispatch(ImportIntent.SkipStaging(draft)) }, size = ButtonSize.COMPACT)
                SecondaryButton(text = "Aprobar", onClick = { viewModel.dispatch(ImportIntent.ApproveStaging(draft)) }, size = ButtonSize.COMPACT)
                PrimaryButton(text = "Guardar", onClick = { viewModel.dispatch(ImportIntent.SaveEditDraft) }, size = ButtonSize.COMPACT, loading = state.actionState is UiAsync.Loading)
            }
        }
    }
}

@Composable
private fun PublishFooter(state: ImportState, batch: ContentImportBatch, viewModel: ImportViewModel) {
    val tokens = GlassTheme.tokens
    val metrics = batch.metrics()
    ImportCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("${metrics.detected} detectadas · ${metrics.ready} publicables · ${metrics.errors} errores", style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
                    if (state.actionState is UiAsync.Success) {
                        Text((state.actionState as UiAsync.Success).value, style = MaterialTheme.typography.labelSmall, color = tokens.success)
                    }
                    if (state.actionState is UiAsync.Error) {
                        Text((state.actionState as UiAsync.Error).error.message, style = MaterialTheme.typography.labelSmall, color = tokens.danger)
                    }
                }
                PrimaryButton(
                    text = "Publicar lote",
                    onClick = { viewModel.dispatch(ImportIntent.PublishBatch) },
                    enabled = metrics.errors == 0 && batch.staging.isNotEmpty(),
                    loading = state.actionState is UiAsync.Loading,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().background(tokens.warning.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIconImage(AppIcon.WARNING, size = 14.dp, tint = tokens.warning)
                Text(
                    "La publicación segura y atómica de este lote todavía no está disponible: un fallo a mitad de proceso puede dejar contenido parcial. Revisa el resultado tras publicar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.warning,
                )
            }
        }
    }
}
