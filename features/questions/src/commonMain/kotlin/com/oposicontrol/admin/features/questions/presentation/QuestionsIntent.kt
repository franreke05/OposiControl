package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.domain.model.QuestionType

sealed interface QuestionsIntent {
    data object Refresh : QuestionsIntent

    data class Search(val query: String) : QuestionsIntent
    data class SetTypeFilter(val type: QuestionType?) : QuestionsIntent
    data class SetExamFilter(val examId: String?) : QuestionsIntent
    data class SetActiveFilter(val active: Boolean?) : QuestionsIntent
    data class SetSort(val sort: QuestionsSort) : QuestionsIntent
    data class SetPageIndex(val index: Int) : QuestionsIntent
    data class SetPageSize(val size: Int) : QuestionsIntent

    data class SelectQuestion(val id: String) : QuestionsIntent
    data object StartCreate : QuestionsIntent
    data class UpdateCreateExamId(val examId: String) : QuestionsIntent

    data class UpdateStatement(val value: String) : QuestionsIntent
    data class UpdateType(val type: QuestionType) : QuestionsIntent
    data class UpdateOptionContent(val label: String, val value: String) : QuestionsIntent
    data class SetCorrectOption(val label: String) : QuestionsIntent
    data class UpdateExplanation(val value: String) : QuestionsIntent
    data class UpdateSource(val value: String) : QuestionsIntent
    data class SetActive(val active: Boolean) : QuestionsIntent

    data object RequestAssociateExam : QuestionsIntent
    data class UpdateAssociateExamId(val examId: String) : QuestionsIntent
    data class UpdateAssociatePosition(val value: String) : QuestionsIntent
    data object ConfirmAssociateExam : QuestionsIntent
    data object CancelAssociateDialog : QuestionsIntent

    data object RequestCancel : QuestionsIntent
    data object ConfirmCancel : QuestionsIntent
    data object DismissCancelConfirm : QuestionsIntent

    data object RequestDelete : QuestionsIntent
    data object ConfirmDelete : QuestionsIntent
    data object DismissDeleteConfirm : QuestionsIntent

    data object Save : QuestionsIntent
}
