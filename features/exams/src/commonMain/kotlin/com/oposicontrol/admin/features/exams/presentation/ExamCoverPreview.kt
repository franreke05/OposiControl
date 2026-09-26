package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ExamCover
import com.oposicontrol.admin.domain.model.ExamCoverKind

/**
 * El recurso visual de una portada resuelta: `ExamCover → RECURSO VISUAL`, centralizado.
 *
 * PROVISIONAL: un bloque de color con unas iniciales, dibujado en el propio Compose —sin URLs, sin
 * Storage, sin archivos—. Cuando lleguen los vectores definitivos se sustituye SÓLO esta función.
 */
internal data class ExamCoverVisual(
    val background: Color,
    val foreground: Color,
    val glyph: String,
)

internal fun examCoverVisual(cover: ExamCover): ExamCoverVisual = when (cover.kind) {
    ExamCoverKind.RAFAGA -> ExamCoverVisual(Color(0xFF1E3A8A), Color.White, "RÁF")
    ExamCoverKind.RAZONADO -> ExamCoverVisual(Color(0xFF0F5F8A), Color.White, "RAZ")
    ExamCoverKind.COMMUNITY_VECTOR ->
        ExamCoverVisual(Color(0xFF14532D), Color.White, cover.communityCode?.removePrefix("ES-") ?: "?")
    ExamCoverKind.GENERIC -> ExamCoverVisual(Color(0xFF475569), Color.White, "?")
}

private object ExamCoverSizing {
    val previewWidth: Dp = 176.dp
    val previewHeight: Dp = 100.dp
}

/**
 * Previsualización AUTOMÁTICA de la portada del examen.
 *
 * Sustituye a la subida de archivos: no hay selector, ni botones, ni estado propio. La portada sale
 * del tipo y de la comunidad que ya están en el formulario, así que cambia sola cuando cambian y
 * no viaja con «Guardar cambios». La usan el workspace, la pantalla clásica y el móvil.
 */
@Composable
internal fun ExamCoverPreview(state: ExamsState, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val presentation = examCoverPresentation(state)
    val visual = examCoverVisual(presentation.cover)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(ExamCoverSizing.previewWidth)
                .height(ExamCoverSizing.previewHeight)
                .clip(Radius.card)
                .background(visual.background)
                .border(1.dp, tokens.borderStrong, Radius.card)
                .semantics { contentDescription = "${presentation.headline}. ${presentation.cover.assetKey}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = visual.glyph,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = visual.foreground,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = presentation.headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
            )
            Text(
                text = presentation.detail,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            Text(
                text = presentation.cover.assetKey,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
        }
    }
}
