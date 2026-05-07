package com.oposicontrol.admin.domain.usecase.commerce

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.BurstCampaignType
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreOffer
import com.oposicontrol.admin.domain.repository.CommerceRepository

class ObserveStoreItemsUseCase(private val repository: CommerceRepository) {
    operator fun invoke() = repository.observeStoreItems()
}

class ObserveStoreOffersUseCase(private val repository: CommerceRepository) {
    operator fun invoke() = repository.observeStoreOffers()
}

class ObserveLimitedResourcesUseCase(private val repository: CommerceRepository) {
    operator fun invoke() = repository.observeLimitedResources()
}

class ObserveBurstCampaignsUseCase(private val repository: CommerceRepository) {
    operator fun invoke() = repository.observeBurstCampaigns()
}

class RefreshCommerceUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke() = repository.refreshCommerce()
}

class UpsertStoreItemUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(item: StoreItem): AppResult<StoreItem> {
        if (item.name.isBlank()) return AppResult.Failure(AppError.Validation("El articulo necesita nombre."))
        if (item.priceTickets < 0) return AppResult.Failure(AppError.Validation("El precio no puede ser negativo."))
        return repository.upsertStoreItem(item)
    }
}

class UpsertStoreOfferUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(offer: StoreOffer): AppResult<StoreOffer> {
        if (offer.title.isBlank()) return AppResult.Failure(AppError.Validation("La oferta necesita titulo."))
        if (offer.discountPercent == null && offer.specialPriceTickets == null) {
            return AppResult.Failure(AppError.Validation("Define descuento o precio especial."))
        }
        return repository.upsertStoreOffer(offer)
    }
}

class UpsertLimitedResourceUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(window: LimitedResourceWindow): AppResult<LimitedResourceWindow> {
        if (window.title.isBlank()) return AppResult.Failure(AppError.Validation("El recurso temporal necesita titulo."))
        if (window.accessDurationHours <= 0) return AppResult.Failure(AppError.Validation("La duracion debe ser mayor que 0 horas."))
        return repository.upsertLimitedResource(window)
    }
}

class UpsertBurstCampaignUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(campaign: BurstCampaign): AppResult<BurstCampaign> {
        if (campaign.title.isBlank()) return AppResult.Failure(AppError.Validation("La rafaga necesita titulo."))
        if (campaign.type != BurstCampaignType.EXAM_BURST) return AppResult.Failure(AppError.Validation("La rafaga debe ser de tipo examen."))
        if (campaign.linkedExamId.isNullOrBlank()) return AppResult.Failure(AppError.Validation("La rafaga necesita examen vinculado."))
        if (campaign.multiplier < 1.0) return AppResult.Failure(AppError.Validation("El multiplicador minimo es 1.0."))
        return repository.upsertBurstCampaign(campaign)
    }
}
