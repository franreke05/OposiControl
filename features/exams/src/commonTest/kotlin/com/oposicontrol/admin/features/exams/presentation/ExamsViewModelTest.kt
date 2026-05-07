package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamResourceSummary
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionResourceAudit
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadExamCoverUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ExamsViewModelTest {
    @Test
    fun `selecting exam loads related questions`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = ExamsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(repository),
            observeExamsUseCase = ObserveExamsUseCase(repository),
            refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(repository),
            upsertSpecialtyUseCase = UpsertSpecialtyUseCase(repository),
            deleteSpecialtyUseCase = DeleteSpecialtyUseCase(repository),
            refreshExamsUseCase = RefreshExamsUseCase(repository),
            getExamQuestionsUseCase = GetExamQuestionsUseCase(repository),
            getExamResourceAuditUseCase = GetExamResourceAuditUseCase(repository),
            upsertExamUseCase = UpsertExamUseCase(repository),
            deleteExamUseCase = DeleteExamUseCase(repository),
            upsertQuestionUseCase = UpsertQuestionUseCase(repository),
            importQuestionsUseCase = ImportQuestionsUseCase(repository),
            deleteQuestionUseCase = DeleteQuestionUseCase(repository),
            moveQuestionUseCase = MoveQuestionUseCase(repository),
            uploadExamCoverUseCase = UploadExamCoverUseCase(repository),
            uploadQuestionResourceUseCase = UploadQuestionResourceUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        val state = viewModel.state.value
        val questions = (state.questions as UiAsync.Success).value
        assertEquals("exam-tcae-marzo", state.selectedExamId)
        assertEquals(2, questions.size)
        assertEquals("question-tcae-1", questions.first().id)
    }
}

private class ExamsTestRepository : ContentRepository {
    private val now = Instant.parse("2026-04-15T09:00:00Z")
    private val specialties = MutableStateFlow(
        listOf(
            Specialty(
                id = "specialty-tcae",
                name = "TCAE",
                description = "",
                isActive = true,
                sortOrder = 0,
                linkedExams = 1,
                createdAt = now,
                updatedAt = now,
            ),
        ),
    )
    private val exams = MutableStateFlow(
        listOf(
            Exam(
                id = "exam-tcae-marzo",
                specialtyId = "specialty-tcae",
                specialtyName = "TCAE",
                title = "TCAE Marzo",
                description = "",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                sortOrder = 0,
                questionCount = 2,
                createdAt = now,
                updatedAt = now,
            ),
        ),
    )
    private val questions = mutableMapOf(
        "exam-tcae-marzo" to mutableListOf(
            question("question-tcae-1", "exam-tcae-marzo", 0),
            question("question-tcae-2", "exam-tcae-marzo", 1),
        ),
    )

    override fun observeSpecialties(): Flow<List<Specialty>> = specialties
    override fun observeExams(): Flow<List<Exam>> = exams
    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> = AppResult.Success(specialties.value)
    override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(exams.value)
    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> {
        return AppResult.Success(questions[examId].orEmpty())
    }
    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> {
        val rows = questions[examId].orEmpty()
        val audits = rows.map { question ->
            QuestionResourceAudit(
                questionId = question.id,
                questionTitle = question.title,
                resourceCount = question.resources.size,
                pendingResourceCount = 0,
                hasLinkedResources = question.resources.isNotEmpty(),
            )
        }
        return AppResult.Success(
            ExamResourceAudit(
                summary = ExamResourceSummary(
                    totalQuestions = rows.size,
                    questionsWithResources = audits.count { it.hasLinkedResources },
                    questionsWithoutResources = audits.count { !it.hasLinkedResources },
                    linkedResources = rows.sumOf { it.resources.size },
                    pendingResources = 0,
                    unlinkedResources = 0,
                ),
                questions = audits,
                unlinkedResources = emptyList(),
            ),
        )
    }
    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> = AppResult.Success(specialty)
    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun upsertExam(exam: Exam): AppResult<Exam> = AppResult.Success(exam)
    override suspend fun deleteExam(examId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> {
        val saved = exams.value.first { it.id == examId }.copy(
            coverUrl = "https://storage.local/exam-covers/$examId/cover.png",
            coverStoragePath = "exam-covers/$examId/cover.png",
            coverMimeType = "image/png",
        )
        exams.value = exams.value.map { if (it.id == examId) saved else it }
        return AppResult.Success(saved)
    }
    override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> {
        val bucket = questions.getOrPut(question.examId) { mutableListOf() }
        bucket.removeAll { it.id == question.id }
        bucket += question
        return AppResult.Success(question)
    }
    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> {
        val bucket = this.questions.getOrPut(examId) { mutableListOf() }
        bucket += questions
        return AppResult.Success(questions)
    }
    override suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>> = AppResult.Success(questions[examId].orEmpty())
    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource> {
        return AppResult.Success(
            QuestionResource(
                id = resourceId,
                questionId = questionId,
                type = type,
                title = title,
                textBody = null,
                storagePath = localPathOrUrl,
                publicUrl = localPathOrUrl,
                mimeType = null,
                sortOrder = 0,
            ),
        )
    }

    private fun question(id: String, examId: String, order: Int): ExamQuestion {
        return ExamQuestion(
            id = id,
            examId = examId,
            title = "Pregunta ${order + 1}",
            statement = "Enunciado ${order + 1}",
            sortOrder = order,
            options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
                QuestionOption(
                    id = "$id-option-$label",
                    questionId = id,
                    label = label,
                    content = "Opcion $label",
                    isCorrect = index == 0,
                    sortOrder = index,
                )
            },
            resources = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
    }
}
