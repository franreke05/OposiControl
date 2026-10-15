package com.oposicontrol.admin.features.store.di

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogViewModel
import com.oposicontrol.admin.features.store.presentation.CommercialPlansViewModel
import com.oposicontrol.admin.features.store.presentation.MarketViewModel
import com.oposicontrol.admin.features.store.presentation.PublishedCatalog
import com.oposicontrol.admin.features.store.presentation.PublishedExamsSource
import org.koin.dsl.module
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository

val storeFeatureModule = module {
    factory { CommercialCatalogViewModel(dispatcherProvider = get(), repository = get(), analyticsTracker = get()) }
    factory { CommercialPlansViewModel(dispatcherProvider = get(), repository = get(), analyticsTracker = get()) }
    factory {
        MarketViewModel(
            dispatcherProvider = get(),
            repository = get(),
            productsSource = { get<CommercialCatalogRepository>().getProducts() },
            examsSource = PublishedExamsSource {
                when (val exams = get<ContentRepository>().refreshAllExams()) {
                    is AppResult.Failure -> exams
                    // Las comunidades solo eligen la ilustración: si fallan, el
                    // escaparate se enseña igual con la portada genérica.
                    is AppResult.Success -> AppResult.Success(
                        PublishedCatalog(
                            exams = exams.value,
                            communities = (get<CommunitiesRepository>().refreshCommunities() as? AppResult.Success)
                                ?.value.orEmpty(),
                        ),
                    )
                }
            },
            analyticsTracker = get(),
        )
    }
    // La tienda heredada (StoreScreen/StoreViewModel y sus casos de uso) se retiró el 2026-10-15: ninguna ruta la
    // mostraba desde que Catálogo es el único sitio donde se edita lo que vende la tienda.
}
