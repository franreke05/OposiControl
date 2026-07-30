package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class StoreItem(
    val id: String,
    val name: String,
    val description: String,
    val type: StoreItemType,
    val priceTickets: Int,
    val status: CommerceStatus,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 1,
)

data class StoreOffer(
    val id: String,
    val itemId: String,
    val title: String,
    val discountPercent: Int?,
    val specialPriceTickets: Int?,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val status: CommerceStatus,
    val version: Long = 1,
)

data class LimitedResourceWindow(
    val id: String,
    val itemId: String,
    val resourceId: String,
    val title: String,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val accessDurationHours: Int,
    val status: CommerceStatus,
    val version: Long = 1,
)

data class BurstCampaign(
    val id: String,
    val title: String,
    val description: String,
    val type: BurstCampaignType,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val multiplier: Double,
    val rewardTickets: Int,
    val linkedExamId: String?,
    val linkedStoreItemId: String?,
    val status: CommerceStatus,
    val version: Long = 1,
)

enum class StoreItemType {
    CONSUMABLE,
    PROTECTOR,
    STREAK,
    POTENTIATOR,
    EXAM_ACCESS,
    RESOURCE,
}

enum class BurstCampaignType {
    DAILY_STREAK,
    EXAM_BURST,
    TICKET_MULTIPLIER,
    RESOURCE_UNLOCK,
}

enum class CommerceStatus {
    DRAFT,
    ACTIVE,
    SCHEDULED,
    EXPIRED,
    ARCHIVED,
}
