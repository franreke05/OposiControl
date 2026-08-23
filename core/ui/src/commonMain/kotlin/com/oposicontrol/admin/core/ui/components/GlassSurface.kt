package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val highlightColor = GlassTheme.tokens.glassHighlight
    val shadowColor = GlassTheme.tokens.shadowSpot
    // No Modifier.shadow()/elevation: on this renderer, an elevation-based RenderNode
    // shadow behind a translucent glass background composites into a visible opaque
    // rectangle rather than a soft drop shadow (isolated empirically — the artifact
    // tracks the shadow modifier itself, not the tint, border or highlight). "Soft
    // shadow" is instead a faint inner gradient drawn in the same pass as the
    // highlight sheen below, which is proven clean on this renderer.
    Box(
        modifier = modifier
            // Sin animateContentSize(): medido con dumpsys gfxinfo durante el gesto de
            // deslizar entre pestañas (GlassSurface se usa en cada card de cada
            // pantalla, así que este modificador corría decenas de veces por frame) —
            // pasaba de ~47% de frames "janky" (mediana 48ms/frame) a ~14% (mediana
            // 28ms/frame) al quitarlo. El caso de uso real de tamaño animado
            // (secciones que se expanden/colapsan en Home, EmptyStateCard con acción)
            // ya lo resuelve cada sitio con su propio AnimatedVisibility/
            // animateContentSize específico, así que este no aportaba nada que no
            // estuviera ya cubierto — solo coste.
            .clip(shape)
            .background(animatedTint)
            .border(1.dp, animatedStroke, shape)
            .drawWithContent {
                // Soft sheen confined to a fraction of the actual node height so it
                // never reads as an opaque rectangle on short cards (tiles, chips).
                val highlightBrush = Brush.verticalGradient(
                    colors = listOf(
                        highlightColor.copy(alpha = 0.22f),
                        highlightColor.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height * 0.6f,
                )
                drawRect(highlightBrush)
                if (animatedElevation > 0.dp) {
                    val shadowBrush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, shadowColor.copy(alpha = 0.10f)),
                        startY = size.height * 0.6f,
                        endY = size.height,
                    )
                    drawRect(shadowBrush)
                }
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = tween(durationMillis = 120),
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (pressed && enabled) 6.dp else 12.dp,
        animationSpec = tween(durationMillis = 120),
    )
    val radius = 16.dp
    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .defaultMinSize(
                minWidth = 88.dp,
                minHeight = 50.dp,
            )
            .clip(RoundedCornerShape(radius))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
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
