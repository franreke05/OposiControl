package com.oposicontrol.admin.domain.usecase.dashboard

import com.oposicontrol.admin.domain.repository.DashboardRepository

class GetDashboardMetricsUseCase(
    private val repository: DashboardRepository,
) {
    suspend operator fun invoke() = repository.getMetrics()
}
