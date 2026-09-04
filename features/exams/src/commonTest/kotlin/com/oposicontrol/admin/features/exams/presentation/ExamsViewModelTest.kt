package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Community
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
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.communities.ObserveCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.RefreshCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ClearExamCoverUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetRafagaQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UnpublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadExamCoverUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExamsViewModelTest {

    private fun TestScope.buildViewModel(repository: ExamsTestRepository): ExamsViewModel {
        return ExamsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(repository),
            observeExamsUseCase = ObserveExamsUseCase(repository),
            observeRafagasUseCase = ObserveRafagasUseCase(repository),
            refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(repository),
            upsertSpecialtyUseCase = UpsertSpecialtyUseCase(repository),
            deleteSpecialtyUseCase = DeleteSpecialtyUseCase(repository),
            refreshExamsUseCase = RefreshExamsUseCase(repository),
            refreshRafagasUseCase = RefreshRafagasUseCase(repository),
            getExamQuestionsUseCase = GetExamQuestionsUseCase(repository),
            getRafagaQuestionsUseCase = GetRafagaQuestionsUseCase(repository),
            getExamResourceAuditUseCase = GetExamResourceAuditUseCase(repository),
            upsertExamUseCase = UpsertExamUseCase(repository),
            deleteExamUseCase = DeleteExamUseCase(repository),
            publishExamUseCase = PublishExamUseCase(repository),
            unpublishExamUseCase = UnpublishExamUseCase(repository),
            getSpecialtyCapabilitiesUseCase = GetSpecialtyCapabilitiesUseCase(repository),
            observeCommunitiesUseCase = ObserveCommunitiesUseCase(repository),
            refreshCommunitiesUseCase = RefreshCommunitiesUseCase(repository),
            upsertRafagaUseCase = UpsertRafagaUseCase(repository),
            deleteRafagaUseCase = DeleteRafagaUseCase(repository),
            upsertQuestionUseCase = UpsertQuestionUseCase(repository),
            importQuestionsUseCase = ImportQuestionsUseCase(repository),
            deleteQuestionUseCase = DeleteQuestionUseCase(repository),
            moveQuestionUseCase = MoveQuestionUseCase(repository),
            upsertRafagaQuestionUseCase = UpsertRafagaQuestionUseCase(repository),
            deleteRafagaQuestionUseCase = DeleteRafagaQuestionUseCase(repository),
            moveRafagaQuestionUseCase = MoveRafagaQuestionUseCase(repository),
            uploadExamCoverUseCase = UploadExamCoverUseCase(repository),
            clearExamCoverUseCase = ClearExamCoverUseCase(repository),
            uploadQuestionResourceUseCase = UploadQuestionResourceUseCase(repository),
            deleteQuestionResourceUseCase = DeleteQuestionResourceUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
    }

    @Test
    fun `selecting exam loads related questions`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()

        val state = viewModel.state.value
        val questions = (state.questions as UiAsync.Success).value
        assertEquals("exam-tcae-marzo", state.selectedExamId)
        assertEquals(2, questions.size)
        assertEquals("question-tcae-1", questions.first().id)
    }

    // Regresión del bug donde saveExam() nunca incluía `code` al construir el
    // Exam a persistir: cualquier edición borraba en silencio el código real
    // que el servidor había autogenerado al crear el examen.
    @Test
    fun `saving exam sends the typed code to the repository`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode("EX-2026-001"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        assertEquals("EX-2026-001", repository.lastUpsertedExam?.code)
        // saveExam() deja actionState en Success, pero reselecciona el examen
        // guardado a continuación y selectExam() lo resetea a Idle a propósito
        // (para no arrastrar un banner de "guardado" al reabrir el editor) —
        // el estado terminal observable aquí es Idle, no Success.
        assertEquals(UiAsync.Idle, viewModel.state.value.actionState)
        assertEquals("exam-tcae-marzo", viewModel.state.value.selectedExamId)
    }

    @Test
    fun `saving exam with a blank code preserves the existing one`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateCode(""))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        assertEquals("TCAE-2026-03", repository.lastUpsertedExam?.code)
    }

    // La especialidad de este examen (ver fake) sólo permite ráfaga: guardar un
    // tipo que no está entre sus capacidades debe rechazarse en el cliente, ya
    // que el backend no valida tipoExamen contra capacidades_especialidad.
    @Test
    fun `saving exam rejects a type not allowed by the specialty`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UpdateTypeExam("inmersivo"))
        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertTrue((action.error as AppError.Validation).message.contains("no permite"))
        assertNull(repository.lastUpsertedExam)
    }

    // Antes "capabilities != null && !capabilities.allows(typeExam)" se saltaba
    // por completo la comprobación cuando specialtyCapabilities no era Success
    // (fallo de red) y dejaba guardar cualquier tipo sin verificar.
    @Test
    fun `saving exam blocks when specialty capabilities failed to load`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        repository.capabilitiesShouldFail = true
        // Reintentar contra la misma especialidad, ahora fallando.
        viewModel.dispatch(ExamsIntent.UpdateSpecialtyId("specialty-tcae"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.specialtyCapabilities is UiAsync.Error)
        // El tipo ya persistido ("rafaga") no se invalida ni se borra en silencio.
        assertEquals("rafaga", viewModel.state.value.editorTypeExam)

        viewModel.dispatch(ExamsIntent.SaveExam)
        advanceUntilIdle()

        val action = viewModel.state.value.actionState
        assertTrue(action is UiAsync.Error)
        assertTrue((action.error as AppError.Validation).message.contains("comprobar"))
        assertNull(repository.lastUpsertedExam)
    }

    @Test
    fun `publishing an exam uses the dedicated endpoint and updates its status`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.lifecycleActionState is UiAsync.Success)
        val exams = (viewModel.state.value.exams as UiAsync.Success).value
        val published = exams.first { it.id == "exam-tcae-marzo" }
        assertEquals(ExamStatus.PUBLISHED, published.status)
        assertNotNull(published.publishedAt)
    }

    @Test
    fun `unpublishing an exam clears its published date`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.PublishExam)
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.UnpublishExam)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.lifecycleActionState is UiAsync.Success)
        val exams = (viewModel.state.value.exams as UiAsync.Success).value
        val unpublished = exams.first { it.id == "exam-tcae-marzo" }
        assertEquals(ExamStatus.DRAFT, unpublished.status)
        assertNull(unpublished.publishedAt)
    }

    // El botón "Archivar" dispara RequestDeleteSelectedExam -> ConfirmPendingDelete,
    // no un DeleteSelectedExam directo: es el mismo mecanismo que Rafaga/Pregunta/
    // Especialidad, así que el mensaje de confirmación también debe ser el real.
    @Test
    fun `requesting to archive an exam sets the real confirmation message`() = runTest {
        val repository = ExamsTestRepository()
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(ExamsIntent.SelectExam("exam-tcae-marzo"))
        advanceUntilIdle()
        viewModel.dispatch(ExamsIntent.RequestDeleteSelectedExam)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PendingDeleteTarget.Exam, state.pendingDeleteTarget)
        assertTrue(state.pendingDeleteMessage.contains("no se eliminan"))

        viewModel.dispatch(ExamsIntent.ConfirmPendingDelete)
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingDeleteTarget)
        assertTrue(viewModel.state.value.actionState is UiAsync.Success)
    }
}

private class ExamsTestRepository : ContentRepository, CommunitiesRepository {
    private val now = Instant.parse("2026-04-15T09:00:00Z")
    var lastUpsertedExam: Exam? = null
        private set

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
                code = "TCAE-2026-03",
                specialtyId = "specialty-tcae",
                specialtyName = "TCAE",
                title = "TCAE Marzo",
                description = "",
                typeExam = "rafaga",
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
    private val rafagas = MutableStateFlow<List<Rafaga>>(emptyList())
    private val communities = MutableStateFlow<List<Community>>(emptyList())
    private val rafagaQuestions = mutableMapOf<String, MutableList<RafagaQuestion>>()
    private val questions = mutableMapOf(
        "exam-tcae-marzo" to mutableListOf(
            question("question-tcae-1", "exam-tcae-marzo", 0),
            question("question-tcae-2", "exam-tcae-marzo", 1),
        ),
    )

    override fun observeSpecialties(): Flow<List<Specialty>> = specialties
    override fun observeExams(): Flow<List<Exam>> = exams
    override fun observeRafagas(): Flow<List<Rafaga>> = rafagas
    override fun observeCommunities(): Flow<List<Community>> = communities
    override suspend fun refreshCommunities(): AppResult<List<Community>> = AppResult.Success(communities.value)
    override suspend fun upsertCommunity(community: Community): AppResult<Community> = AppResult.Success(community)
    override suspend fun deleteCommunity(communityId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> = AppResult.Success(specialties.value)
    override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(exams.value)
    override suspend fun refreshRafagas(): AppResult<List<Rafaga>> = AppResult.Success(rafagas.value)
    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> {
        return AppResult.Success(questions[examId].orEmpty())
    }
    override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> {
        return AppResult.Success(rafagaQuestions[rafagaId].orEmpty())
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

    // Esta especialidad sólo permite ráfaga: es lo que ejercitan los tests de
    // validación de tipo de examen contra capacidades_especialidad.
    var capabilitiesShouldFail = false

    override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> {
        if (capabilitiesShouldFail) {
            return AppResult.Failure(AppError.Network("sin conexión"))
        }
        return AppResult.Success(
            SpecialtyCapabilities(
                permiteRafagas = true,
                permiteRazonados = false,
                permiteInmersivos = false,
                permiteDemos = true,
                permiteOposimarket = true,
                specialtyId = specialtyId,
                configured = false,
            ),
        )
    }

    override suspend fun saveSpecialtyCapabilities(
        capabilities: SpecialtyCapabilities,
    ): AppResult<SpecialtyCapabilities> = AppResult.Success(capabilities)

    override suspend fun upsertExam(exam: Exam): AppResult<Exam> {
        lastUpsertedExam = exam
        exams.value = if (exams.value.any { it.id == exam.id }) {
            exams.value.map { if (it.id == exam.id) exam else it }
        } else {
            exams.value + exam
        }
        return AppResult.Success(exam)
    }
    override suspend fun deleteExam(examId: String): AppResult<Unit> = AppResult.Success(Unit)

    // Publicar/despublicar reales: no pasan por upsertExam, así que se modelan
    // aparte para que un test que use PublishExam nunca vea tocado lastUpsertedExam.
    override suspend fun publishExam(examId: String): AppResult<Exam> {
        val updated = exams.value.first { it.id == examId }.copy(status = ExamStatus.PUBLISHED, publishedAt = now)
        exams.value = exams.value.map { if (it.id == examId) updated else it }
        return AppResult.Success(updated)
    }
    override suspend fun unpublishExam(examId: String): AppResult<Exam> {
        val updated = exams.value.first { it.id == examId }.copy(status = ExamStatus.DRAFT, publishedAt = null)
        exams.value = exams.value.map { if (it.id == examId) updated else it }
        return AppResult.Success(updated)
    }
    override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> = AppResult.Success(rafaga)
    override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> {
        val saved = exams.value.first { it.id == examId }.copy(
            coverUrl = "https://storage.local/exam-covers/$examId/cover.png",
            coverStoragePath = "exam-covers/$examId/cover.png",
            coverMimeType = "image/png",
        )
        exams.value = exams.value.map { if (it.id == examId) saved else it }
        return AppResult.Success(saved)
    }
    override suspend fun clearExamCover(examId: String): AppResult<Exam> {
        val saved = exams.value.first { it.id == examId }.copy(
            coverUrl = null,
            coverStoragePath = null,
            coverMimeType = null,
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
    override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> {
        val bucket = rafagaQuestions.getOrPut(question.rafagaId) { mutableListOf() }
        bucket.removeAll { it.id == question.id }
        bucket += question
        return AppResult.Success(question)
    }
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>> = AppResult.Success(rafagaQuestions[rafagaId].orEmpty())
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
    override suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit> = AppResult.Success(Unit)

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
