package com.oposicontrol.admin.core.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private const val BaseWidth = 390f
private const val BaseHeight = 844f

@Immutable
data class AppLayoutMetrics(
    val widthScale: Float,
    val heightScale: Float,
    val contentScale: Float,
) {
    fun width(base: Dp, min: Dp = 0.dp): Dp = (base.value * widthScale).dp.coerceAtLeast(min)

    fun height(base: Dp, min: Dp = 0.dp): Dp = (base.value * heightScale).dp.coerceAtLeast(min)

    fun size(base: Dp, min: Dp = 0.dp): Dp = (base.value * contentScale).dp.coerceAtLeast(min)

    fun text(base: TextUnit, min: TextUnit = 0.sp): TextUnit {
        val scaledValue = base.value * contentScale
        return max(scaledValue, min.value).sp
    }
}

private var currentAppLayoutMetrics = AppLayoutMetrics(
    widthScale = 1f,
    heightScale = 1f,
    contentScale = 1f,
)

object AppLayout {
    val metrics: AppLayoutMetrics
        @Composable get() = currentAppLayoutMetrics
}

@Composable
fun rememberAppLayoutMetrics(
    maxWidth: Dp,
    maxHeight: Dp,
): AppLayoutMetrics {
    return remember(maxWidth, maxHeight) {
        val widthScale = maxWidth.value / BaseWidth
        val heightScale = maxHeight.value / BaseHeight
        AppLayoutMetrics(
            widthScale = widthScale,
            heightScale = heightScale,
            contentScale = minOf(widthScale, heightScale),
        )
    }
}

@Composable
fun ProvideAppLayoutMetrics(
    metrics: AppLayoutMetrics,
    content: @Composable () -> Unit,
) {
    currentAppLayoutMetrics = metrics
    content()
}
