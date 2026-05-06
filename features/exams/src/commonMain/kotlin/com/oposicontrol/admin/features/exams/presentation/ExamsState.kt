package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.Specialty

data class ExamsState(
    val specialties: UiAsync<List<Specialty>> = UiAsync.Loading,
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,
    val questions: UiAsync<List<ExamQuestion>> = UiAsync.Empty,
    val resourceAudit: UiAsync<ExamResourceAudit> = UiAsync.Idle,
    val searchQuery: String = "",
    val statusFilter: String = "all",
    val questionResourceFilter: String = "all",
    val selectedExamId: String? = null,
    val editorTitle: String = "",
    val editorDescription: String = "",
    val editorSpecialtyId: String = "",
    val editorStatus: String = "draft",
    val editorPublishFrom: String = "",
    val selectedQuestionId: String? = null,
    val editorQuestionId: String? = null,
    val editorQuestionTitle: String = "",
    val editorQuestionStatement: String = "",
    val editorOptions: List<QuestionOptionDraft> = emptyList(),
    val editorResources: List<QuestionResourceDraft> = emptyList(),
    val uploadState: UiAsync<String> = UiAsync.Idle,
    val importState: UiAsync<Int> = UiAsync.Idle,
    val importSummary: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
