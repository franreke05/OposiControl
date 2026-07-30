package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Specialty

/** Immutable specialties state rendered by [SpecialtiesContent]. */
data class SpecialtiesState(
    val items: UiAsync<List<Specialty>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: SpecialtyId? = null,
    val isCreating: Boolean = false,
    val editorName: String = "",
    val editorDescription: String = "",
    val editorSortOrder: String = "0",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
)
