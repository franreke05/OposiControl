package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ForumReportDetail

data class ReportDetailState(
    val report: UiAsync<ForumReportDetail> = UiAsync.Loading,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
