package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.DashboardMetricsDto
import com.oposicontrol.admin.domain.model.DashboardMetrics
import kotlinx.datetime.Instant

fun DashboardMetricsDto.toDomain(): DashboardMetrics {
    return DashboardMetrics(
        totalSpecialties = totalSpecialties,
        totalExams = totalExams,
        publishedExams = publishedExams,
        totalQuestions = totalQuestions,
        lastUpdatedAt = Instant.parse(lastUpdatedAt),
        draftExams = draftExams ?: 0,
        questionsWithoutResources = questionsWithoutResources ?: 0,
        linkedResources = linkedResources ?: 0,
        pendingResources = pendingResources ?: 0,
        unlinkedResources = unlinkedResources ?: 0,
        publishedNews = publishedNews ?: 0,
        draftNews = draftNews ?: 0,
        activeStoreItems = activeStoreItems ?: 0,
        activeOffers = activeOffers ?: 0,
        scheduledOffers = scheduledOffers ?: 0,
        activeBurstCampaigns = activeBurstCampaigns ?: 0,
        activeLimitedResources = activeLimitedResources ?: 0,
        nextCommerceExpiryLabel = nextCommerceExpiryLabel ?: "Sin vencimientos programados",
        latestImportLabel = latestImportLabel ?: "Sin registro de importaciones",
    )
}
