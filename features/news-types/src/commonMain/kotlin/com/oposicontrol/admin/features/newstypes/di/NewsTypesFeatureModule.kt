package com.oposicontrol.admin.features.newstypes.di

import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesViewModel
import org.koin.dsl.module

val newsTypesFeatureModule = module {
    factory { NewsTypesViewModel(get(), get(), get()) }
}
