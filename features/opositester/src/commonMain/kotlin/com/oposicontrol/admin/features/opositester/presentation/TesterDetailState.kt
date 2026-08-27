package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.TesterReportDetail

data class TesterDetailState(
    val report: UiAsync<TesterReportDetail> = UiAsync.Loading,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
