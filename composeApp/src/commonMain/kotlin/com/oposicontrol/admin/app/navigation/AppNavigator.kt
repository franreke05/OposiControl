package com.oposicontrol.admin.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppNavigator {
    private val mutableDestination = MutableStateFlow<AppDestination>(AppDestination.Admin(AdminSection.DASHBOARD))
    val destination: StateFlow<AppDestination> = mutableDestination.asStateFlow()

    fun openAuth() {
        mutableDestination.value = AppDestination.Auth
    }

    fun openSection(section: AdminSection) {
        mutableDestination.value = AppDestination.Admin(section)
    }
}
