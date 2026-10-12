package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import com.oposicontrol.admin.core.ui.theme.LocalMotionEnabled
import com.oposicontrol.admin.core.ui.theme.OposiMotion

/** Opacidad del velo de un dialogo de escritorio: la misma que Compose usa por defecto. */
private const val SCRIM_ALPHA = 0.6f

@Composable
actual fun overlayDialogProperties(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
    usePlatformDefaultWidth: Boolean,
): DialogProperties {
    val enabled = LocalMotionEnabled.current
    val veil = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) { veil.animateTo(1f, tween(OposiMotion.Overlay, easing = OposiMotion.Enter)) }
    return DialogProperties(
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        usePlatformDefaultWidth = usePlatformDefaultWidth,
        scrimColor = Color.Black.copy(alpha = SCRIM_ALPHA * veil.value),
    )
}
