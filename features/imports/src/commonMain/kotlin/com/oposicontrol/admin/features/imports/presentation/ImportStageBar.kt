package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Dónde está el administrador dentro del flujo.
 *
 * Cada etapa lleva número, nombre y un detalle con el dato real —el tipo elegido,
 * el archivo, cuántas filas—, así que el estado nunca depende sólo del color: una
 * etapa hecha lleva su check, y la bloqueada su marca de aviso.
 */
@Composable
internal fun ImportStageBar(state: ImportState, modifier: Modifier = Modifier) {
    val steps = importStages(state)
    GlassSectionSurface(
        modifier = modifier.fillMaxWidth(),
        shape = Radius.xl,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Spacing.md,
            vertical = Spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEach { step ->
                ImportStageChip(step, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImportStageChip(step: ImportStageStep, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val accent = when (step.state) {
        ImportStepState.DONE -> tokens.success
        ImportStepState.ACTIVE -> tokens.accentInk
        ImportStepState.BLOCKED -> tokens.danger
        ImportStepState.PENDING -> tokens.textSecondary
    }
    val stateWord = when (step.state) {
        ImportStepState.DONE -> "hecha"
        ImportStepState.ACTIVE -> "en curso"
        ImportStepState.BLOCKED -> "con problema"
        ImportStepState.PENDING -> "pendiente"
    }

    Row(
        modifier = modifier
            .clip(Radius.md)
            .background(if (step.state == ImportStepState.ACTIVE) tokens.surfaceSelected else tokens.surfaceSolid)
            .border(
                width = 1.dp,
                color = if (step.state == ImportStepState.ACTIVE) tokens.accent.copy(alpha = 0.55f) else tokens.border,
                shape = Radius.md,
            )
            .semantics { contentDescription = "Etapa ${step.index}, ${step.label}, $stateWord. ${step.detail}" }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(Radius.pill)
                .background(tokens.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            when (step.state) {
                ImportStepState.DONE -> AppIconImage(AppIcon.CHECK, size = 12.dp, tint = accent)
                ImportStepState.BLOCKED -> AppIconImage(AppIcon.WARNING, size = 12.dp, tint = accent)
                else -> Text(
                    text = "${step.index}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
            )
            Text(
                text = step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
