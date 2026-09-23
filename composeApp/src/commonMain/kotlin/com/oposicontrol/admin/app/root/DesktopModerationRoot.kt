package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.core.common.config.AppConfig

@Composable
fun DesktopModerationRoot(
    appConfig: AppConfig,
) {
    AppRoot(
        appConfig = appConfig,
    )
}
