package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.DashboardMetrics

interface DashboardRepository {
    suspend fun getMetrics(): AppResult<DashboardMetrics>
}
