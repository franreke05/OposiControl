package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.common.presentation.UiAsync

@Composable
fun <T> AsyncStatePane(
    state: UiAsync<T>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No hay datos disponibles.",
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 220),
            modifier = Modifier.fillMaxWidth(),
        ) { targetState ->
            when (targetState) {
                UiAsync.Empty -> GlassFeedbackPane(
                    kind = GlassFeedbackKind.Empty,
                    title = "Sin resultados",
                    message = emptyMessage,
                )

                is UiAsync.Error -> GlassFeedbackPane(
                    kind = GlassFeedbackKind.Error,
                    title = "No se pudo cargar",
                    message = targetState.error.message,
                    actionLabel = if (onRetry != null) "Reintentar" else null,
                    onAction = onRetry,
                )

                UiAsync.Idle -> GlassFeedbackPane(
                    kind = GlassFeedbackKind.Idle,
                    title = "Preparado",
                    message = "Listo para cargar.",
                )

                UiAsync.Loading -> GlassFeedbackPane(
                    kind = GlassFeedbackKind.Loading,
                    title = "Cargando",
                    message = "Estamos preparando los datos.",
                )

                is UiAsync.Success -> content(targetState.value)
            }
        }
    }
}
