package com.oposicontrol.admin.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CommerceStatus {
    @SerialName("draft")
    DRAFT,

    @SerialName("active")
    ACTIVE,

    @SerialName("scheduled")
    SCHEDULED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("archived")
    ARCHIVED,
}

@Serializable
enum class StoreItemType {
    @SerialName("consumable")
    CONSUMABLE,

    @SerialName("protector")
    PROTECTOR,

    @SerialName("streak")
    STREAK,

    @SerialName("potentiator")
    POTENTIATOR,

    @SerialName("exam_access")
    EXAM_ACCESS,

    @SerialName("resource")
    RESOURCE,
}

@Serializable
enum class BurstCampaignType {
    @SerialName("daily_streak")
    DAILY_STREAK,

    @SerialName("exam_burst")
    EXAM_BURST,

    @SerialName("ticket_multiplier")
    TICKET_MULTIPLIER,

    @SerialName("resource_unlock")
    RESOURCE_UNLOCK,
}

@Serializable
data class StoreItemResponse(
    val id: Long,
    val name: String,
    val description: String,
    val type: StoreItemType,
    val priceTickets: Long,
    val status: CommerceStatus,
    val sortOrder: Int,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpsertStoreItemRequest(
    val name: String,
    val description: String,
    val type: StoreItemType,
    val priceTickets: Long,
    val status: CommerceStatus,
    val sortOrder: Int,
    val expectedVersion: Long? = null,
)

@Serializable
data class StoreOfferResponse(
    val id: Long,
    val itemId: Long,
    val title: String,
    val discountPercent: Int? = null,
    val specialPriceTickets: Long? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: CommerceStatus,
    val version: Long,
)

@Serializable
data class UpsertStoreOfferRequest(
    val itemId: Long,
    val title: String,
    val discountPercent: Int? = null,
    val specialPriceTickets: Long? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: CommerceStatus,
    val expectedVersion: Long? = null,
)

@Serializable
data class LimitedResourceResponse(
    val id: Long,
    val itemId: Long,
    val resourceId: Long,
    val title: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val accessDurationHours: Int,
    val status: CommerceStatus,
    val version: Long,
)

@Serializable
data class UpsertLimitedResourceRequest(
    val itemId: Long,
    val resourceId: Long,
    val title: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val accessDurationHours: Int,
    val status: CommerceStatus,
    val expectedVersion: Long? = null,
)

@Serializable
data class BurstCampaignResponse(
    val id: Long,
    val title: String,
    val description: String,
    val type: BurstCampaignType,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val multiplier: Double,
    val rewardTickets: Long,
    val linkedExamId: Long? = null,
    val linkedStoreItemId: Long? = null,
    val status: CommerceStatus,
    val version: Long,
)

@Serializable
data class UpsertBurstCampaignRequest(
    val title: String,
    val description: String,
    val type: BurstCampaignType,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val multiplier: Double,
    val rewardTickets: Long,
    val linkedExamId: Long? = null,
    val linkedStoreItemId: Long? = null,
    val status: CommerceStatus,
    val expectedVersion: Long? = null,
)

@Serializable
data class SearchTagResponse(
    val id: Long,
    val name: String,
    val slug: String,
    val origin: String,
)

@Serializable
data class SearchResultResponse(
    val entityType: String,
    val entityId: String,
    val section: String,
    val title: String,
    val summary: String,
    val tags: List<SearchTagResponse>,
    val updatedAt: String,
)

@Serializable
data class DashboardSummaryResponse(
    val totalSpecialties: Int,
    val totalExams: Int,
    val draftExams: Int,
    val publishedExams: Int,
    val totalQuestions: Int,
    val questionsWithoutResources: Int,
    val activeStoreItems: Int,
    val openSupportConversations: Int,
    val generatedAt: String,
)
