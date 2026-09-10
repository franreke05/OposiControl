package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam

@Composable
fun ImportScreen(viewModel: ImportViewModel) {
    val state by viewModel.state.collectAsState()
    val pickPdf = rememberPdfPickerLauncher { picked -> viewModel.dispatch(ImportIntent.PdfPicked(picked)) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.activeBatch is UiAsync.Idle) {
            ImportHome(state, viewModel, pickPdf)
        } else {
            ImportBatchDetail(state, viewModel)
        }
    }

    if (state.showHowItWorks) {
        HowItWorksDialog(onDismiss = { viewModel.dispatch(ImportIntent.ToggleHowItWorks) })
    }
}

// ============================================================ HOME

@Composable
private fun ImportHome(state: ImportState, viewModel: ImportViewModel, pickPdf: () -> Unit) {
    val tokens = GlassTheme.tokens

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Importar preguntas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = tokens.textPrimary)
            Text(
                "Sube un PDF con preguntas y OposiControl las analizará, validará y te permitirá revisarlas antes de publicarlas.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
            )
        }
        SecondaryButton(
            text = "¿Cómo funciona?",
            onClick = { viewModel.dispatch(ImportIntent.ToggleHowItWorks) },
            icon = { AppIconImage(AppIcon.HELP, size = 14.dp) },
        )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ImportSectionTitle("1. Selecciona el tipo de importación")
            Text(
                "Cada tipo de pregunta tiene una plantilla específica. Usa siempre nuestras plantillas para un resultado óptimo.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TypeCard(
                    type = ContentExamType.RAFAGA,
                    icon = AppIcon.QUESTIONS,
                    subtitle = "Preguntas rápidas",
                    description = "Preguntas tipo test sin explicación ni recursos multimedia.",
                    state = state,
                    viewModel = viewModel,
                    pickPdf = pickPdf,
                    modifier = Modifier.weight(1f),
                )
                TypeCard(
                    type = ContentExamType.RAZONADO,
                    icon = AppIcon.IMPORT,
                    subtitle = "Con explicación",
                    description = "Incluye explicación y fuente bibliográfica.",
                    state = state,
                    viewModel = viewModel,
                    pickPdf = pickPdf,
                    modifier = Modifier.weight(1f),
                )
                TypeCard(
                    type = ContentExamType.INMERSIVO,
                    icon = AppIcon.QUESTIONS,
                    subtitle = "Con recursos",
                    description = "Audio/vídeo/imagen previamente subidos.",
                    state = state,
                    viewModel = viewModel,
                    pickPdf = pickPdf,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.actionState is UiAsync.Error) {
                Text((state.actionState as UiAsync.Error).error.message, style = MaterialTheme.typography.bodySmall, color = tokens.danger)
            }
            if (state.actionState is UiAsync.Loading) {
                Text("Subiendo y analizando el PDF...", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        CodesPanel(modifier = Modifier.weight(1f))
    }

    HowItWorksPanel()

    RecentImportsSection(state, viewModel)

    ImportCard {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("¿Necesitas ayuda?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                Text(
                    "Descarga las plantillas, revisa el formato correcto y consulta ejemplos en la documentación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun TypeCard(
    type: ContentExamType,
    icon: AppIcon,
    subtitle: String,
    description: String,
    state: ImportState,
    viewModel: ImportViewModel,
    pickPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val selected = state.selectedType == type
    ImportCard(
        modifier = modifier.clickable { viewModel.dispatch(ImportIntent.SelectType(type)) },
        strokeColor = if (selected) tokens.accent else tokens.border,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) tokens.accent.copy(alpha = 0.15f) else tokens.surfaceMuted)
                        .padding(8.dp),
                ) {
                    AppIconImage(icon, size = 18.dp, tint = if (selected) tokens.accentInk else tokens.textSecondary)
                }
                if (selected) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(tokens.accent).padding(4.dp)) {
                        AppIconImage(AppIcon.CHECK, size = 11.dp, tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            Text(type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)

            if (selected) {
                ImportDropdown(
                    label = "Examen destino",
                    selectedLabel = state.examsForSelectedType.firstOrNull { it.id == state.selectedExamId }?.let { examLabel(it) } ?: "Selecciona un examen",
                    options = state.examsForSelectedType.map { it.id to examLabel(it) },
                    onSelect = { viewModel.dispatch(ImportIntent.SelectExam(it)) },
                )
                if (state.pickedPdfName.isNotBlank()) {
                    Text("Archivo: ${state.pickedPdfName}", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (selected) {
                PrimaryButton(
                    text = if (state.pickedPdfName.isBlank()) "Seleccionar PDF" else "Subir y analizar",
                    onClick = {
                        if (state.pickedPdfName.isBlank()) pickPdf() else viewModel.dispatch(ImportIntent.UploadPdf)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.COMPACT,
                    enabled = state.selectedExamId != null,
                    loading = state.actionState is UiAsync.Loading,
                    icon = { AppIconImage(AppIcon.IMPORT, size = 14.dp) },
                )
            } else {
                SecondaryButton(text = "Seleccionar PDF", onClick = { viewModel.dispatch(ImportIntent.SelectType(type)) }, modifier = Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppIconImage(AppIcon.DOWNLOAD, size = 12.dp, tint = tokens.textSecondary.copy(alpha = 0.6f))
                Text("Descargar plantilla (no disponible todavía)", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary.copy(alpha = 0.6f))
            }
        }
    }
}

private fun examLabel(exam: Exam) = exam.code.ifBlank { exam.id.take(8) } + " · " + exam.title

@Composable
private fun CodesPanel(modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    ImportCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Códigos que utilizarás en la plantilla", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            listOf(
                "ID_GLOBAL" to "PREG-000001" to "Identificador único de la pregunta.",
                "ID_EXAMEN" to "EX-0001" to "Código del examen.",
                "TEMA" to "TEMA-001" to "Código del tema.",
                "ID_AUDIO" to "AUD-0001" to "Recurso de audio (solo inmersivas).",
                "ID_VIDEO" to "VID-0001" to "Recurso de vídeo (solo inmersivas).",
                "ID_IMAGEN" to "IMG-0001" to "Recurso de imagen (solo inmersivas).",
            ).forEach { (pair, help) ->
                val (code, example) = pair
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(code, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                        Text(example, style = MaterialTheme.typography.labelMedium, color = tokens.accentInk)
                    }
                    Text(help, style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tokens.info.copy(alpha = 0.1f)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIconImage(AppIcon.INFO, size = 14.dp, tint = tokens.info)
                Text("Usa los códigos que ya existen en OposiControl. No escribas UUID.", style = MaterialTheme.typography.labelSmall, color = tokens.info)
            }
        }
    }
}

@Composable
private fun HowItWorksPanel() {
    val tokens = GlassTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ImportSectionTitle("Cómo funciona")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val steps = listOf(
                Triple("1", "Archivo", "Selecciona el tipo y sube tu PDF."),
                Triple("2", "Procesado", "Analizamos el PDF y validamos los datos de cada pregunta."),
                Triple("3", "Revisión", "Revisa las preguntas, corrige errores si es necesario."),
                Triple("4", "Publicación", "Se crean las preguntas en bloque, todo o nada."),
            )
            steps.forEach { (number, title, body) ->
                ImportCard(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(tokens.accent.copy(alpha = 0.15f)).padding(8.dp),
                        ) {
                            Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tokens.accentInk)
                        }
                        Column {
                            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                            Text(body, style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentImportsSection(state: ImportState, viewModel: ImportViewModel) {
    val tokens = GlassTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ImportSectionTitle("Importaciones recientes")
        AsyncStatePane(
            state = state.batches,
            emptyTitle = "No hay importaciones todavía",
            emptyMessage = "Selecciona una plantilla y sube tu primer PDF.",
            onRetry = { viewModel.dispatch(ImportIntent.Refresh) },
        ) { batches ->
            ImportCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(tokens.surfaceMuted).padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("Archivo" to 2.2f, "Tipo" to 1f, "Detectadas" to 0.8f, "Listas" to 0.8f, "Errores" to 0.8f, "Estado" to 1f).forEach { (label, weight) ->
                            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary, modifier = Modifier.weight(weight))
                        }
                        Spacer(modifier = Modifier.width(90.dp))
                    }
                    batches.forEach { batch ->
                        val metrics = batch.metrics()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(batch.fileName, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, modifier = Modifier.weight(2.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ContentExamType.fromApiValue(batch.examType).label, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary, modifier = Modifier.weight(1f))
                            Text("${metrics.detected}", style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, modifier = Modifier.weight(0.8f))
                            Text("${metrics.ready}", style = MaterialTheme.typography.bodyMedium, color = tokens.success, modifier = Modifier.weight(0.8f))
                            Text("${metrics.errors}", style = MaterialTheme.typography.bodyMedium, color = if (metrics.errors > 0) tokens.danger else tokens.textSecondary, modifier = Modifier.weight(0.8f))
                            Box(modifier = Modifier.weight(1f)) { StatusChip(status = batchStatusLabel(batch.status)) }
                            SecondaryButton(text = "Ver detalle", onClick = { viewModel.dispatch(ImportIntent.OpenBatch(batch.id)) }, size = ButtonSize.COMPACT, modifier = Modifier.width(120.dp))
                        }
                    }
                }
            }
        }
    }
}

internal fun batchStatusLabel(status: String) = when (status.lowercase()) {
    "parcial" -> "Parcial"
    "parseado" -> "En revisión"
    "publicado" -> "Publicado"
    else -> status.ifBlank { "Desconocido" }
}

@Composable
private fun HowItWorksDialog(onDismiss: () -> Unit) {
    val tokens = GlassTheme.tokens
    Dialog(onDismissRequest = onDismiss) {
        ImportCard {
            Column(modifier = Modifier.padding(20.dp).width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Cómo funciona?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                Text(
                    "1. Elige el tipo de pregunta y sube un PDF con texto seleccionable.\n" +
                        "2. Analizamos el archivo y separamos las preguntas listas de las que necesitan revisión.\n" +
                        "3. Revisa y corrige lo necesario — nada se publica todavía.\n" +
                        "4. Publicas el lote de forma explícita, cuando esté listo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton(text = "Entendido", onClick = onDismiss)
                }
            }
        }
    }
}
