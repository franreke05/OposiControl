package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamStatus
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
import com.oposicontrol.admin.domain.usecase.content.ArchiveExamUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetRafagaQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UnlinkQuestionFromExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UnpublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Publicacion con el [ExamsViewModel] REAL y un [ContentRepository] falso: lo que [ExamWorkspaceUiTest] no ve (alli el
 * estado es fabricado y solo se capturan intents). Aqui se comprueba la vuelta del servidor —el rechazo se pinta y el
 * estado no cambia; el exito se pinta— y que cada boton llega a SU llamada del repositorio.
 */
@OptIn(ExperimentalTestApi::class)
class ExamPublicationFlowUiTest {
    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun exam(status: ExamStatus) = Exam(
        id = "ex-1",
        code = "EX-2026-0001",
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        communityId = null,
        title = "TCAE · Simulacro marzo",
        description = "Simulacro completo.",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = status,
        publishFrom = null,
        publishedAt = if (status == ExamStatus.PUBLISHED) now else null,
        sortOrder = 0,
        questionCount = 1,
        createdAt = now,
        updatedAt = now,
    )

    private val question = ExamQuestion(
        id = "q-1",
        examId = "ex-1",
        title = "Pregunta 1",
        statement = "Enunciado 1",
        sortOrder = 0,
        options = listOf("A", "B").mapIndexed { index, label ->
            QuestionOption(
                id = "q-1-$label",
                questionId = "q-1",
                label = label,
                content = "Opción $label",
                isCorrect = index == 0,
                sortOrder = index,
            )
        },
        resources = emptyList(),
        createdAt = now,
        updatedAt = now,
    )

    /** Solo publicar/despublicar/archivar y las lecturas del arranque hacen algo; el resto no interviene. */
    private inner class FakeContent(
        initial: Exam,
        var questionsResult: AppResult<List<ExamQuestion>> = AppResult.Success(listOf(question)),
        var publishResult: AppResult<Exam>? = null,
    ) : ContentRepository, CommunitiesRepository {
        val exams = MutableStateFlow(listOf(initial))
        val calls = mutableListOf<String>()
        private val specialties = MutableStateFlow(
            listOf(Specialty("esp-1", "TCAE", "", isActive = true, sortOrder = 0, linkedExams = 1, createdAt = now, updatedAt = now)),
        )

        private fun lifecycle(call: String, examId: String, status: ExamStatus): AppResult<Exam> {
            calls += "$call:$examId"
            val updated = exams.value.first { it.id == examId }
                .copy(status = status, publishedAt = if (status == ExamStatus.PUBLISHED) now else null)
            exams.value = exams.value.map { if (it.id == examId) updated else it }
            return AppResult.Success(updated)
        }

        override fun observeSpecialties(): Flow<List<Specialty>> = specialties
        override fun observeExams(): Flow<List<Exam>> = exams
        override fun observeAllExams(): Flow<List<Exam>> = exams
        override fun observeRafagas(): Flow<List<Rafaga>> = MutableStateFlow(emptyList())
        override fun observeCommunities(): Flow<List<Community>> = MutableStateFlow(emptyList())
        override suspend fun refreshCommunities(): AppResult<List<Community>> = AppResult.Success(emptyList())
        override suspend fun refreshSpecialties(): AppResult<List<Specialty>> = AppResult.Success(specialties.value)
        override suspend fun refreshAllExams(): AppResult<List<Exam>> = AppResult.Success(exams.value)
        override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(exams.value)
        override suspend fun refreshRafagas(): AppResult<List<Rafaga>> = AppResult.Success(emptyList())
        override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> = questionsResult
        override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> = AppResult.Success(emptyList())
        override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> =
            AppResult.Failure(AppError.Network("no interviene"))
        override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> =
            AppResult.Success(
                SpecialtyCapabilities(
                    permiteRafagas = true,
                    permiteRazonados = true,
                    permiteInmersivos = true,
                    permiteDemos = false,
                    permiteOposimarket = false,
                    specialtyId = specialtyId,
                ),
            )

        override suspend fun publishExam(examId: String): AppResult<Exam> {
            publishResult?.let { calls += "publish:$examId"; return it }
            return lifecycle("publish", examId, ExamStatus.PUBLISHED)
        }
        override suspend fun unpublishExam(examId: String): AppResult<Exam> = lifecycle("unpublish", examId, ExamStatus.DRAFT)
        override suspend fun archiveExam(examId: String): AppResult<Exam> = lifecycle("archive", examId, ExamStatus.ARCHIVED)
        override suspend fun deleteExam(examId: String): AppResult<Unit> {
            calls += "delete:$examId"
            return AppResult.Success(Unit)
        }

        override suspend fun upsertCommunity(community: Community): AppResult<Community> = TODO()
        override suspend fun deleteCommunity(communityId: String): AppResult<Unit> = TODO()
        override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> = TODO()
        override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> = TODO()
        override suspend fun saveSpecialtyCapabilities(capabilities: SpecialtyCapabilities): AppResult<SpecialtyCapabilities> = TODO()
        override suspend fun upsertExam(exam: Exam): AppResult<Exam> = TODO()
        override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> = TODO()
        override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> = TODO()
        override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> = TODO()
        override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> = TODO()
        override suspend fun moveQuestion(examId: String, questionId: String, direction: QuestionMoveDirection): AppResult<List<ExamQuestion>> = TODO()
        override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> = TODO()
        override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> = TODO()
        override suspend fun moveRafagaQuestion(rafagaId: String, questionId: String, direction: QuestionMoveDirection): AppResult<List<RafagaQuestion>> = TODO()
        override suspend fun uploadResource(
            questionId: String,
            resourceId: String,
            type: QuestionResourceType,
            localPathOrUrl: String,
            title: String,
        ): AppResult<QuestionResource> = TODO()
        override suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit> = TODO()
        override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> = TODO()
        override suspend fun clearExamCover(examId: String): AppResult<Exam> = TODO()
    }

    private fun viewModel(repo: FakeContent) = ExamsViewModel(
        dispatcherProvider = Unconfined,
        observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(repo),
        observeAllExamsUseCase = ObserveAllExamsUseCase(repo),
        observeRafagasUseCase = ObserveRafagasUseCase(repo),
        refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(repo),
        upsertSpecialtyUseCase = UpsertSpecialtyUseCase(repo),
        deleteSpecialtyUseCase = DeleteSpecialtyUseCase(repo),
        refreshAllExamsUseCase = RefreshAllExamsUseCase(repo),
        refreshRafagasUseCase = RefreshRafagasUseCase(repo),
        getExamQuestionsUseCase = GetExamQuestionsUseCase(repo),
        getRafagaQuestionsUseCase = GetRafagaQuestionsUseCase(repo),
        getExamResourceAuditUseCase = GetExamResourceAuditUseCase(repo),
        upsertExamUseCase = UpsertExamUseCase(repo),
        deleteExamUseCase = DeleteExamUseCase(repo),
        archiveExamUseCase = ArchiveExamUseCase(repo),
        publishExamUseCase = PublishExamUseCase(repo),
        unpublishExamUseCase = UnpublishExamUseCase(repo),
        getSpecialtyCapabilitiesUseCase = GetSpecialtyCapabilitiesUseCase(repo),
        observeCommunitiesUseCase = ObserveCommunitiesUseCase(repo),
        refreshCommunitiesUseCase = RefreshCommunitiesUseCase(repo),
        upsertRafagaUseCase = UpsertRafagaUseCase(repo),
        deleteRafagaUseCase = DeleteRafagaUseCase(repo),
        upsertQuestionUseCase = UpsertQuestionUseCase(repo),
        importQuestionsUseCase = ImportQuestionsUseCase(repo),
        unlinkQuestionFromExamUseCase = UnlinkQuestionFromExamUseCase(repo),
        archiveQuestionUseCase = ArchiveQuestionUseCase(repo),
        moveQuestionUseCase = MoveQuestionUseCase(repo),
        upsertRafagaQuestionUseCase = UpsertRafagaQuestionUseCase(repo),
        deleteRafagaQuestionUseCase = DeleteRafagaQuestionUseCase(repo),
        moveRafagaQuestionUseCase = MoveRafagaQuestionUseCase(repo),
        uploadQuestionResourceUseCase = UploadQuestionResourceUseCase(repo),
        deleteQuestionResourceUseCase = DeleteQuestionResourceUseCase(repo),
        analyticsTracker = NoOpAnalyticsTracker,
    )

    /** Monta el workspace como [ExamWorkspaceScreen] (estado del VM + `dispatch`), abierto en Publicación. */
    private fun publication(repo: FakeContent, block: ComposeUiTest.(ExamsViewModel) -> Unit) =
        runDesktopComposeUiTest(width = 1920, height = 1080) {
            val vm = viewModel(repo)
            vm.dispatch(ExamsIntent.SelectExam("ex-1"))
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) {
                        val state by vm.state.collectAsState()
                        ExamWorkspaceContent(
                            state = state,
                            onIntent = vm::dispatch,
                            initialTab = ExamWorkspaceTabId.PUBLICACION,
                        )
                    }
                }
            }
            waitForIdle()
            block(vm)
        }

    private fun ComposeUiTest.visible(text: String): Boolean = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ExamsViewModel.catalogStatus(): ExamStatus = state.value.catalogExams.first { it.id == "ex-1" }.status

    /**
     * La lista de preguntas no se pudo leer (el cliente no puede saber si esta vacia y no apaga el boton): el servidor
     * rechaza con K-DEC01, el rechazo se pinta tal cual y el examen sigue en borrador.
     */
    @Test
    fun aServerRejectionForMissingQuestionsIsShownAndTheExamStaysDraft() {
        val rule = "No se puede publicar un examen sin preguntas activas."
        val repo = FakeContent(
            exam(ExamStatus.DRAFT),
            questionsResult = AppResult.Failure(AppError.Network("sin conexión")),
            publishResult = AppResult.Failure(AppError.Validation(rule, code = "EXAM_PUBLISH_REQUIRES_QUESTIONS")),
        )
        publication(repo) { vm ->
            onNodeWithText("Publicar ahora").performClick()
            waitForIdle()
            onNodeWithText("Publicar").performClick()
            waitForIdle()

            onNodeWithText("No se ha podido publicar.").assertExists()
            onNodeWithText(rule).assertExists()
            assertTrue(visible("Borrador"), "El chip de estado tiene que seguir diciendo Borrador")
            onNodeWithText("Examen publicado.").assertDoesNotExist()
            assertEquals(ExamStatus.DRAFT, vm.catalogStatus())
            assertEquals("draft", vm.state.value.editorStatus)
        }
        assertEquals(listOf("publish:ex-1"), repo.calls)
    }

    @Test
    fun aSuccessfulPublishShowsThePublishedState() {
        val repo = FakeContent(exam(ExamStatus.DRAFT))
        publication(repo) { vm ->
            onNodeWithText("Publicar ahora").performClick()
            waitForIdle()
            onNodeWithText("Publicar").performClick()
            waitForIdle()

            onNodeWithText("Examen publicado.").assertExists()
            assertTrue(visible("Publicado"), "El chip de estado no ha pasado a Publicado")
            // Publicado ofrece la accion contraria y ya no la de publicar.
            onNodeWithText("Despublicar").assertExists()
            onNodeWithText("Publicar ahora").assertDoesNotExist()
            assertEquals(ExamStatus.PUBLISHED, vm.catalogStatus())
        }
        assertEquals(listOf("publish:ex-1"), repo.calls)
    }

    @Test
    fun unpublishAsksAndThenCallsTheRepository() {
        val repo = FakeContent(exam(ExamStatus.PUBLISHED))
        publication(repo) { vm ->
            onNodeWithText("Despublicar").performClick()
            waitForIdle()
            assertTrue(repo.calls.isEmpty(), "Ha despublicado sin confirmar")
            onNodeWithText("Despublicar examen").assertExists()
            // Boton de la fila y boton del dialogo se llaman igual: el del dialogo es el ultimo compuesto.
            onAllNodesWithText("Despublicar").onLast().performClick()
            waitForIdle()

            onNodeWithText("Examen despublicado.").assertExists()
            assertEquals(ExamStatus.DRAFT, vm.catalogStatus())
        }
        assertEquals(listOf("unpublish:ex-1"), repo.calls)
    }

    @Test
    fun archiveAsksAndThenCallsArchiveNotDelete() {
        val repo = FakeContent(exam(ExamStatus.DRAFT))
        publication(repo) { vm ->
            onNodeWithText("Archivar").performClick()
            waitForIdle()
            assertTrue(repo.calls.isEmpty(), "Ha archivado sin confirmar")
            onNodeWithText("Archivar examen").assertExists()
            onAllNodesWithText("Archivar").onLast().performClick()
            waitForIdle()

            assertEquals(ExamStatus.ARCHIVED, vm.catalogStatus())
            assertTrue(visible("Archivado"), "El chip de estado no ha pasado a Archivado")
        }
        assertEquals(listOf("archive:ex-1"), repo.calls)
    }
}
