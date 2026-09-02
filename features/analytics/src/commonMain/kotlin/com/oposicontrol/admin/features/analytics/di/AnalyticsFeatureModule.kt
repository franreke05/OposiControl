package com.oposicontrol.admin.features.analytics.di

import com.oposicontrol.admin.features.analytics.presentation.ProductAnalyticsViewModel
import org.koin.dsl.module

val analyticsFeatureModule = module {
    factory { ProductAnalyticsViewModel(dispatcherProvider = get(), repository = get(), analyticsTracker = get()) }
}
