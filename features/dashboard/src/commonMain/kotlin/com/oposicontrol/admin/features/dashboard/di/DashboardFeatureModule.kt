package com.oposicontrol.admin.features.dashboard.di

import com.oposicontrol.admin.domain.usecase.dashboard.GetDashboardMetricsUseCase
import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import org.koin.dsl.module

val dashboardFeatureModule = module {
    factory { GetDashboardMetricsUseCase(get()) }
    factory { DashboardViewModel(get(), get(), get()) }
}
