package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.DashboardMetrics

/** Immutable dashboard state rendered by DashboardContent. */
data class DashboardState(
    val metrics: UiAsync<DashboardMetrics> = UiAsync.Loading,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
)
