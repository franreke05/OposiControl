package com.oposicontrol.admin.features.settings.di

import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import com.oposicontrol.admin.features.settings.data.NotConnectedSystemStatusRepository
import com.oposicontrol.admin.features.settings.presentation.SystemStatusViewModel
import org.koin.dsl.module

val systemStatusFeatureModule = module {
    single<SystemStatusRepository> { NotConnectedSystemStatusRepository() }
    factory { SystemStatusViewModel(get(), get(), get()) }
}
