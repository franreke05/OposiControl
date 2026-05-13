package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun EmptyStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 220))
                .padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(6.dp)
                    .background(
                        GlassTheme.tokens.accent.copy(alpha = 0.22f),
                        RoundedCornerShape(6.dp),
                    ),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

@Composable
fun LoadingStateCard(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(rows.coerceIn(1, 5)) { index ->
                SkeletonLine(
                    modifier = Modifier
                        .fillMaxWidth(if (index == 0) 0.72f else 1f)
                        .height(if (index == 0) 18.dp else 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SkeletonLine(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition()
    val shimmer = transition.animateFloat(
        initialValue = -220f,
        targetValue = 420f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    Box(
        modifier = modifier.background(
            brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                    Color.White.copy(alpha = 0.44f),
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                ),
                start = Offset(shimmer.value, 0f),
                end = Offset(shimmer.value + 180f, 0f),
            ),
            shape = RoundedCornerShape(8.dp),
        ),
    )
}
