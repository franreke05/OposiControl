package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.DashboardMetricsDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class DashboardApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getMetrics(): DashboardMetricsDto {
        return client.get("${appConfig.baseUrl}/dashboard/metrics") {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }.body()
    }
}
