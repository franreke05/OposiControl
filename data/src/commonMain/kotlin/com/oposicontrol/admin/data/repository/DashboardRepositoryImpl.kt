package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.data.remote.source.DashboardDataSource
import com.oposicontrol.admin.domain.repository.DashboardRepository

class DashboardRepositoryImpl(
    private val remote: DashboardDataSource,
) : DashboardRepository {
    override suspend fun getMetrics() = remote.getMetrics()
}
