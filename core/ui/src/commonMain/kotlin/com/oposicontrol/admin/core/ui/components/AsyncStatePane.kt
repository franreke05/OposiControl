package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
 *
 * Movimiento: la cara entra con un fundido cuando cambia la CLASE de estado (cargando, vacío, error, datos), no
 * cuando cambian los datos. Antes había un `Crossfade` sobre el estado entero, que se disparaba con cada `Success`
 * nuevo: recargar una lista la fundía consigo misma, con las dos versiones compuestas a la vez. La identidad del
 * contenido no cambia (`key(state)`, igual que hacía el `Crossfade`): unos datos nuevos siguen siendo un contenido
 * nuevo para cualquier `remember` que haya dentro; simplemente ya no se anuncian con una animación.
 *
 * [stableContent] = `true` cambia eso último a propósito: unos datos nuevos actualizan el contenido que ya está en
 * pantalla, que conserva su scroll y permite resaltar la fila que cambió. Sólo para contenidos cuyo estado interno no
 * dependa de reiniciarse con los datos (una lista con su scroll); por defecto sigue siendo `false`.
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
    stableContent: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val face = when (state) {
        UiAsync.Empty -> AsyncFace.EMPTY
        is UiAsync.Error -> AsyncFace.ERROR
        UiAsync.Idle, UiAsync.Loading -> AsyncFace.LOADING
        is UiAsync.Success -> AsyncFace.CONTENT
    }
    val faceContent: @Composable () -> Unit = {
        when (state) {
            UiAsync.Empty -> EmptyStateCard(
                title = emptyTitle,
                message = emptyMessage,
                icon = emptyIcon,
                action = emptyAction,
            )

            is UiAsync.Error -> ErrorStateCard(
                message = state.error.humanMessage(),
                technicalDetail = state.error.message
                    .takeIf { it.isNotBlank() && it != state.error.humanMessage() },
                onRetry = onRetry?.takeIf { state.error.isRetryable() },
            )

            // `Idle` no merece un mensaje propio: «Listo para cargar» describía
            // el estado interno del ViewModel, no algo que le importe a quien
            // mira la pantalla. Desde fuera, no-ha-cargado-aún y
            // está-cargando son lo mismo.
            UiAsync.Idle, UiAsync.Loading -> LoadingStateCard(rows = loadingRows)

            is UiAsync.Success -> content(state.value)
        }
    }
    Box(modifier = modifier.fillMaxWidth().motionEntrance(key = face, rise = false)) {
        if (stableContent) faceContent() else key(state) { faceContent() }
    }
}

private enum class AsyncFace { EMPTY, ERROR, LOADING, CONTENT }
