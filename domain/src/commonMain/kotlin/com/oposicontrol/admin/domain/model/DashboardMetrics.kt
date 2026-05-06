package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class DashboardMetrics(
    val totalSpecialties: Int,
    val totalExams: Int,
    val publishedExams: Int,
    val totalQuestions: Int,
    val lastUpdatedAt: Instant,
    val draftExams: Int = 0,
    val questionsWithoutResources: Int = 0,
    val linkedResources: Int = 0,
    val pendingResources: Int = 0,
    val unlinkedResources: Int = 0,
    val publishedNews: Int = 0,
    val draftNews: Int = 0,
    val activeStoreItems: Int = 0,
    val activeOffers: Int = 0,
    val scheduledOffers: Int = 0,
    val activeBurstCampaigns: Int = 0,
    val activeLimitedResources: Int = 0,
    val nextCommerceExpiryLabel: String = "Sin vencimientos programados",
    val latestImportLabel: String = "Sin registro de importaciones",
    val alerts: List<DashboardAlert> = emptyList(),
    val recentEvents: List<DashboardActivityEvent> = emptyList(),
)

data class DashboardAlert(
    val id: String,
    val title: String,
    val detail: String,
    val actionLabel: String,
    val severity: DashboardAlertSeverity,
)

enum class DashboardAlertSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

data class DashboardActivityEvent(
    val id: String,
    val title: String,
    val detail: String,
    val occurredAt: Instant,
)
