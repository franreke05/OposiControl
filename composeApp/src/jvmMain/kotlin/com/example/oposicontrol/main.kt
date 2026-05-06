package com.oposicontrol.admin.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.oposicontrol.admin.core.common.platform.AppPlatformContext

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "OposiControl Admin",
    ) {
        OposiControlAdminApp(AppPlatformContext())
    }
}
