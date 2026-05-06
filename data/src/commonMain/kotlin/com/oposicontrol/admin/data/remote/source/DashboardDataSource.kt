package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.DashboardMetrics

interface DashboardDataSource {
    suspend fun getMetrics(): AppResult<DashboardMetrics>
}
