package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLinkRequest
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuestionsViewModelTest {

    private val exam = Exam(
        id = "ex-1",
        code = "EX-0012",
        specialtyId = "sp-1",
        specialtyName = "Administrativo",
        title = "Administrativo del Estado 2024",
        description = "",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = null,
        sortOrder = 0,
        questionCount = 1,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun listItem(id: String = "q-1", active: Boolean = true) = QuestionListItem(
        id = id,
        code = "PREG-000123",
        type = QuestionType.RAZONADA,
        statement = "¿Cuál es la capital de Francia?",
        active = active,
    )

    private fun detail(id: String = "q-1") = QuestionDetail(
        id = id,
        code = "PREG-000123",
        type = QuestionType.RAZONADA,
        statement = "¿Cuál es la capital de Francia?",
        options = listOf("A", "B", "C", "D").mapIndexed { i, l ->
            QuestionOption(id = "o-$l", questionId = id, label = l, content = "Opción $l", isCorrect = i == 1, sortOrder = i)
        },
        explanation = "Porque sí.",
        source = "Fuente X",
        active = true,
        difficulty = "media",
        resources = emptyList(),
    )

    private fun TestScope.buildViewModel(repository: FakeQuestionsRepository, exams: List<Exam> = listOf(exam)): QuestionsViewModel {
        return QuestionsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            questionsRepository = repository,
            contentRepository = FakeContentRepository(exams),
        )
    }

    @Test
    fun loadsTheGlobalBankOnStartup() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        val success = assertIs<UiAsync.Success<List<QuestionListItem>>>(viewModel.state.value.questions)
        assertEquals(listOf("PREG-000123"), success.value.map { it.code })
    }

    @Test
    fun backendFailureSurfacesHonestlyNotAsEmpty() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Failure(AppError.Validation("boom")))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.questions)
    }

    @Test
    fun searchFiltersClientSideOverLoadedQuestions() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem(id = "q-1"), listItem(id = "q-2").copy(code = "PREG-999999", statement = "Otra pregunta"))),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.Search("Francia"))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.filteredQuestions().size)
    }

    @Test
    fun selectingAnExamFilterSwapsTheDataSourceToTheExamScopedEndpoint() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            forExamResult = AppResult.Success(listOf(listItem().copy(examId = "ex-1", position = 3))),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, repository.listCallCount)

        viewModel.dispatch(QuestionsIntent.SetExamFilter("ex-1"))
        advanceUntilIdle()

        assertEquals(1, repository.forExamCallCount)
        assertEquals("ex-1", repository.lastExamIdRequested)
    }

    @Test
    fun selectingAQuestionLoadsDetailAndPopulatesTheDraft() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("¿Cuál es la capital de Francia?", state.draftStatement)
        assertEquals("B", state.draftOptions.first { it.isCorrect }.label)
        assertFalse(state.dirty)
    }

    @Test
    fun editingAnOptionMarksDirtyAndOptionsDirty() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateOptionContent("A", "Nuevo contenido"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.optionsDirty)
    }

    @Test
    fun savingWithoutTouchingOptionsSendsOptionsChangedFalse() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            updateResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado editado"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()

        assertEquals(false, repository.lastOptionsChanged)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.saveState)
    }

    @Test
    fun backendRejectionOnSaveIsShownHonestlyNotAsSuccess() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            updateResult = AppResult.Failure(AppError.Validation("admin_replace_question_options no disponible")),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.UpdateOptionContent("A", "x"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.saveState)
    }

    @Test
    fun blankStatementBlocksSave() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())), detailResult = AppResult.Success(detail()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateStatement(""))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isDraftValid())
    }

    @Test
    fun createRequiresAnInitialExamBecauseBackendNeedsSpecialtyId() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(emptyList()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado nuevo"))
        listOf("A" to "a", "B" to "b", "C" to "c", "D" to "d").forEach { (label, text) ->
            viewModel.dispatch(QuestionsIntent.UpdateOptionContent(label, text))
        }
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isDraftValid())
    }

    @Test
    fun associatingToAnAlreadyLinkedExamIsRejectedClientSideWithoutACall() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            // La pregunta ya aparece asociada a ex-1 en posición 5: es lo que
            // hace que el ViewModel SEPA de esa relación sin fan-out.
            forExamResult = AppResult.Success(listOf(listItem().copy(examId = "ex-1", position = 5))),
            detailResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository, exams = listOf(exam))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SetExamFilter("ex-1"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestAssociateExam)
        viewModel.dispatch(QuestionsIntent.UpdateAssociateExamId("ex-1"))
        viewModel.dispatch(QuestionsIntent.UpdateAssociatePosition("5"))
        viewModel.dispatch(QuestionsIntent.ConfirmAssociateExam)
        advanceUntilIdle()

        assertEquals(0, repository.updateCallCount)
        assertIs<UiAsync.Error>(viewModel.state.value.saveState)
    }

    @Test
    fun deletingArchivesAndClearsSelection() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            archiveResult = AppResult.Success(Unit),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestDelete)
        viewModel.dispatch(QuestionsIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.selectedQuestionId)
        assertEquals(1, repository.archiveCallCount)
    }

    @Test
    fun cancelWithDirtyChangesAsksForConfirmationFirst() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())), detailResult = AppResult.Success(detail()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.UpdateStatement("cambiado"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestCancel)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showCancelConfirm)
        assertEquals("cambiado", viewModel.state.value.draftStatement)

        viewModel.dispatch(QuestionsIntent.ConfirmCancel)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showCancelConfirm)
        assertEquals("¿Cuál es la capital de Francia?", viewModel.state.value.draftStatement)
    }
}

private class FakeQuestionsRepository(
    private val listResult: AppResult<List<QuestionListItem>> = AppResult.Success(emptyList()),
    private val forExamResult: AppResult<List<QuestionListItem>> = AppResult.Success(emptyList()),
    private val detailResult: AppResult<QuestionDetail>? = null,
    private val updateResult: AppResult<QuestionDetail>? = null,
    private val createResult: AppResult<QuestionDetail>? = null,
    private val archiveResult: AppResult<Unit> = AppResult.Success(Unit),
) : QuestionsRepository {
    var listCallCount = 0
        private set
    var forExamCallCount = 0
        private set
    var lastExamIdRequested: String? = null
        private set
    var updateCallCount = 0
        private set
    var lastOptionsChanged: Boolean? = null
        private set
    var archiveCallCount = 0
        private set

    override suspend fun listQuestions(): AppResult<List<QuestionListItem>> {
        listCallCount += 1
        return listResult
    }

    override suspend fun listQuestionsForExam(examId: String): AppResult<List<QuestionListItem>> {
        forExamCallCount += 1
        lastExamIdRequested = examId
        return forExamResult
    }

    override suspend fun getQuestionDetail(questionId: String): AppResult<QuestionDetail> =
        detailResult ?: AppResult.Failure(AppError.Validation("no detail configured"))

    override suspend fun createQuestion(draft: QuestionDraft, examId: String, position: Int): AppResult<QuestionDetail> =
        createResult ?: AppResult.Failure(AppError.Validation("admin_create_question no disponible"))

    override suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
        linkToExam: QuestionExamLinkRequest?,
    ): AppResult<QuestionDetail> {
        updateCallCount += 1
        lastOptionsChanged = optionsChanged
        return updateResult ?: AppResult.Failure(AppError.Validation("no update configured"))
    }

    override suspend fun archiveQuestion(questionId: String): AppResult<Unit> {
        archiveCallCount += 1
        return archiveResult
    }
}

private class FakeContentRepository(private val exams: List<Exam>) : com.oposicontrol.admin.domain.repository.ContentRepository {
    override fun observeSpecialties(): Flow<List<com.oposicontrol.admin.domain.model.Specialty>> = MutableStateFlow(emptyList())
    override fun observeExams(): Flow<List<Exam>> = MutableStateFlow(exams)
    override fun observeRafagas(): Flow<List<com.oposicontrol.admin.domain.model.Rafaga>> = MutableStateFlow(emptyList())
    override suspend fun refreshSpecialties() = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.Specialty>())
    override suspend fun refreshExams() = AppResult.Success(exams)
    override suspend fun refreshRafagas() = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.Rafaga>())
    override suspend fun getExamQuestions(examId: String) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.ExamQuestion>())
    override suspend fun getRafagaQuestions(rafagaId: String) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.RafagaQuestion>())
    override suspend fun getExamResourceAudit(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun upsertSpecialty(specialty: com.oposicontrol.admin.domain.model.Specialty) = AppResult.Success(specialty)
    override suspend fun deleteSpecialty(specialtyId: String) = AppResult.Success(Unit)
    override suspend fun getSpecialtyCapabilities(specialtyId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun saveSpecialtyCapabilities(capabilities: com.oposicontrol.admin.domain.model.SpecialtyCapabilities) = AppResult.Success(capabilities)
    override suspend fun upsertExam(exam: Exam) = AppResult.Success(exam)
    override suspend fun publishExam(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun unpublishExam(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun deleteExam(examId: String) = AppResult.Success(Unit)
    override suspend fun upsertRafaga(rafaga: com.oposicontrol.admin.domain.model.Rafaga) = AppResult.Success(rafaga)
    override suspend fun deleteRafaga(rafagaId: String) = AppResult.Success(Unit)
    override suspend fun upsertQuestion(question: com.oposicontrol.admin.domain.model.ExamQuestion) = AppResult.Success(question)
    override suspend fun importQuestions(examId: String, questions: List<com.oposicontrol.admin.domain.model.ExamQuestion>) = AppResult.Success(questions)
    override suspend fun deleteQuestion(examId: String, questionId: String) = AppResult.Success(Unit)
    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: com.oposicontrol.admin.domain.model.QuestionMoveDirection,
    ) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.ExamQuestion>())
    override suspend fun upsertRafagaQuestion(question: com.oposicontrol.admin.domain.model.RafagaQuestion) = AppResult.Success(question)
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String) = AppResult.Success(Unit)
    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: com.oposicontrol.admin.domain.model.QuestionMoveDirection,
    ) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.RafagaQuestion>())
    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: com.oposicontrol.admin.domain.model.QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun deleteResource(questionId: String, resourceId: String) = AppResult.Success(Unit)
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun clearExamCover(examId: String) = AppResult.Failure(AppError.Validation("not used"))
}
