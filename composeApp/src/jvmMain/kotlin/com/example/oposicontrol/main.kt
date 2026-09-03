package com.oposicontrol.admin.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.awt.Dimension

/**
 * Punto de entrada del panel de escritorio.
 *
 * El tamaño de ventana no es cosmético. `Window` sin `state` arranca en el
 * tamaño por defecto de Compose Desktop —800×600—, y el shell elige entre el
 * layout de escritorio y el de móvil en el umbral de 1040dp de ancho: la
 * aplicación de escritorio se abría, literalmente, con la interfaz de móvil y
 * su barra inferior, hasta que alguien maximizaba la ventana a mano.
 *
 * 1440×900 es el arranque porque entra en cualquiera de las resoluciones
 * objetivo (la más pequeña es 1366×768) sin salirse de la pantalla.
 *
 * El mínimo de 1024×640 lo impone la ventana AWT, no Compose: por debajo de ahí
 * la sidebar y el contenido dejan de convivir, y es preferible que el sistema
 * operativo no permita llegar a ese tamaño a que la aplicación se rompa dentro.
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "OposiControl Admin",
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1024, 640)
        }
        OposiControlAdminApp(AppPlatformContext())
    }
}
