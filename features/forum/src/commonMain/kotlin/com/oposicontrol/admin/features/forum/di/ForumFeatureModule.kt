package com.oposicontrol.admin.features.forum.di

import com.oposicontrol.admin.features.forum.presentation.ForumViewModel
import com.oposicontrol.admin.features.forum.presentation.ThreadDetailViewModel
import org.koin.dsl.module

val forumFeatureModule = module {
    // single: persiste entre visitas a la pestana (ver DashboardFeatureModule).
    single { ForumViewModel(get(), get(), get(), get()) }
    // factory con parametro: cada hilo abierto necesita su propia instancia
    // (mismo criterio que ReportDetailViewModel), nunca compartida entre hilos.
    factory { (threadId: String) -> ThreadDetailViewModel(threadId, get(), get(), get()) }
}
