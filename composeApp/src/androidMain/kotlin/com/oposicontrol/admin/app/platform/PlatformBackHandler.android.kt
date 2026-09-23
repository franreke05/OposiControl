package com.oposicontrol.admin.app.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // `enabled = false` deja pasar el gesto al sistema, que es exactamente lo
    // que debe ocurrir en una pantalla raiz: mandar la app al fondo.
    BackHandler(enabled = enabled, onBack = onBack)
}
