package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Qué tema usa el panel.
 *
 * [SYSTEM] es el comportamiento que había antes de existir este control: seguir
 * al sistema operativo. [LIGHT] y [DARK] son una elección explícita de quien
 * administra, y mandan sobre el sistema.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * El estado del tema y cómo cambiarlo, disponible en toda la aplicación.
 *
 * Va por `CompositionLocal` y no por parámetros para no atravesar con un booleano
 * las firmas de `AppShell`, `DesktopShell` y las diecisiete pantallas que hay en
 * medio: el control vive en la barra superior, que está a seis niveles del sitio
 * donde se decide el tema.
 *
 * **La elección dura lo que dura la sesión.** No hay ningún mecanismo de
 * preferencias locales en la aplicación —ni DataStore, ni ajustes persistidos—, y
 * montar uno entero para esto sería desproporcionado: al reiniciar se vuelve a
 * [ThemeMode.SYSTEM]. El día que exista almacenamiento de preferencias, el único
 * cambio es de dónde sale el valor inicial y dónde se guarda [onSelect].
 */
class ThemeController(
    val mode: ThemeMode,
    val onSelect: (ThemeMode) -> Unit,
)

val LocalThemeController = staticCompositionLocalOf { ThemeController(ThemeMode.SYSTEM) {} }

/** El tema que toca pintar: la elección explícita, o la del sistema si no la hay. */
@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

