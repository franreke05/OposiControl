package com.oposicontrol.admin.features.news.di

import com.oposicontrol.admin.domain.usecase.news.ArchiveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.DeleteNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.LoadMoreNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.LoadNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ObserveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.PublishNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.RefreshNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.SaveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ScheduleNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.UploadNewsAssetUseCase
import com.oposicontrol.admin.domain.usecase.news.ValidateNewsDraftUseCase
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import org.koin.dsl.module

val newsFeatureModule = module {
    factory { ObserveNewsUseCase(get()) }
    factory { RefreshNewsUseCase(get()) }
    factory { LoadMoreNewsUseCase(get()) }
    factory { LoadNewsUseCase(get()) }
    factory { SaveNewsUseCase(get()) }
    factory { DeleteNewsUseCase(get()) }
    factory { ValidateNewsDraftUseCase() }
    factory { PublishNewsUseCase(get(), get()) }
    factory { ScheduleNewsUseCase(get(), get()) }
    factory { UploadNewsAssetUseCase(get()) }
    factory { ArchiveNewsUseCase(get()) }
    single {
        NewsViewModel(
            dispatcherProvider = get(),
            observeNewsUseCase = get(),
            refreshNewsUseCase = get(),
            loadMoreNewsUseCase = get(),
            loadNewsUseCase = get(),
            saveNewsUseCase = get(),
            deleteNewsUseCase = get(),
            validateNewsDraftUseCase = get(),
            publishNewsUseCase = get(),
            scheduleNewsUseCase = get(),
            uploadNewsAssetUseCase = get(),
            archiveNewsUseCase = get(),
            analyticsTracker = get(),
            draftLocalDataSource = get(),
            contentRepository = get(),
            operationsRepository = get(),
        )
    }
}
