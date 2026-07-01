package com.oposicontrol.admin.features.dashboard.di

import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import org.koin.dsl.module

val dashboardFeatureModule = module {
    factory { DashboardViewModel(get(), get(), get()) }
}
