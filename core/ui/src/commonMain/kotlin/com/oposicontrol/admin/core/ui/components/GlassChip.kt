package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme

/** A single selectable glass chip. */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val tokens = GlassTheme.tokens
    val background by animateColorAsState(
        targetValue = if (selected) tokens.accent.copy(alpha = 0.18f) else tokens.glassFill,
        animationSpec = tween(160),
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) tokens.accent.copy(alpha = 0.55f) else tokens.glassStroke,
        animationSpec = tween(160),
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) tokens.accent else tokens.textSecondary,
        animationSpec = tween(160),
    )
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .background(background, RoundedCornerShape(18.dp))
            .border(1.dp, stroke, RoundedCornerShape(18.dp))
            .let {
                if (enabled) {
                    it.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    it
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .wrapContentSize(Alignment.Center),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

/**
 * Multi/single select group of chips over a set of [options] (id -> label).
 * Replaces free-text CSV fields for roles / permissions / tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassChipGroup(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            GlassChip(
                label = label,
                selected = id in selected,
                enabled = enabled,
                onClick = { onToggle(id) },
            )
        }
    }
}
