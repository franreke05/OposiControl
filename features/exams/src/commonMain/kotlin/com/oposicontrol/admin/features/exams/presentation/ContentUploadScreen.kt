package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.HelpPanel
import com.oposicontrol.admin.core.ui.components.HelpStep
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.Specialty

@Composable
fun ContentUploadScreen(viewModel: ContentUploadViewModel) {
    val state by viewModel.state.collectAsState()
    val pdfPicker = rememberContentFilePickerLauncher(mimeType = "application/pdf") { picked ->
        viewModel.dispatch(ContentUploadIntent.SelectPdf(picked.uri, picked.name, picked.mimeType))
    }
    val activeBatch = (state.activeBatch as? UiAsync.Success)?.value
    var helpOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (GlassTheme.isDesktop) 0.dp else UiSpacing.screenMobile,
                vertical = 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("Subir contenido", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            // La importación es el proceso con más pasos del panel y el único
            // donde equivocarse publica contenido a los usuarios. Es justo el
            // caso de §16 nivel 3: no basta un tooltip, hace falta ver el
            // recorrido entero.
            SecondaryButton(
                text = if (helpOpen) "Ocultar ayuda" else "Cómo funciona",
                onClick = { helpOpen = !helpOpen },
                size = ButtonSize.COMPACT,
                icon = { AppIconImage(AppIcon.HELP, size = 14.dp) },
            )
        }
        Text(
            "Añade exámenes y preguntas con revisión humana antes de llevarlas al contenido canónico.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ImportHelpPanel(visible = helpOpen, onClose = { helpOpen = false })
        FormFeedback(
            state = state.actionState,
            loadingText = "Procesando importación...",
            successText = "Operación completada.",
        )
        if (activeBatch != null) {
            ImportReview(
                batch = activeBatch,
                busy = state.actionState is UiAsync.Loading,
                onBack = { viewModel.dispatch(ContentUploadIntent.CloseBatch) },
                onSave = { viewModel.dispatch(ContentUploadIntent.SaveStaging(it)) },
                onSkip = { viewModel.dispatch(ContentUploadIntent.SkipStaging(it)) },
                onPublish = { viewModel.dispatch(ContentUploadIntent.PublishBatch) },
            )
        } else {
            ImportSetup(
                state = state,
                onIntent = viewModel::dispatch,
                onSelectPdf = { pdfPicker.open() },
            )
            RecentImports(
                batches = state.batches,
                onOpen = { viewModel.dispatch(ContentUploadIntent.OpenBatch(it)) },
                onRefresh = { viewModel.dispatch(ContentUploadIntent.Refresh) },
            )
        }
    }
}

@Composable
private fun ImportSetup(
    state: ContentUploadState,
    onIntent: (ContentUploadIntent) -> Unit,
    onSelectPdf: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Nuevo lote de importación", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("1. Especialidad real", style = MaterialTheme.typography.labelLarge, color = GlassTheme.tokens.accent)
            SelectionChips(
                items = (state.specialties as? UiAsync.Success)?.value.orEmpty(),
                selectedId = state.selectedSpecialtyId,
                label = { it.name },
                id = { it.id },
                onSelected = { onIntent(ContentUploadIntent.SelectSpecialty(it)) },
                emptyText = "No hay especialidades disponibles.",
            )
            Text("2. Examen existente o borrador", style = MaterialTheme.typography.labelLarge, color = GlassTheme.tokens.accent)
            SelectionChips(
                items = (state.exams as? UiAsync.Success)?.value.orEmpty(),
                selectedId = state.selectedExamId,
                label = { "${it.title} · ${it.typeExam}" },
                id = { it.id },
                onSelected = { onIntent(ContentUploadIntent.SelectExam(it)) },
                emptyText = "No hay exámenes; crea un borrador abajo.",
            )
            GlassTextField(
                value = state.draftTitle,
                onValueChange = { onIntent(ContentUploadIntent.UpdateDraftTitle(it)) },
                placeholder = "Nombre del nuevo examen borrador",
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onIntent(ContentUploadIntent.CreateDraft) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Crear borrador") }
            Text("3. Tipo de examen", style = MaterialTheme.typography.labelLarge, color = GlassTheme.tokens.accent)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContentExamType.entries.forEach { type ->
                    Button(
                        onClick = { onIntent(ContentUploadIntent.SelectType(type.apiValue)) },
                        modifier = Modifier.width(112.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(type.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text("4. PDF digital", style = MaterialTheme.typography.labelLarge, color = GlassTheme.tokens.accent)
            Text(
                "Solo se admite texto seleccionable. No se ejecuta OCR y el archivo no publica por sí solo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSelectPdf, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.selectedPdfName.isBlank()) "Seleccionar PDF" else state.selectedPdfName)
            }
            Button(
                onClick = { onIntent(ContentUploadIntent.UploadPdf) },
                enabled = state.selectedExamId != null && state.selectedPdfUri.isNotBlank() && state.actionState !is UiAsync.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Crear lote y procesar en staging") }
        }
    }
}

@Composable
private fun <T> SelectionChips(
    items: List<T>,
    selectedId: String?,
    label: (T) -> String,
    id: (T) -> String,
    onSelected: (String) -> Unit,
    emptyText: String,
) {
    if (items.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Button(
                onClick = { onSelected(id(item)) },
                modifier = Modifier.width(190.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(label(item), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun RecentImports(
    batches: UiAsync<List<ContentImportBatch>>,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Importaciones recientes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRefresh) { Text("Actualizar") }
            }
            when (batches) {
                UiAsync.Empty -> EmptyStateCard("Sin importaciones", "Los lotes creados aparecerán aquí.")
                UiAsync.Loading -> Text("Cargando lotes...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                is UiAsync.Error -> Text(batches.error.message, color = MaterialTheme.colorScheme.error)
                is UiAsync.Success -> batches.value.forEach { batch ->
                    ImportBatchRow(batch = batch, onClick = { onOpen(batch.id) })
                }
                UiAsync.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ImportBatchRow(batch: ContentImportBatch, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tint = GlassTheme.tokens.glassFillStrong,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(batch.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChip(batch.status)
            }
            Text("${batch.examType} · ${batch.importedCount}/${batch.detectedCount} importadas", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Abrir revisión") }
        }
    }
}

@Composable
private fun ImportReview(
    batch: ContentImportBatch,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (ContentImportStagingItem) -> Unit,
    onSkip: (ContentImportStagingItem) -> Unit,
    onPublish: () -> Unit,
) {
    val summary = contentUploadPublishSummary(batch)
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Revisión del lote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onBack) { Text("Volver") }
            }
            Text(batch.fileName, style = MaterialTheme.typography.bodyMedium)
            Text("${batch.detectedCount} detectadas · ${summary.publishable} se publicarán · ${summary.blocking} con error o advertencia", style = MaterialTheme.typography.bodySmall)
            Text(summary.note(), style = MaterialTheme.typography.bodySmall)
            PublicationSafetyNotice()
            batch.staging.sortedBy { it.position }.forEach { item ->
                StagingEditorCard(item = item, onSave = onSave, onSkip = onSkip)
            }
            PrimaryButton(
                text = CONTENT_UPLOAD_PUBLISH_LABEL,
                onClick = onPublish,
                // La RPC publica `ok` Y `aprobada`; con errores/advertencias pendientes no.
                // `!busy` no es cosmético: un segundo toque mientras la primera
                // petición sigue en vuelo enviaría una publicación redundante.
                enabled = summary.canPublish(batch.status, busy),
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StagingEditorCard(
    item: ContentImportStagingItem,
    onSave: (ContentImportStagingItem) -> Unit,
    onSkip: (ContentImportStagingItem) -> Unit,
) {
    var title by remember(item.id, item.parseStatus) { mutableStateOf(item.title) }
    var optionA by remember(item.id, item.parseStatus) { mutableStateOf(item.optionA) }
    var optionB by remember(item.id, item.parseStatus) { mutableStateOf(item.optionB) }
    var optionC by remember(item.id, item.parseStatus) { mutableStateOf(item.optionC) }
    var optionD by remember(item.id, item.parseStatus) { mutableStateOf(item.optionD) }
    var correct by remember(item.id, item.parseStatus) { mutableStateOf(item.correctAnswer) }
    var reasoning by remember(item.id, item.parseStatus) { mutableStateOf(item.reasoning) }
    var source by remember(item.id, item.parseStatus) { mutableStateOf(item.sourceText) }
    var tag by remember(item.id, item.parseStatus) { mutableStateOf(item.tagText) }
    val edited = item.copy(title = title, optionA = optionA, optionB = optionB, optionC = optionC, optionD = optionD, correctAnswer = correct, reasoning = reasoning, sourceText = source, tagText = tag)
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pregunta ${item.position}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                StatusChip(item.parseStatus)
            }
            item.parseError?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            GlassTextField(title, { title = it }, "Título")
            GlassTextField(optionA, { optionA = it }, "Opción A")
            GlassTextField(optionB, { optionB = it }, "Opción B")
            GlassTextField(optionC, { optionC = it }, "Opción C")
            GlassTextField(optionD, { optionD = it }, "Opción D")
            GlassTextField(correct, { correct = it.take(1).uppercase() }, "Correcta: A, B, C o D")
            GlassTextField(reasoning, { reasoning = it }, "Razonamiento", singleLine = false)
            GlassTextField(source, { source = it }, "Fuente")
            GlassTextField(tag, { tag = it }, "Etiqueta")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(edited) }, modifier = Modifier.weight(1f)) { Text("Guardar y aprobar") }
                TextButton(onClick = { onSkip(item) }, modifier = Modifier.weight(1f)) { Text("Saltar") }
            }
        }
    }
}

/**
 * Ayuda de nivel 3 para la importación (§16, §61).
 *
 * Los cinco pasos y, sobre todo, la frase que evita el susto: nada de lo que se
 * sube llega a los usuarios hasta el último paso. Es la duda que tiene cualquiera
 * la primera vez que arrastra un PDF a un panel de administración.
 */
@Composable
private fun ImportHelpPanel(visible: Boolean, onClose: () -> Unit) {
    HelpPanel(title = "Cómo funciona la importación", visible = visible, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            HelpStep(1, "Sube el PDF. Sólo se lee texto seleccionable; no se hace OCR.")
            HelpStep(2, "Revisa las preguntas detectadas una a una.")
            HelpStep(3, "Corrige lo que haya salido mal y descarta lo que no sirva.")
            HelpStep(4, "Revisa los duplicados con preguntas que ya existen.")
            HelpStep(5, "Publica. Sólo en este paso el contenido llega a los usuarios.")
            Text(
                text = "Mientras el lote está en revisión no afecta a nadie: puedes dejarlo a " +
                    "medias y retomarlo más tarde.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.textSecondary,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
    }
}

/**
 * Aviso de seguridad de publicación (§39).
 *
 * La publicación por lotes es ATÓMICA (una RPC todo-o-nada) y detecta duplicados, así que
 * el aviso dice lo que de verdad pasa: si algo falla no se publica nada, y una duplicada
 * sin resolver bloquea la publicación en vez de reemplazar contenido existente.
 */
@Composable
private fun PublicationSafetyNotice() {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.warning.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(AppIcon.WARNING, size = 15.dp, tint = tokens.warning)
            Text(
                text = "Antes de publicar",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.warning,
            )
        }
        Text(
            text = "La publicación es todo o nada: si algo falla, no se publica ninguna pregunta " +
                "del lote y puedes corregirlo y reintentar.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        Text(
            text = "Los duplicados se detectan al publicar: no se reemplaza contenido que ya exista.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}
