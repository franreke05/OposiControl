package com.oposicontrol.admin.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.oposicontrol.admin.app.bootstrap.appModules
import com.oposicontrol.admin.app.bootstrap.platformRuntimeConfigSource
import com.oposicontrol.admin.app.root.AppRoot
import com.oposicontrol.admin.core.common.config.AppConfigResolver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import org.koin.compose.KoinApplication

@Composable
fun OposiControlAdminApp(
    platformContext: AppPlatformContext,
) {
    val appConfig = remember { AppConfigResolver.resolve(platformRuntimeConfigSource()) }

    AdminTheme {
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
