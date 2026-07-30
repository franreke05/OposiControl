package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError

/** Immutable exam-access state rendered by [ExamAccessContent]. */
data class ExamAccessState(
    val accesses: UiAsync<List<ExamAccessItem>> = UiAsync.Loading,
    val userCatalog: UiAsync<List<ExamAccessUserOption>> = UiAsync.Loading,
    val examCatalog: UiAsync<List<ExamAccessExamOption>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: ExamAccessId? = null,
    val isCreating: Boolean = false,
    val editorUserId: UserId? = null,
    val editorExamId: ExamId? = null,
    val editorUserName: String = "",
    val editorExamTitle: String = "",
    val editorAccessType: String = "manual",
    val editorIsActive: Boolean = true,
    val editorVersion: Long = 1,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
)
