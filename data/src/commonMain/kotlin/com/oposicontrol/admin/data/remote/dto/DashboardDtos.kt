package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class DashboardMetricsDto(
    val totalSpecialties: Int,
    val totalExams: Int,
    val publishedExams: Int,
    val totalQuestions: Int,
    val lastUpdatedAt: String,
    val draftExams: Int? = null,
    val questionsWithoutResources: Int? = null,
    val linkedResources: Int? = null,
    val pendingResources: Int? = null,
    val unlinkedResources: Int? = null,
    val publishedNews: Int? = null,
    val draftNews: Int? = null,
    val activeStoreItems: Int? = null,
    val activeOffers: Int? = null,
    val scheduledOffers: Int? = null,
    val activeBurstCampaigns: Int? = null,
    val activeLimitedResources: Int? = null,
    val nextCommerceExpiryLabel: String? = null,
    val latestImportLabel: String? = null,
)
