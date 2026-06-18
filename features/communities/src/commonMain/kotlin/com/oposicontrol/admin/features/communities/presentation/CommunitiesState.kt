package com.oposicontrol.admin.features.communities.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Community

data class CommunitiesState(
    val items: UiAsync<List<Community>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorName: String = "",
    val editorCode: String = "",
    val editorSlug: String = "",
    val editorSortOrder: String = "0",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
