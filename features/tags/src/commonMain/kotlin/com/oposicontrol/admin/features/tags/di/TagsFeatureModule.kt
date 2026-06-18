package com.oposicontrol.admin.features.tags.di

import com.oposicontrol.admin.domain.usecase.tags.DeleteTagUseCase
import com.oposicontrol.admin.domain.usecase.tags.ObserveTagsUseCase
import com.oposicontrol.admin.domain.usecase.tags.RefreshTagsUseCase
import com.oposicontrol.admin.domain.usecase.tags.UpsertTagUseCase
import com.oposicontrol.admin.features.tags.presentation.TagsViewModel
import org.koin.dsl.module

val tagsFeatureModule = module {
    factory { ObserveTagsUseCase(get()) }
    factory { RefreshTagsUseCase(get()) }
    factory { UpsertTagUseCase(get()) }
    factory { DeleteTagUseCase(get()) }
    factory { TagsViewModel(get(), get(), get(), get(), get(), get()) }
}
