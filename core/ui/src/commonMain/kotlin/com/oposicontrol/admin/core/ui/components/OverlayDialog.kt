package com.oposicontrol.admin.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * Propiedades de un dialogo del panel. En escritorio el velo que oscurece la pantalla aparece con un fundido de
 * [com.oposicontrol.admin.core.ui.theme.OposiMotion.Overlay], a la vez que entra el dialogo; cerrar lo quita en el
 * acto. En movil el velo y su animacion son los de la plataforma.
 */
@Composable
expect fun overlayDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
): DialogProperties
