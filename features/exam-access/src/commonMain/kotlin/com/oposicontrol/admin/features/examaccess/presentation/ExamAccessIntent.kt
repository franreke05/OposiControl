package com.oposicontrol.admin.features.examaccess.presentation

sealed interface ExamAccessIntent {
    data object CreateNew : ExamAccessIntent
    data object Refresh : ExamAccessIntent
    data object Save : ExamAccessIntent
    data class Select(val id: String) : ExamAccessIntent
    data class UpdateSearch(val value: String) : ExamAccessIntent
    data class UpdateUserName(val value: String) : ExamAccessIntent
    data class UpdateExamTitle(val value: String) : ExamAccessIntent
    data class UpdateAccessType(val value: String) : ExamAccessIntent
    data class ToggleActive(val value: Boolean) : ExamAccessIntent
}
