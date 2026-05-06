package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable

@Composable
actual fun LockScreenOrientation(orientation: Int) {
    // IOS orientation locking depends on UIViewController which is not easily accessible here
}
