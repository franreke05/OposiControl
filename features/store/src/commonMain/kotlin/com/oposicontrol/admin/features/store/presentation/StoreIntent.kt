package com.oposicontrol.admin.features.store.presentation

sealed interface StoreIntent {
    data object CreateNew : StoreIntent
    data object Save : StoreIntent
    data object SaveOffer : StoreIntent
    data object SaveLimitedResource : StoreIntent
    data object SaveBurst : StoreIntent
    data class Select(val id: String) : StoreIntent
    data class UpdateSearch(val value: String) : StoreIntent
    data class UpdateName(val value: String) : StoreIntent
    data class UpdateDescription(val value: String) : StoreIntent
    data class UpdateType(val value: String) : StoreIntent
    data class UpdatePriceTickets(val value: String) : StoreIntent
    data class UpdateStatus(val value: String) : StoreIntent
    data class UpdateOfferTitle(val value: String) : StoreIntent
    data class UpdateOfferDiscountPercent(val value: String) : StoreIntent
    data class UpdateOfferSpecialPriceTickets(val value: String) : StoreIntent
    data class UpdateOfferStartsAt(val value: String) : StoreIntent
    data class UpdateOfferEndsAt(val value: String) : StoreIntent
    data class UpdateLimitedTitle(val value: String) : StoreIntent
    data class UpdateLimitedResourceId(val value: String) : StoreIntent
    data class UpdateLimitedStartsAt(val value: String) : StoreIntent
    data class UpdateLimitedEndsAt(val value: String) : StoreIntent
    data class UpdateLimitedAccessHours(val value: String) : StoreIntent
    data class UpdateBurstTitle(val value: String) : StoreIntent
    data class UpdateBurstDescription(val value: String) : StoreIntent
    data class UpdateBurstType(val value: String) : StoreIntent
    data class UpdateBurstMultiplier(val value: String) : StoreIntent
    data class UpdateBurstRewardTickets(val value: String) : StoreIntent
    data class UpdateBurstStartsAt(val value: String) : StoreIntent
    data class UpdateBurstEndsAt(val value: String) : StoreIntent
}
