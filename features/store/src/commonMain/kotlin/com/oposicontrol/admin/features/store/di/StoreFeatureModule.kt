package com.oposicontrol.admin.features.store.di

import com.oposicontrol.admin.domain.usecase.commerce.ObserveBurstCampaignsUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveLimitedResourcesUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveStoreItemsUseCase
import com.oposicontrol.admin.domain.usecase.commerce.ObserveStoreOffersUseCase
import com.oposicontrol.admin.domain.usecase.commerce.RefreshCommerceUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertBurstCampaignUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertLimitedResourceUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertStoreItemUseCase
import com.oposicontrol.admin.domain.usecase.commerce.UpsertStoreOfferUseCase
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogViewModel
import com.oposicontrol.admin.features.store.presentation.MarketViewModel
import com.oposicontrol.admin.features.store.presentation.StoreViewModel
import org.koin.dsl.module

val storeFeatureModule = module {
    factory { CommercialCatalogViewModel(dispatcherProvider = get(), repository = get(), analyticsTracker = get()) }
    factory { MarketViewModel(dispatcherProvider = get(), repository = get(), analyticsTracker = get()) }
    factory { ObserveStoreItemsUseCase(get()) }
    factory { ObserveStoreOffersUseCase(get()) }
    factory { ObserveLimitedResourcesUseCase(get()) }
    factory { ObserveBurstCampaignsUseCase(get()) }
    factory { RefreshCommerceUseCase(get()) }
    factory { UpsertStoreItemUseCase(get()) }
    factory { UpsertStoreOfferUseCase(get()) }
    factory { UpsertLimitedResourceUseCase(get()) }
    factory { UpsertBurstCampaignUseCase(get()) }
    factory {
        StoreViewModel(
            dispatcherProvider = get(),
            observeStoreItemsUseCase = get(),
            observeStoreOffersUseCase = get(),
            observeLimitedResourcesUseCase = get(),
            observeBurstCampaignsUseCase = get(),
            refreshCommerceUseCase = get(),
            upsertStoreItemUseCase = get(),
            upsertStoreOfferUseCase = get(),
            upsertLimitedResourceUseCase = get(),
            upsertBurstCampaignUseCase = get(),
            analyticsTracker = get(),
        )
    }
}
