package com.oposicontrol.admin.core.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class AppWindowClass {
    Mobile,
    Tablet,
    DesktopCompact,
    DesktopWide,
}

@Immutable
data class AppLayoutMetrics(
    val windowClass: AppWindowClass,
    val screenPadding: Dp,
    val navigationWidth: Dp,
    val contentMaxWidth: Dp,
) {
    val isMobile: Boolean
        get() = windowClass == AppWindowClass.Mobile

    val isCompact: Boolean
        get() = windowClass == AppWindowClass.Mobile || windowClass == AppWindowClass.Tablet

    // Compatibility helpers intentionally keep exact design dimensions.
    fun width(base: Dp, min: Dp = 0.dp): Dp = base.coerceAtLeast(min)

    fun height(base: Dp, min: Dp = 0.dp): Dp = base.coerceAtLeast(min)

    fun size(base: Dp, min: Dp = 0.dp): Dp = base.coerceAtLeast(min)

    fun text(base: TextUnit, min: TextUnit = 0.sp): TextUnit = if (base < min) min else base
}

private val DefaultAppLayoutMetrics = AppLayoutMetrics(
    windowClass = AppWindowClass.Mobile,
    screenPadding = 20.dp,
    navigationWidth = 72.dp,
    contentMaxWidth = 560.dp,
)

private val LocalAppLayoutMetrics = staticCompositionLocalOf { DefaultAppLayoutMetrics }

object AppLayout {
    val metrics: AppLayoutMetrics
        @Composable
        @ReadOnlyComposable
        get() = LocalAppLayoutMetrics.current
}

fun appWindowClassForWidth(width: Dp): AppWindowClass = when {
    width < 600.dp -> AppWindowClass.Mobile
    width < 840.dp -> AppWindowClass.Tablet
    width < 1_200.dp -> AppWindowClass.DesktopCompact
    else -> AppWindowClass.DesktopWide
}

@Composable
fun rememberAppLayoutMetrics(
    maxWidth: Dp,
    maxHeight: Dp,
): AppLayoutMetrics {
    return remember(maxWidth, maxHeight) {
        when (val windowClass = appWindowClassForWidth(maxWidth)) {
            AppWindowClass.Mobile -> AppLayoutMetrics(
                windowClass = windowClass,
                screenPadding = 20.dp,
                navigationWidth = 72.dp,
                contentMaxWidth = 560.dp,
            )

            AppWindowClass.Tablet -> AppLayoutMetrics(
                windowClass = windowClass,
                screenPadding = 24.dp,
                navigationWidth = 72.dp,
                contentMaxWidth = 560.dp,
            )

            AppWindowClass.DesktopCompact -> AppLayoutMetrics(
                windowClass = windowClass,
                screenPadding = 32.dp,
                navigationWidth = 72.dp,
                contentMaxWidth = 1_120.dp,
            )

            AppWindowClass.DesktopWide -> AppLayoutMetrics(
                windowClass = windowClass,
                screenPadding = 32.dp,
                navigationWidth = 280.dp,
                contentMaxWidth = 1_440.dp,
            )
        }
    }
}

@Composable
fun ProvideAppLayoutMetrics(
    metrics: AppLayoutMetrics,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppLayoutMetrics provides metrics,
        content = content,
    )
}
