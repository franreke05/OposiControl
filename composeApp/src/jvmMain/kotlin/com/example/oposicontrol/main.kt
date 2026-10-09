package com.oposicontrol.admin.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oposicontrol.admin.app.bootstrap.JvmRuntimeConfigSource
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import io.sentry.Sentry
import org.jetbrains.compose.resources.painterResource
import oposicontrol.composeapp.generated.resources.Res
import oposicontrol.composeapp.generated.resources.ic_logo
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
private fun initializeDesktopSentry() {
    val config = JvmRuntimeConfigSource()

    val dsn = config.read("SENTRY_DESKTOP_DSN")
        ?.trim()
        .orEmpty()

    if (dsn.isBlank()) return

    val environment = config.read("SENTRY_DESKTOP_ENVIRONMENT")
        ?.trim()
        ?.ifBlank { "development" }
        ?: "development"

    val tracesSampleRate = config.read("SENTRY_DESKTOP_TRACES_SAMPLE_RATE")
        ?.toDoubleOrNull()
        ?.coerceIn(0.0, 1.0)
        ?: 0.0

    val release = config.read("SENTRY_DESKTOP_RELEASE")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    Sentry.init { options ->
        options.dsn = dsn
        options.environment = environment
        options.release = release
        options.tracesSampleRate = tracesSampleRate
        options.isSendDefaultPii = false
        options.isAttachStacktrace = true
    }
}

fun main() {
    initializeDesktopSentry()

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "OposiControl Admin",
            // Sin icono, la barra de tareas muestra el de Java.
            icon = painterResource(Res.drawable.ic_logo),
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(1024, 640)
            }
            OposiControlAdminApp(AppPlatformContext())
        }
    }
}
