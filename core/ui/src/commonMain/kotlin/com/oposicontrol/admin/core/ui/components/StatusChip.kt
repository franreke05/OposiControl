package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.UiSizes
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier,
) {
    val normalized = status.trim().lowercase()
    val (background, foreground) = when (normalized) {
        "active", "activo", "published", "publicado", "open", "resolved", "success", "correcta", "visible", "guardable", "publicable" -> {
            GlassTheme.tokens.accent.copy(alpha = 0.22f) to GlassTheme.tokens.textPrimary
        }
        "draft", "borrador", "scheduled", "programado", "pending", "pendiente", "idle", "editable", "programable" -> {
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
        "inactive", "inactivo", "archived", "archivado", "closed", "error", "ban", "muted", "bloqueada", "incompleta", "oculta" -> {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val animatedBackground by animateColorAsState(
        targetValue = background,
        animationSpec = tween(durationMillis = 180),
    )
    val animatedForeground by animateColorAsState(
        targetValue = foreground.takeOrElse { Color.Unspecified },
        animationSpec = tween(durationMillis = 180),
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = UiSizes.chipMinHeight)
            .animateContentSize(animationSpec = tween(durationMillis = 180))
            .background(color = animatedBackground, shape = MaterialTheme.shapes.extraLarge)
            .semantics(mergeDescendants = true) {
                contentDescription = "Estado: $status"
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(animatedForeground.copy(alpha = 0.84f), MaterialTheme.shapes.extraLarge),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = animatedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Color.takeOrElse(fallback: () -> Color): Color {
    return if (this == Color.Unspecified) fallback() else this
}
