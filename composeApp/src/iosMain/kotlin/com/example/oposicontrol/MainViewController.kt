package com.oposicontrol.admin.app

import androidx.compose.ui.window.ComposeUIViewController
import com.oposicontrol.admin.core.common.platform.AppPlatformContext

fun MainViewController() = ComposeUIViewController { OposiControlAdminApp(AppPlatformContext()) }
