package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class Specialty(
    val id: String,
    val name: String,
    val description: String,
    val isActive: Boolean,
    val sortOrder: Int,
    val linkedExams: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Exam(
    val id: String,
    val specialtyId: String,
    val specialtyName: String,
    val title: String,
    val description: String,
    val status: ExamStatus,
    val publishFrom: Instant?,
    val sortOrder: Int,
    val questionCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ExamStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
}

data class ExamQuestion(
    val id: String,
    val examId: String,
    val title: String,
    val statement: String,
    val sortOrder: Int,
    val options: List<QuestionOption>,
    val resources: List<QuestionResource>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class QuestionOption(
    val id: String,
    val questionId: String,
    val label: String,
    val content: String,
    val isCorrect: Boolean,
    val sortOrder: Int,
)

data class QuestionResource(
    val id: String,
    val questionId: String,
    val type: QuestionResourceType,
    val title: String,
    val textBody: String?,
    val storagePath: String?,
    val publicUrl: String?,
    val mimeType: String?,
    val sortOrder: Int,
)

data class ExamResourceAudit(
    val summary: ExamResourceSummary,
    val questions: List<QuestionResourceAudit>,
    val unlinkedResources: List<UnlinkedResource>,
)

data class ExamResourceSummary(
    val totalQuestions: Int,
    val questionsWithResources: Int,
    val questionsWithoutResources: Int,
    val linkedResources: Int,
    val pendingResources: Int,
    val unlinkedResources: Int,
)

data class QuestionResourceAudit(
    val questionId: String,
    val questionTitle: String,
    val resourceCount: Int,
    val pendingResourceCount: Int,
    val hasLinkedResources: Boolean,
)

data class UnlinkedResource(
    val id: String,
    val path: String,
    val type: QuestionResourceType,
    val reason: UnlinkedResourceReason,
    val suggestedQuestionId: String?,
    val updatedAt: Instant?,
)

enum class UnlinkedResourceReason {
    STORAGE_WITHOUT_DATABASE_ROW,
    DATABASE_ROW_WITHOUT_QUESTION,
    LOCAL_PENDING_NOT_SAVED,
}

enum class QuestionResourceType {
    IMAGE,
    AUDIO,
    VIDEO,
    TEXT,
    DOCUMENT,
}

enum class QuestionMoveDirection {
    UP,
    DOWN,
}
