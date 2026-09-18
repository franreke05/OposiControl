package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Importaciones recientes.
 *
 * **El error no se queda con la página.** Antes, si el listado fallaba, una
 * tarjeta de error a pantalla completa ocupaba el sitio del historial y empujaba
 * todo lo demás: el flujo de importar seguía funcionando, pero no lo parecía.
 * Ahora el fallo cabe en una línea con su reintento, y la sección conserva su
 * tamaño.
 */
@Composable
internal fun ImportHistoryPane(
    state: ImportState,
    onIntent: (ImportIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = "Importaciones recientes",
        description = "Lotes subidos, con lo que detectó el análisis.",
        modifier = modifier,
        icon = AppIcon.CLOCK,
        iconRole = SemanticRole.INFO,
    ) {
        when (val batches = state.batches) {
            is UiAsync.Loading, is UiAsync.Idle -> Text(
                text = "Cargando importaciones…",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )

            is UiAsync.Error -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radius.md)
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.border, Radius.md)
                    .padding(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppIconImage(AppIcon.WARNING, size = 14.dp, tint = tokens.danger)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "No se han podido cargar las importaciones recientes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textPrimary,
                    )
                    Text(
                        text = batches.error.humanMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
                GhostButton(
                    text = "Reintentar",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(ImportIntent.Refresh) },
                )
            }

            is UiAsync.Empty -> Text(
                text = "Todavía no se ha importado ningún PDF.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )

            is UiAsync.Success -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                batches.value.forEach { batch ->
                    val metrics = batch.metrics()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(Radius.md)
                            .background(tokens.surfaceSolid)
                            .border(1.dp, tokens.border, Radius.md)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onIntent(ImportIntent.OpenBatch(batch.id)) }
                            .semantics {
                                contentDescription = "${importBatchLabel(batch)}. " +
                                    "${importBatchTypeLabel(batch)}. " +
                                    "${metrics.detected} detectadas, ${metrics.errors} con error. " +
                                    importBatchStatusLabel(batch.status)
                            }
                            .padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = importBatchLabel(batch),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${importBatchTypeLabel(batch)} · ${metrics.detected} detectadas · " +
                                    "${metrics.ready} listas · ${metrics.errors} con error",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(modifier = Modifier.width(160.dp)) {
                            StatusChip(
                                status = importBatchStatusLabel(batch.status),
                                tone = importBatchStatusTone(batch.status),
                            )
                        }
                        GhostButton(
                            text = "Abrir",
                            size = ButtonSize.COMPACT,
                            onClick = { onIntent(ImportIntent.OpenBatch(batch.id)) },
                        )
                    }
                }
            }
        }
    }
}
