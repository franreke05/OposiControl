package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = GlassTheme.tokens.glassFill,
    strokeColor: Color = GlassTheme.tokens.glassStroke,
    shadowElevation: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val highlightBrush = Brush.linearGradient(
        colors = listOf(
            GlassTheme.tokens.glassHighlight.copy(alpha = 0.55f),
            GlassTheme.tokens.glassHighlight.copy(alpha = 0.08f),
            Color.Transparent,
        ),
        start = Offset.Zero,
        end = Offset(0f, 280f),
    )
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                clip = false,
                ambientColor = GlassTheme.tokens.shadowAmbient,
                spotColor = GlassTheme.tokens.shadowSpot,
            )
            .clip(shape)
            .background(tint)
            .border(1.dp, strokeColor, shape)
            .drawWithContent {
                drawRect(highlightBrush)
                drawContent()
            },
        content = content,
    )
}

@Composable
fun GlassActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = if (enabled) GlassTheme.tokens.accent else GlassTheme.tokens.glassFillStrong,
    textColor: Color = if (enabled) Color.White else GlassTheme.tokens.textSecondary,
    onClick: () -> Unit,
) {
    val radius = 16.dp
    GlassSurface(
        modifier = modifier
            .defaultMinSize(
                minWidth = 88.dp,
                minHeight = 50.dp,
            )
            .clip(RoundedCornerShape(radius))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(radius),
        tint = tint,
        strokeColor = tint.copy(alpha = 0.5f),
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(
                    horizontal = 18.dp,
                    vertical = 12.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
            )
        }
    }
}
