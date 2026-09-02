package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.DashboardMetrics
import com.oposicontrol.admin.domain.model.DayCount
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

class DashboardApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getMetrics(): DashboardMetrics {
        val r = client.get(BackendRoutes.url(appConfig.baseUrl, BackendRoutes.ADMIN_V1_DASHBOARD)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }
        if (!r.status.isSuccess()) error("backend ${r.status.value} ${BackendRoutes.ADMIN_V1_DASHBOARD}: ${r.bodyAsText().take(240)}")
        return r.body<AdminDashboardV1Dto>().toDomain()
    }

    private fun AdminDashboardV1Dto.toDomain(): DashboardMetrics {
        return DashboardMetrics(
            totalSpecialties = totalSpecialties,
            totalExams = totalExams,
            publishedExams = publishedExams,
            totalQuestions = totalQuestions,
            lastUpdatedAt = parseInstantOrEpoch(lastUpdatedAt),
            draftExams = draftExams,
            totalAttempts = totalAttempts,
            activeUsers30d = activeUsers30d,
            avgScore = avgScore,
            globalErrorRate = globalErrorRate,
            attemptsPerDay = attemptsPerDay.map { DayCount(date = it.date, count = it.count) },
            unavailableMetrics = unavailableMetrics.toSet(),
        )
    }

    private fun parseInstantOrEpoch(value: String): Instant {
        return runCatching { Instant.parse(value) }.getOrDefault(Instant.fromEpochMilliseconds(0))
    }
}

@Serializable
private data class AdminDashboardV1Dto(
    val totalSpecialties: Int = 0,
    val totalExams: Int = 0,
    val publishedExams: Int = 0,
    val totalQuestions: Int = 0,
    val lastUpdatedAt: String = "",
    val draftExams: Int = 0,
    val totalAttempts: Int = 0,
    val activeUsers30d: Int = 0,
    val avgScore: Double = 0.0,
    val globalErrorRate: Double = 0.0,
    val attemptsPerDay: List<DayCountV1Dto> = emptyList(),
    // Backends anteriores no envían este campo; ausente = todo lo recibido es real.
    val unavailableMetrics: List<String> = emptyList(),
)

@Serializable
private data class DayCountV1Dto(val date: String = "", val count: Int = 0)
