package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Sin barra: en tactil el gesto de arrastre ya comunica el desplazamiento y una
// barra permanente solo robaria ancho util.

@Composable
actual fun DesktopVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) = Unit

@Composable
actual fun DesktopVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier,
) = Unit
