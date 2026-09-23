package com.oposicontrol.admin.features.rankings.di

import com.oposicontrol.admin.features.rankings.presentation.RankingsViewModel
import org.koin.dsl.module

val rankingsFeatureModule = module {
    factory { RankingsViewModel(dispatcherProvider = get(), repository = get(), analyticsTracker = get()) }
}
