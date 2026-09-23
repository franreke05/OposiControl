package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Barra de desplazamiento visible, sólo en escritorio.
 *
 * En móvil el scroll se descubre arrastrando y la barra sobra. En un panel de
 * escritorio es información: dice si la tabla tiene tres filas más o trescientas,
 * y sin ella no hay forma de saber cuánto queda por debajo del pliegue.
 *
 * En Android e iOS estas funciones no pintan nada — el gesto ya resuelve el caso.
 */
@Composable
expect fun DesktopVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

@Composable
expect fun DesktopVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
)
