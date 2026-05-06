package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier,
) {
    val normalized = status.trim().lowercase()
    val (background, foreground) = when (normalized) {
        "active", "published", "open", "resolved", "success", "correcta" -> GlassTheme.tokens.accent.copy(alpha = 0.16f) to GlassTheme.tokens.accent
        "draft", "scheduled", "pending", "idle" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
        "inactive", "archived", "closed", "error", "ban", "muted" -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status,
        modifier = modifier
            .background(color = background, shape = MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = foreground.takeOrElse { Color.Unspecified },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun Color.takeOrElse(fallback: () -> Color): Color {
    return if (this == Color.Unspecified) fallback() else this
}
