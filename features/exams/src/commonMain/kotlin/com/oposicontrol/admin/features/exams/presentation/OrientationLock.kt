package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable

@Composable
expect fun LockScreenOrientation(orientation: Int)

object ScreenOrientation {
    const val PORTRAIT = 1
    const val LANDSCAPE = 2
    const val UNSET = 0
}
