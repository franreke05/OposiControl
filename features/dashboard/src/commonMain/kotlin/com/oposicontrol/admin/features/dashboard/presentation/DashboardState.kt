package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.DashboardMetrics

data class DashboardState(
    val metrics: UiAsync<DashboardMetrics> = UiAsync.Loading,
)
