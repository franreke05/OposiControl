package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportPublishResult

/**
 * El rail derecho: qué se está importando, qué admite y qué pasa al publicar.
 *
 * Sin lote abierto es la guía —reglas del tipo elegido y qué campos espera el
 * PDF—. Con lote abierto se convierte en su resumen y en el sitio desde el que se
 * publica, con el motivo del bloqueo escrito debajo del botón cuando no se puede.
 */
@Composable
internal fun ImportBatchSummary(
    state: ImportState,
    batch: ContentImportBatch?,
    onIntent: (ImportIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (batch != null) {
            ImportBatchCard(state, batch, onIntent)
        }
        ImportTypeRulesCard(state)
        ImportCodeGuideCard(state)
    }
}

@Composable
private fun ImportBatchCard(
    state: ImportState,
    batch: ContentImportBatch,
    onIntent: (ImportIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    val metrics = batch.metrics()
    val gate = importPublishGate(state, batch)

    GlassSectionSurface(
        shape = Radius.xl,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "Lote",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
        // El lote se identifica por su archivo: no hay código humano de lote.
        Text(
            text = importBatchLabel(batch),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Cabecera del PDF: el código del examen al que dice pertenecer. El servidor lo compara con el examen elegido.
        batch.examCode?.let { code ->
            Text(
                text = "Cabecera del fichero: $code",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            StatusChip(
                status = importBatchStatusLabel(batch.status),
                tone = importBatchStatusTone(batch.status),
            )
            Text(
                text = importBatchTypeLabel(batch),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }

        ImportMetricLine("Detectadas", metrics.detected, tokens.textPrimary)
        ImportMetricLine("Listas", metrics.ready, tokens.success)
        ImportMetricLine("Con advertencia", metrics.warnings, tokens.warning)
        ImportMetricLine("Omitidas", metrics.skipped, tokens.textSecondary)
        ImportMetricLine("Duplicadas", metrics.duplicates, tokens.warning)
        ImportMetricLine("Con error", metrics.errors, tokens.danger)
        // Importadas: ya están en el examen. Sólo salen cuando hay alguna (lote publicado).
        if (metrics.imported > 0) ImportMetricLine("Importadas", metrics.imported, tokens.info)
        // Anuladas por el tribunal: un dato de la pregunta en el examen, aparte del estado de parseo.
        ImportMetricLine("Anuladas por el tribunal", metrics.annulled, tokens.textSecondary)
        // Reservas del examen oficial: solo puntúan cuando se anula una principal.
        if (metrics.reserves > 0) ImportMetricLine("Reservas", metrics.reserves, tokens.info)

        PrimaryButton(
            text = "Publicar lote",
            onClick = { onIntent(ImportIntent.PublishBatch) },
            modifier = Modifier.fillMaxWidth(),
            enabled = gate.enabled,
            loading = state.actionState is UiAsync.Loading,
        )
        // El motivo va SIEMPRE: con el botón apagado dice por qué, y encendido
        // dice qué va a pasar exactamente.
        Text(
            text = gate.reason,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )

        // Los motivos concretos (preflight): por qué no se puede publicar, o qué conviene saber antes.
        gate.issues.filter { it.kind != ImportPreflightKind.PUBLISHING && it.kind != ImportPreflightKind.ALREADY_PUBLISHED && it.message != gate.reason }
            .forEach { issue ->
                ImportNotice(
                    text = issue.message,
                    icon = if (issue.blocking) AppIcon.WARNING else AppIcon.INFO,
                    danger = issue.blocking,
                )
            }

        ImportNotice(text = IMPORT_ATOMIC_NOTICE, icon = AppIcon.INFO, danger = false)

        (state.actionState as? UiAsync.Error)?.let { error ->
            ImportNotice(text = error.error.message, icon = AppIcon.WARNING, danger = true)
        }
        // Lo que hizo la última publicación (reservas añadidas, avisos y, si falta algo, qué falta).
        batch.publishResult?.takeIf { it.executed }?.let { result -> ImportPublishResultNotes(result) }

        (state.actionState as? UiAsync.Success)?.let { success ->
            Text(
                text = success.value,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun ImportTypeRulesCard(state: ImportState) {
    val tokens = GlassTheme.tokens
    val rule = importTypeRule(state.selectedType)
    GlassSectionSurface(
        shape = Radius.xl,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "Qué admite ${rule.label}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
        rule.admits.forEach { item ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                AppIconImage(AppIcon.CHECK, size = 12.dp, tint = tokens.success)
                Text(item, style = MaterialTheme.typography.bodySmall, color = tokens.textPrimary)
            }
        }
        rule.rejects.forEach { item ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                AppIconImage(AppIcon.CLOSE, size = 12.dp, tint = tokens.textSecondary)
                Text(item, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        if (state.selectedType == ContentExamType.INMERSIVO) {
            ImportNotice(text = IMMERSIVE_RESOURCE_IMPORT_NOTICE, icon = AppIcon.INFO, danger = false)
        }
    }
}

@Composable
private fun ImportCodeGuideCard(state: ImportState) {
    val tokens = GlassTheme.tokens
    GlassSectionSurface(
        shape = Radius.xl,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "Qué espera el PDF",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
        importCodeGuide(state.selectedType).forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        text = entry.field,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                    )
                    Text(
                        text = entry.example,
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                }
                Text(entry.note, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        ImportNotice(text = IMPORT_NO_CODES_NOTICE, icon = AppIcon.INFO, danger = false)
    }
}

@Composable
private fun ImportMetricLine(label: String, value: Int, tint: androidx.compose.ui.graphics.Color) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        Text(
            text = "$value",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

@Composable
private fun ImportNotice(text: String, icon: AppIcon, danger: Boolean) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(tokens.surfaceMuted)
            .border(1.dp, tokens.border, Radius.md)
            .padding(Spacing.xs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AppIconImage(icon, size = 13.dp, tint = if (danger) tokens.danger else tokens.textSecondary)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) tokens.dangerInk else tokens.textSecondary,
        )
    }
}

/** Qué hizo la publicación: lo que no bloquea se cuenta tal cual, y un problema abierto se explica en lenguaje de operador. */
@Composable
private fun ImportPublishResultNotes(result: ContentImportPublishResult) {
    if (result.reserves > 0) {
        ImportNotice(
            text = "Se añadieron ${result.reserves} reservas al examen, tras las principales. Solo puntúan si se anula alguna principal.",
            icon = AppIcon.INFO,
            danger = false,
        )
    }
    result.warnings.forEach { code ->
        ImportNotice(text = importPublishWarningText(code), icon = AppIcon.INFO, danger = false)
    }
    result.problem?.let { code ->
        ImportNotice(
            text = importPublishProblemText(code),
            icon = AppIcon.WARNING,
            danger = false,
        )
    }
}

/** Los avisos que el servidor devuelve al publicar un lote con reservas. */
internal fun importPublishWarningText(code: String): String = when (code) {
    "MORE_RESERVES_THAN_MAX" -> "El examen tiene más reservas de las habituales (normalmente 3). No bloquea."
    "ANNULLED_RESERVE_SKIPPED" -> "Una reserva está anulada: se salta y se usa la siguiente."
    else -> "Aviso del servidor: $code."
}

/** El primer problema abierto de las reglas oficiales tras publicar: el examen en borrador aún no está completo. */
internal fun importPublishProblemText(code: String): String = when (code) {
    "RESERVE_WITHOUT_MAIN" -> "Hay reservas pero aún no hay preguntas principales: importa primero las principales."
    "RESERVE_ORDER_GAPS" -> "El orden de las reservas tiene huecos: tiene que ser 1, 2, 3…"
    "RESERVE_BEFORE_MAIN" -> "Una reserva quedó antes de la última principal: no se podrá publicar el examen así."
    "REEMPLAZO_NOT_ENOUGH_RESERVES" -> "Hay más preguntas anuladas que reservas: faltan reservas para poder publicar el examen."
    "DEMO_CONFLICT" -> "Una pregunta de demostración está anulada o es reserva."
    else -> "Falta completar el examen antes de publicarlo ($code)."
}
