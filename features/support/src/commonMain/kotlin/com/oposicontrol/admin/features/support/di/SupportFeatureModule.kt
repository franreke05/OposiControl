package com.oposicontrol.admin.features.support.di

import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import org.koin.dsl.module

val supportFeatureModule = module {
    single { SupportViewModel(get(), get(), get()) }
}
