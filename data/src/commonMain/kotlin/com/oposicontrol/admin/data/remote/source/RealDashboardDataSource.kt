package com.oposicontrol.admin.data.remote.source

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.remote.api.DashboardApi
import com.oposicontrol.admin.domain.model.DashboardMetrics

class RealDashboardDataSource(
    private val api: DashboardApi,
    private val adapter: NetworkResultAdapter,
) : DashboardDataSource {
    override suspend fun getMetrics(): AppResult<DashboardMetrics> {
        return adapter.safeCall {
            api.getMetrics()
        }
    }
}
