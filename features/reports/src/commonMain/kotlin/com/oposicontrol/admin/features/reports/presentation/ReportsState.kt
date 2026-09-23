package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ForumReport

data class ReportsState(
    val mainFilter: ReportsMainFilter = ReportsMainFilter.PENDING,
    val secondaryFilters: ReportSecondaryFilters = ReportSecondaryFilters(),
    val reports: UiAsync<List<ForumReport>> = UiAsync.Loading,
    val refreshing: Boolean = false,
)
