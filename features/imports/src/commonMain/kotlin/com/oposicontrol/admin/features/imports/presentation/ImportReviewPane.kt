package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.IMPORT_BLOCK_MAIN
import com.oposicontrol.admin.domain.model.RazonadoMarkup
import com.oposicontrol.admin.domain.model.RazonadoMarkupMessages
import com.oposicontrol.admin.features.controlv1.presentation.MarkupPreview
import com.oposicontrol.admin.domain.model.IMPORT_BLOCK_RESERVE
import com.oposicontrol.admin.domain.model.isReserve
import com.oposicontrol.admin.domain.model.reserveOrderOf

/**
 * Etapas 3 y 4: qué ha salido del PDF y qué se hace con ello.
 *
 * **Una sola pantalla para validar y revisar.** El servidor ya devuelve las filas
 * clasificadas —lista, omitida, duplicada o con error— y lo único que se puede
 * hacer con esa clasificación es revisarla. Separarlas en dos pasos habría dado
 * una etapa que nadie puede recorrer.
 *
 * Las filas se editan de verdad: `updateStaging` existe y valida las cuatro
 * opciones y que la correcta sea A, B, C o D. Lo que se guarda se queda en el
 * lote; nada toca contenido publicado hasta pulsar «Publicar lote».
 */
@Composable
internal fun ImportReviewPane(
    state: ImportState,
    batch: ContentImportBatch,
    onIntent: (ImportIntent) -> Unit,
    modifier: Modifier = Modifier,
    detailBesideTable: Boolean = true,
) {
    val rows = state.visibleStaging()
    FormSection(
        title = "Revisión del lote",
        description = "Nada se publica hasta que lo decidas.",
        modifier = modifier,
        icon = AppIcon.CHECK,
        iconRole = SemanticRole.INFO,
        action = {
            GhostButton(
                text = "Cerrar lote",
                size = ButtonSize.COMPACT,
                onClick = { onIntent(ImportIntent.CloseBatch) },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ImportReviewFilters(state, batch, onIntent)

            if (detailBesideTable && state.editDraft != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ImportStagingList(state, rows, onIntent, modifier = Modifier.weight(1.1f))
                    ImportStagingDetail(state, batch, onIntent, modifier = Modifier.weight(1f))
                }
            } else {
                ImportStagingList(state, rows, onIntent, modifier = Modifier.fillMaxWidth())
                if (state.editDraft != null) {
                    ImportStagingDetail(state, batch, onIntent, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ImportReviewFilters(
    state: ImportState,
    batch: ContentImportBatch,
    onIntent: (ImportIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    val counts = batch.staging.groupingBy { stagingTone(it) }.eachCount()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ImportTextField(
            value = state.reviewSearch,
            onValueChange = { onIntent(ImportIntent.SetReviewSearch(it)) },
            placeholder = "Buscar por enunciado…",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            FilterChip(
                label = importRowToneLabel(ReviewFilter.ALL),
                count = batch.staging.size,
                selected = state.reviewFilter == ReviewFilter.ALL,
                onClick = { onIntent(ImportIntent.SetReviewFilter(ReviewFilter.ALL)) },
                tone = tokens.textPrimary,
            )
            listOf(
                ReviewFilter.LISTA to tokens.success,
                ReviewFilter.ADVERTENCIA to tokens.warning,
                ReviewFilter.OMITIDA to tokens.textSecondary,
                ReviewFilter.DUPLICADA to tokens.warning,
                ReviewFilter.ERROR to tokens.danger,
                ReviewFilter.IMPORTADA to tokens.info,
            ).filter { (filter, _) ->
                // «Importada» sólo cuando hay alguna: en un lote sin publicar sería un chip a 0 sin sentido.
                filter != ReviewFilter.IMPORTADA || (counts[filter] ?: 0) > 0
            }.forEach { (filter, tone) ->
                FilterChip(
                    label = importRowToneLabel(filter),
                    count = counts[filter] ?: 0,
                    selected = state.reviewFilter == filter,
                    onClick = { onIntent(ImportIntent.SetReviewFilter(filter)) },
                    tone = tone,
                )
            }
        }
    }
}

@Composable
private fun ImportStagingList(
    state: ImportState,
    rows: List<ContentImportStagingItem>,
    onIntent: (ImportIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    if (rows.isEmpty()) {
        Text(
            text = "Ninguna fila coincide con este filtro.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
            modifier = modifier.padding(vertical = Spacing.md),
        )
        return
    }
    Column(
        modifier = modifier
            .heightIn(max = ImportReviewSizing.listMaxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        val all = (state.activeBatch as? UiAsync.Success)?.value?.staging.orEmpty()
        rows.forEach { item ->
            ImportStagingRow(
                item = item,
                reserveNumber = reserveOrderOf(all, item),
                selected = item.id == state.selectedStagingId,
                onClick = { onIntent(ImportIntent.SelectStaging(item.id)) },
            )
        }
    }
}

@Composable
private fun ImportStagingRow(
    item: ContentImportStagingItem,
    reserveNumber: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val tone = stagingTone(item)
    val problem = importRowProblem(item)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(if (selected) tokens.surfaceSelected else tokens.surfaceSolid)
            .border(
                width = 1.dp,
                color = if (selected) tokens.accent.copy(alpha = 0.55f) else tokens.border,
                shape = Radius.md,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = "Fila ${item.position}. ${importRowTitle(item)}. " +
                    "${importRowToneLabel(tone)}. ${problem ?: ""} " +
                    "${importRowLinkLabel(item) ?: ""} ${importRowAnnulmentNote(item) ?: ""}"
            }
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "${item.position}",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            Text(
                text = importRowTitle(item),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                // La anulación es un dato de la pregunta en el examen: chip propio, aparte del estado de parseo.
                // Una reserva lleva su número futuro: solo puntúa si se anula una principal.
                if (item.isReserve) StatusChip(status = reserveNumber?.let { "Reserva $it" } ?: "Reserva", tone = StatusTone.NEUTRAL)
                if (item.annulled) StatusChip(status = IMPORT_ANNULLED_CHIP, tone = IMPORT_ANNULLED_TONE)
                StatusChip(status = importRowToneLabel(tone), tone = importRowToneStatusTone(tone))
            }
        }
        importRowLinkLabel(item)?.let { link ->
            Text(link, style = MaterialTheme.typography.bodySmall, color = tokens.success)
        }
        importRowAnnulmentNote(item)?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = "Correcta: ${importRowCorrectAnswer(item)}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            Text(
                text = importRowTopic(item),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (problem != null) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                AppIconImage(
                    icon = if (tone == ReviewFilter.ERROR) AppIcon.WARNING else AppIcon.INFO,
                    size = 12.dp,
                    tint = if (tone == ReviewFilter.ERROR) tokens.danger else tokens.textSecondary,
                )
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tone == ReviewFilter.ERROR) tokens.dangerInk else tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * El detalle de una fila, editable.
 *
 * Explicación y fuente sólo se ofrecen cuando el tipo del LOTE las admite. Si una
 * fila de un lote Ráfaga llega con esos campos rellenos, no se esconden —sería
 * ocultar contenido que el PDF traía— sino que se dicen con su aviso: el tipo del
 * lote no los contempla.
 */
@Composable
private fun ImportStagingDetail(
    state: ImportState,
    batch: ContentImportBatch,
    onIntent: (ImportIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val draft = state.editDraft ?: return
    val batchType = importTypeFromWire(batch.examType)
    val admitsReasoning = batchType != ContentExamType.RAFAGA
    val carriesUnsupported = !admitsReasoning &&
        (draft.reasoning.isNotBlank() || draft.sourceText.isNotBlank())
    val actions = importRowActions(draft, batchPublished = importBatchIsPublished(batch.status))
    val editable = actions.editable

    Column(
        modifier = modifier
            .clip(Radius.card)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, Radius.card)
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "Fila ${draft.position}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "Cerrar",
                size = ButtonSize.COMPACT,
                onClick = { onIntent(ImportIntent.SelectStaging(null)) },
            )
        }

        val tone = stagingTone(draft)
        importRowProblem(draft)?.let { problem ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radius.md)
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.border, Radius.md)
                    .padding(Spacing.xs),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                val isError = tone == ReviewFilter.ERROR
                AppIconImage(
                    icon = if (isError) AppIcon.WARNING else AppIcon.INFO,
                    size = 13.dp,
                    tint = if (isError) tokens.danger else tokens.textSecondary,
                )
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) tokens.dangerInk else tokens.textSecondary,
                )
            }
        }
        importRowLinkLabel(draft)?.let { link ->
            Text(link, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.success)
        }
        if (!editable) {
            Text(
                text = if (tone == ReviewFilter.IMPORTADA) {
                    "Esta fila ya está publicada en el examen: no se puede editar desde el lote."
                } else {
                    "El lote ya se ha publicado: sus filas no se pueden editar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }

        ImportTextField(
            value = draft.title,
            onValueChange = { onIntent(ImportIntent.UpdateEditDraft(draft.copy(title = it))) },
            placeholder = "Enunciado",
            singleLine = false,
            minHeight = 64.dp,
            enabled = editable,
        )
        listOf("A" to draft.optionA, "B" to draft.optionB, "C" to draft.optionC, "D" to draft.optionD)
            .forEach { (label, value) ->
                ImportTextField(
                    value = value,
                    onValueChange = { new ->
                        val updated = when (label) {
                            "A" -> draft.copy(optionA = new)
                            "B" -> draft.copy(optionB = new)
                            "C" -> draft.copy(optionC = new)
                            else -> draft.copy(optionD = new)
                        }
                        onIntent(ImportIntent.UpdateEditDraft(updated))
                    },
                    placeholder = "Opción $label",
                    singleLine = false,
                    enabled = editable,
                )
            }
        ImportDropdown(
            label = "Respuesta correcta",
            selectedLabel = importRowCorrectAnswer(draft),
            options = IMPORT_VALID_ANSWERS.sorted().map { it to it },
            onSelect = { onIntent(ImportIntent.UpdateEditDraft(draft.copy(correctAnswer = it))) },
            enabled = editable,
        )
        ImportTextField(
            value = draft.tagText,
            onValueChange = { onIntent(ImportIntent.UpdateEditDraft(draft.copy(tagText = it))) },
            placeholder = "Tema (texto libre)",
            enabled = editable,
        )

        if (admitsReasoning) {
            ImportTextField(
                value = draft.reasoning,
                onValueChange = { onIntent(ImportIntent.UpdateEditDraft(draft.copy(reasoning = it))) },
                placeholder = "Explicación",
                singleLine = false,
                minHeight = 56.dp,
                enabled = editable,
            )
            // Marcado de la explicación (FA38): lo que el servidor rechazaría se dice aquí, con su causa, antes de guardar.
            (RazonadoMarkup.parse(draft.reasoning) as? RazonadoMarkup.Result.Rejected)?.issues?.take(3)?.forEach { issue ->
                Text(
                    text = RazonadoMarkupMessages.describe(issue.code),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.dangerInk,
                )
            }
            // Vista previa EXACTA con el analizador saneador (arbol tipado, nunca HTML) cuando el texto lleva estilo.
            (RazonadoMarkup.parse(draft.reasoning) as? RazonadoMarkup.Result.Ok)?.doc?.takeUnless { RazonadoMarkup.isPlain(it) }?.let { doc ->
                Text(text = "Así se verá", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                MarkupPreview(doc)
            }
            ImportTextField(
                value = draft.sourceText,
                onValueChange = { onIntent(ImportIntent.UpdateEditDraft(draft.copy(sourceText = it))) },
                placeholder = "Fuente",
                enabled = editable,
            )
        } else if (carriesUnsupported) {
            Text(
                text = "Esta fila trae explicación o fuente, y el lote es de tipo Ráfaga: ese tipo no las contempla.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }

        // El bloque del examen oficial: una reserva se añade después de las principales y solo puntúa si se anula alguna.
        GlassToggleRow(
            title = "Es una reserva",
            description = "Se añade después de las preguntas principales y sustituye, por orden, a las anuladas. " +
                "El número lo da su lugar entre las reservas del fichero.",
            checked = draft.isReserve,
            onCheckedChange = { reserve ->
                onIntent(ImportIntent.UpdateEditDraft(draft.copy(block = if (reserve) IMPORT_BLOCK_RESERVE else IMPORT_BLOCK_MAIN)))
            },
            enabled = editable,
        )
        // La anulación es un dato de la pregunta EN EL EXAMEN, no del parseo: se corrige aquí, se
        // guarda con la fila y sólo lleva motivo si está anulada.
        GlassToggleRow(
            title = IMPORT_ANNULLED_LABEL,
            description = "La pregunta se publica anulada en este examen; conserva su posición.",
            checked = draft.annulled,
            onCheckedChange = { annulled ->
                onIntent(
                    ImportIntent.UpdateEditDraft(
                        draft.copy(
                            annulled = annulled,
                            annulmentReason = if (annulled) draft.annulmentReason else null,
                        ),
                    ),
                )
            },
            enabled = editable,
        )
        if (draft.annulled) {
            ImportTextField(
                value = draft.annulmentReason.orEmpty(),
                onValueChange = { onIntent(ImportIntent.UpdateEditDraft(draft.copy(annulmentReason = it))) },
                placeholder = "Motivo de la anulación (obligatorio)",
                enabled = editable,
            )
        }

        if (editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (actions.canSkip) {
                    SecondaryButton(
                        text = "Omitir",
                        size = ButtonSize.COMPACT,
                        onClick = { onIntent(ImportIntent.SkipStaging(draft)) },
                    )
                }
                if (actions.linkLabel != null) {
                    SecondaryButton(
                        text = actions.linkLabel,
                        size = ButtonSize.COMPACT,
                        onClick = { onIntent(ImportIntent.LinkStagingToExisting(draft)) },
                    )
                }
                if (actions.canUnlink) {
                    SecondaryButton(
                        text = "Deshacer vínculo",
                        size = ButtonSize.COMPACT,
                        onClick = { onIntent(ImportIntent.UnlinkStaging(draft)) },
                    )
                }
                if (actions.canApprove) {
                    SecondaryButton(
                        text = "Aprobar",
                        size = ButtonSize.COMPACT,
                        onClick = { onIntent(ImportIntent.ApproveStaging(draft)) },
                    )
                }
                PrimaryButton(
                    text = "Guardar fila",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(ImportIntent.SaveEditDraft) },
                    enabled = importAnswerIsValid(draft.correctAnswer),
                    loading = state.actionState is UiAsync.Loading,
                )
            }
            if (!importAnswerIsValid(draft.correctAnswer)) {
                Text(
                    text = "La respuesta correcta debe ser A, B, C o D para guardar o aprobar. Omitir no lo exige.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Los cambios se guardan en el lote. No tocan contenido publicado.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

private object ImportReviewSizing {
    /** La lista desplaza por dentro: si creciera con las filas, el rail quedaría fuera de pantalla. */
    val listMaxHeight: Dp = 520.dp
}
