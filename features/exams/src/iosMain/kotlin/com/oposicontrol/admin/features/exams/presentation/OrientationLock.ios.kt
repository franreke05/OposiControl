package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskAll
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

@Composable
actual fun LockScreenOrientation(orientation: Int) {
    val viewController = LocalUIViewController.current
    DisposableEffect(viewController, orientation) {
        val scene = viewController.view.window?.windowScene
        val originalMask = when (scene?.interfaceOrientation) {
            UIInterfaceOrientationPortrait -> UIInterfaceOrientationMaskPortrait
            UIInterfaceOrientationLandscapeLeft, UIInterfaceOrientationLandscapeRight -> UIInterfaceOrientationMaskLandscape
            else -> UIInterfaceOrientationMaskAll
        }
        scene?.requestOrientation(orientation.toOrientationMask())
        onDispose { scene?.requestOrientation(originalMask) }
    }
}

private fun Int.toOrientationMask(): ULong = when (this) {
    ScreenOrientation.PORTRAIT -> UIInterfaceOrientationMaskPortrait
    ScreenOrientation.LANDSCAPE -> UIInterfaceOrientationMaskLandscape
    else -> UIInterfaceOrientationMaskAll
}

private fun platform.UIKit.UIWindowScene.requestOrientation(mask: ULong) {
    requestGeometryUpdateWithPreferences(
        UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask),
        errorHandler = null,
    )
}
