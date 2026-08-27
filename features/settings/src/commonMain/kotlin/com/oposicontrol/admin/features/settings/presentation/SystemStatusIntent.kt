package com.oposicontrol.admin.features.settings.presentation

sealed interface SystemStatusIntent {
    data object Refresh : SystemStatusIntent
}
