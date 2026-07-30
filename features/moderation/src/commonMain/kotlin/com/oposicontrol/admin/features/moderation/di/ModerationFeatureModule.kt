package com.oposicontrol.admin.features.moderation.di

import com.oposicontrol.admin.features.moderation.presentation.ModerationViewModel
import org.koin.dsl.module

val moderationFeatureModule = module {
    factory {
        ModerationViewModel(
            dispatcherProvider = get(),
            repository = get(),
            analyticsTracker = get(),
            logger = get(),
        )
    }
}
