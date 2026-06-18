package com.oposicontrol.admin.features.communities.di

import com.oposicontrol.admin.domain.usecase.communities.DeleteCommunityUseCase
import com.oposicontrol.admin.domain.usecase.communities.ObserveCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.RefreshCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.UpsertCommunityUseCase
import com.oposicontrol.admin.features.communities.presentation.CommunitiesViewModel
import org.koin.dsl.module

val communitiesFeatureModule = module {
    factory { ObserveCommunitiesUseCase(get()) }
    factory { RefreshCommunitiesUseCase(get()) }
    factory { UpsertCommunityUseCase(get()) }
    factory { DeleteCommunityUseCase(get()) }
    factory { CommunitiesViewModel(get(), get(), get(), get(), get(), get()) }
}
