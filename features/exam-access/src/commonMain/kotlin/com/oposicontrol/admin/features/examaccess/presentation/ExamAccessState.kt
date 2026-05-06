package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class ExamAccessState(
    val accesses: UiAsync<List<ExamAccessItem>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorUserName: String = "",
    val editorExamTitle: String = "",
    val editorAccessType: String = "manual",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
