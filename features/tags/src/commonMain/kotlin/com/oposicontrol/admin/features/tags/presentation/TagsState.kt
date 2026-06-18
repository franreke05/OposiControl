package com.oposicontrol.admin.features.tags.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Tag

data class TagsState(
    val items: UiAsync<List<Tag>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorName: String = "",
    val editorSlug: String = "",
    val editorOrigin: String = "manual",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
