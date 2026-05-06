package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Specialty

data class SpecialtiesState(
    val items: UiAsync<List<Specialty>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorName: String = "",
    val editorDescription: String = "",
    val editorSortOrder: String = "0",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
