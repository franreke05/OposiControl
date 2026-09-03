package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.common.presentation.UiAsync

/**
 * Resuelve los cinco estados de una carga remota.
 *
 * Antes `Error` reutilizaba la tarjeta de «sin resultados» con el mensaje técnico
 * dentro, así que un corte de red y una lista legítimamente vacía se veían igual
 * y ninguno de los dos ofrecía salida. Ahora cada estado tiene su forma:
 *
 *  - `Loading`  esqueleto con el número de filas que va a ocupar el contenido.
 *  - `Empty`    explica qué falta y, si se le pasa, ofrece la acción para crearlo.
 *  - `Error`    mensaje humano, reintento cuando sirve de algo, detalle plegado.
 *  - `Idle`     igual que `Loading`: para quien mira, aún no hay nada que ver.
 *
 * `onRetry` es opcional para no romper las 34 llamadas existentes, pero toda
 * pantalla que cargue de red debería pasarlo.
 */
@Composable
fun <T> AsyncStatePane(
    state: UiAsync<T>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No hay datos disponibles.",
    emptyTitle: String = "Sin resultados",
    emptyIcon: AppIcon = AppIcon.SEARCH,
    loadingRows: Int = 4,
    onRetry: (() -> Unit)? = null,
    emptyAction: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 220),
            modifier = Modifier.fillMaxWidth(),
        ) { targetState ->
            when (targetState) {
                UiAsync.Empty -> EmptyStateCard(
                    title = emptyTitle,
                    message = emptyMessage,
                    icon = emptyIcon,
                    action = emptyAction,
                )

                is UiAsync.Error -> ErrorStateCard(
                    message = targetState.error.humanMessage(),
                    technicalDetail = targetState.error.message
                        .takeIf { it.isNotBlank() && it != targetState.error.humanMessage() },
                    onRetry = onRetry?.takeIf { targetState.error.isRetryable() },
                )

                // `Idle` no merece un mensaje propio: «Listo para cargar» describía
                // el estado interno del ViewModel, no algo que le importe a quien
                // mira la pantalla. Desde fuera, no-ha-cargado-aún y
                // está-cargando son lo mismo.
                UiAsync.Idle, UiAsync.Loading -> LoadingStateCard(rows = loadingRows)

                is UiAsync.Success -> content(targetState.value)
            }
        }
    }
}
