package com.oposicontrol.admin.features.store.presentation

data class StoreItemRow(
    val id: String,
    val name: String,
    val type: String,
    val priceTickets: Int,
    val status: String,
)

data class StoreOfferRow(
    val id: String,
    val itemId: String,
    val title: String,
    val benefit: String,
    val window: String,
    val status: String,
)

data class LimitedResourceRow(
    val id: String,
    val itemId: String,
    val title: String,
    val durationHours: Int,
    val window: String,
    val status: String,
)

data class BurstCampaignRow(
    val id: String,
    val title: String,
    val type: String,
    val reward: String,
    val linkedItemId: String?,
    val status: String,
)
