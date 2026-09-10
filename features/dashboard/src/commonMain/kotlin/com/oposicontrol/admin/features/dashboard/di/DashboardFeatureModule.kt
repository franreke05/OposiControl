package com.oposicontrol.admin.features.dashboard.di

import com.oposicontrol.admin.features.dashboard.presentation.DashboardV2ViewModel
import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import org.koin.dsl.module

val dashboardFeatureModule = module {
    // single (no factory): se crea una vez y persiste durante toda la sesion,
    // asi la pestana no vuelve a mostrar el skeleton de carga cada vez que se
    // revisita — solo la primera vez, y esa primera carga se adelanta desde
    // AppRoot en cuanto el usuario se autentica (ver AppRoot.WarmUpPrimaryTabs).
    single { DashboardViewModel(get(), get(), get()) }

    // Panel de escritorio. `single` por el mismo motivo que el anterior: volver a
    // Inicio no debe repetir los catorce conteos ni enseñar de nuevo el
    // esqueleto. Depende de `DashboardOverviewRepository`, cuyo binding vive en
    // el modulo de datos y NO se declara aqui.
    single { DashboardV2ViewModel(get(), get(), get()) }
}
