package com.oposicontrol.admin.features.news.di

import com.oposicontrol.admin.domain.usecase.news.DeleteNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ArchiveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ObserveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.PublishNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.RefreshNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ScheduleNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.UploadNewsAssetUseCase
import com.oposicontrol.admin.domain.usecase.news.UpsertNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ValidateNewsDraftUseCase
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import org.koin.dsl.module

val newsFeatureModule = module {
    factory { ObserveNewsUseCase(get()) }
    factory { RefreshNewsUseCase(get()) }
    factory { UpsertNewsUseCase(get()) }
    factory { DeleteNewsUseCase(get()) }
    factory { ValidateNewsDraftUseCase() }
    factory { PublishNewsUseCase(get(), get()) }
    factory { ScheduleNewsUseCase(get(), get()) }
    factory { UploadNewsAssetUseCase(get()) }
    factory { ArchiveNewsUseCase(get()) }
    factory { NewsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
