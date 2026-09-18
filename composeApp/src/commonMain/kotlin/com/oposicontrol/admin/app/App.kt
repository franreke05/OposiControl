package com.oposicontrol.admin.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.oposicontrol.admin.app.bootstrap.appModules
import com.oposicontrol.admin.app.bootstrap.platformRuntimeConfigSource
import com.oposicontrol.admin.app.root.AppRoot
import com.oposicontrol.admin.core.common.config.AppConfigResolver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.LocalThemeController
import com.oposicontrol.admin.core.ui.theme.ThemeController
import com.oposicontrol.admin.core.ui.theme.ThemeMode
import com.oposicontrol.admin.core.ui.theme.resolveDark
import org.koin.compose.KoinApplication

@Composable
fun OposiControlAdminApp(
    platformContext: AppPlatformContext,
) {
    val appConfig = remember { AppConfigResolver.resolve(platformRuntimeConfigSource()) }

    // El modo se guarda por nombre y no como enum: `rememberSaveable` sólo sabe
    // guardar tipos primitivos sin un `Saver` propio, y esto también corre en
    // Android, donde una rotación recrea la composición.
    var themeName by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM.name) }
    val themeMode = ThemeMode.valueOf(themeName)
    val isDark = themeMode.resolveDark()
    val themeController = remember(themeMode) {
        ThemeController(themeMode) { themeName = it.name }
    }

    CompositionLocalProvider(LocalThemeController provides themeController) {
        AdminTheme(darkTheme = isDark) {
            KoinApplication(
                application = {
                    modules(appModules(appConfig, platformContext))
                },
            ) {
                AppRoot(
                    appConfig = appConfig,
                )
            }
        }
    }
}
