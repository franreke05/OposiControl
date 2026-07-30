package com.oposicontrol.admin.features.examaccess.presentation

/** User actions accepted by the exam-access state holder. */
sealed interface ExamAccessIntent {
    data object CreateNew : ExamAccessIntent
    data object Refresh : ExamAccessIntent
    data object Save : ExamAccessIntent
    data class Select(val id: ExamAccessId) : ExamAccessIntent
    data class SelectUser(val id: UserId, val displayName: String) : ExamAccessIntent
    data class SelectExam(val id: ExamId, val displayTitle: String) : ExamAccessIntent
    data class UpdateSearch(val value: String) : ExamAccessIntent
    /** Compatibility input for the mobile shell; it never resolves or assigns a user ID. */
    data class UpdateUserName(val value: String) : ExamAccessIntent
    /** Compatibility input for the mobile shell; it never resolves or assigns an exam ID. */
    data class UpdateExamTitle(val value: String) : ExamAccessIntent
    data class UpdateAccessType(val value: String) : ExamAccessIntent
    data class ToggleActive(val value: Boolean) : ExamAccessIntent
}
