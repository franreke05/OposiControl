package com.oposicontrol.admin.features.opositester.di

import com.oposicontrol.admin.features.opositester.presentation.TesterDetailViewModel
import com.oposicontrol.admin.features.opositester.presentation.TesterListViewModel
import org.koin.dsl.module

val opositesterFeatureModule = module {
    // single: persiste entre visitas a la pestana (mismo criterio que Home,
    // Informes y Foro — ver DashboardFeatureModule).
    single { TesterListViewModel(get(), get(), get()) }
    // factory con parametro: una instancia nueva por incidencia abierta,
    // mismo criterio que ThreadDetailViewModel (ForumFeatureModule).
    factory { (testerId: String) -> TesterDetailViewModel(testerId, get(), get(), get()) }
}
