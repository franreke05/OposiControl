package com.oposicontrol.admin.features.opositester.presentation

sealed interface TesterIntent {
    data object Load : TesterIntent
    data object PullToRefresh : TesterIntent
    data object LoadMore : TesterIntent
    data class SetMainFilter(val filter: TesterMainFilter) : TesterIntent
    data class SetTypeFilter(val type: TesterTypeOption?) : TesterIntent
}
