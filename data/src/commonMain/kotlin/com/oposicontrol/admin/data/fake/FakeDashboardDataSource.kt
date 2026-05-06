package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.DashboardDataSource
import com.oposicontrol.admin.domain.model.DashboardActivityEvent
import com.oposicontrol.admin.domain.model.DashboardAlert
import com.oposicontrol.admin.domain.model.DashboardAlertSeverity
import com.oposicontrol.admin.domain.model.DashboardMetrics
import kotlinx.datetime.Instant

class FakeDashboardDataSource(
    private val store: FakeAdminStore,
    private val behavior: FakeBehaviorPolicy,
) : DashboardDataSource {
    override suspend fun getMetrics(): AppResult<DashboardMetrics> {
        return behavior.execute("dashboard.metrics") {
            DashboardMetrics(
                totalSpecialties = 2,
                totalExams = 2,
                publishedExams = 1,
                totalQuestions = 3,
                lastUpdatedAt = Instant.parse("2026-04-14T09:00:00Z"),
                draftExams = 1,
                questionsWithoutResources = 2,
                linkedResources = 4,
                pendingResources = 1,
                unlinkedResources = 1,
                publishedNews = 2,
                draftNews = 1,
                activeStoreItems = 3,
                activeOffers = 1,
                scheduledOffers = 1,
                activeBurstCampaigns = 1,
                activeLimitedResources = 1,
                nextCommerceExpiryLabel = "Oferta activa termina hoy",
                latestImportLabel = "Importacion demo revisada",
                alerts = listOf(
                    DashboardAlert(
                        id = "questions_without_resources",
                        title = "2 preguntas sin recurso",
                        detail = "Revisa el banco antes de publicar el examen.",
                        actionLabel = "Auditar",
                        severity = DashboardAlertSeverity.WARNING,
                    ),
                    DashboardAlert(
                        id = "unlinked_resources",
                        title = "1 recurso sin enlazar",
                        detail = "Hay archivos pendientes de asociar a una pregunta.",
                        actionLabel = "Enlazar",
                        severity = DashboardAlertSeverity.CRITICAL,
                    ),
                ),
                recentEvents = listOf(
                    DashboardActivityEvent(
                        id = "demo_import",
                        title = "Importacion masiva",
                        detail = "3 preguntas procesadas en entorno demo",
                        occurredAt = Instant.parse("2026-04-14T08:42:00Z"),
                    ),
                    DashboardActivityEvent(
                        id = "demo_offer",
                        title = "Oferta activada",
                        detail = "Pack de tickets disponible en tienda",
                        occurredAt = Instant.parse("2026-04-14T08:20:00Z"),
                    ),
                ),
            )
        }
    }
}
