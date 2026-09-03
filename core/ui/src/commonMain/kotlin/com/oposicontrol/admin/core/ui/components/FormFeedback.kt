package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.theme.GlassTheme

/**
 * Inline banner that renders a CRUD action's [UiAsync] state as feedback.
 * Replaces the scattered `when(actionState){ Text(...) }` blocks across screens.
 */
@Composable
fun FormFeedback(
    state: UiAsync<*>,
    modifier: Modifier = Modifier,
    loadingText: String = "Guardando...",
    successText: String = "Cambios guardados.",
) {
    val tokens = GlassTheme.tokens
    val visible = state is UiAsync.Loading || state is UiAsync.Success || state is UiAsync.Error
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val (color, text) = when (state) {
            is UiAsync.Loading -> tokens.warning to loadingText
            is UiAsync.Success -> tokens.success to successText
            // Mismo criterio que ErrorStateCard: lo que se lee es humano, no el texto
            // que viene del transporte.
            is UiAsync.Error -> MaterialTheme.colorScheme.error to state.error.humanMessage()
            else -> tokens.textSecondary to ""
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, RoundedCornerShape(8.dp)),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
