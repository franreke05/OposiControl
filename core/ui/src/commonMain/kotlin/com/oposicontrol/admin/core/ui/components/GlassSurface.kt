package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.UiElevation
import com.oposicontrol.admin.core.ui.layout.UiRadii
import com.oposicontrol.admin.core.ui.layout.UiSizes
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme

enum class GlassButtonVariant {
    Primary,
    Secondary,
    Destructive,
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(UiRadii.extraLarge),
    tint: Color = GlassTheme.tokens.glassFill,
    strokeColor: Color = GlassTheme.tokens.glassStroke,
    shadowElevation: Dp = UiElevation.high,
    content: @Composable BoxScope.() -> Unit,
) {
    val animatedTint by animateColorAsState(
        targetValue = tint,
        animationSpec = tween(durationMillis = 180),
    )
    val animatedStroke by animateColorAsState(
        targetValue = strokeColor,
        animationSpec = tween(durationMillis = 180),
    )
    val animatedElevation by animateDpAsState(
        targetValue = shadowElevation,
        animationSpec = tween(durationMillis = 180),
    )
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
            .animateContentSize(animationSpec = tween(durationMillis = 220))
            .shadow(
                elevation = animatedElevation,
                shape = shape,
                clip = false,
                ambientColor = GlassTheme.tokens.shadowAmbient,
                spotColor = GlassTheme.tokens.shadowSpot,
            )
            .clip(shape)
            .background(animatedTint)
            .border(1.dp, animatedStroke, shape)
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
    textColor: Color = if (enabled) GlassTheme.tokens.textPrimary else GlassTheme.tokens.textSecondary,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    GlassButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        tint = tint,
        textColor = textColor,
    )
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GlassButtonVariant = GlassButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    val (tint, textColor) = when {
        !enabled -> tokens.glassFillStrong to tokens.textSecondary
        variant == GlassButtonVariant.Primary -> tokens.accent to tokens.textPrimary
        variant == GlassButtonVariant.Secondary -> tokens.glassFillStrong to tokens.textPrimary
        else -> tokens.error to Color.White
    }
    GlassButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        tint = tint,
        textColor = textColor,
    )
}

@Composable
private fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    tint: Color,
    textColor: Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !loading) 0.98f else 1f,
        animationSpec = tween(durationMillis = 120),
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (pressed && enabled && !loading) 6.dp else UiElevation.high,
        animationSpec = tween(durationMillis = 120),
    )
    val radius = UiRadii.medium
    val canClick = enabled && !loading
    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .defaultMinSize(
                minWidth = 88.dp,
                minHeight = UiSizes.buttonHeight,
            )
            .heightIn(min = UiSizes.buttonHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!canClick) disabled()
                if (loading) stateDescription = "Cargando"
            }
            .clip(RoundedCornerShape(radius))
            .clickable(
                enabled = canClick,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(radius),
        tint = tint,
        strokeColor = tint.copy(alpha = 0.5f),
        shadowElevation = animatedElevation,
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(
                    horizontal = UiSpacing.block,
                    vertical = UiSpacing.compact,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(UiSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = textColor,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
            }
        }
    }
}
