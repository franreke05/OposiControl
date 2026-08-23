package com.oposicontrol.admin.features.opositester.di

import com.oposicontrol.admin.features.opositester.presentation.TesterListViewModel
import org.koin.dsl.module

val opositesterFeatureModule = module {
    // single: persiste entre visitas a la pestana (mismo criterio que Home,
    // Informes y Foro — ver DashboardFeatureModule).
    single { TesterListViewModel(get(), get(), get()) }
}
