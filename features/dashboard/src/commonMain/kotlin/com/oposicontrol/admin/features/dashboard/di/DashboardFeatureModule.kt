package com.oposicontrol.admin.features.dashboard.di

import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import org.koin.dsl.module

val dashboardFeatureModule = module {
    // single (no factory): se crea una vez y persiste durante toda la sesion,
    // asi la pestana no vuelve a mostrar el skeleton de carga cada vez que se
    // revisita — solo la primera vez, y esa primera carga se adelanta desde
    // AppRoot en cuanto el usuario se autentica (ver AppRoot.WarmUpPrimaryTabs).
    single { DashboardViewModel(get(), get(), get()) }
}
