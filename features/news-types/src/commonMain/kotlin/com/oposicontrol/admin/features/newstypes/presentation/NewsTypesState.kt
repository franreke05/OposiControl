package com.oposicontrol.admin.features.newstypes.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class NewsTypesState(
    val items: UiAsync<List<NewsTypeItem>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorName: String = "",
    val editorSlug: String = "",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
