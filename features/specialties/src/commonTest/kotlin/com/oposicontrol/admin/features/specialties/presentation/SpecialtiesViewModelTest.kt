package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpecialtiesViewModelTest {
    @Test
    fun `double save writes once and emits one-shot success`() = runTest {
        val repository = SpecialtyRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.dispatch(SpecialtiesIntent.CreateNew)
        viewModel.dispatch(SpecialtiesIntent.UpdateName("Auxiliar"))
        viewModel.dispatch(SpecialtiesIntent.UpdateDescription("Descripcion"))
        viewModel.dispatch(SpecialtiesIntent.Save)
        viewModel.dispatch(SpecialtiesIntent.Save)
        advanceUntilIdle()

        assertEquals(1, repository.upsertCalls)
        assertEquals(
            SpecialtiesEffect.Saved(SpecialtyId("specialty-new")),
            effect.await(),
        )
    }

    @Test
    fun `refresh does not replace an in-progress new specialty`() = runTest {
        val repository = SpecialtyRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(SpecialtiesIntent.CreateNew)
        viewModel.dispatch(SpecialtiesIntent.UpdateName("Borrador local"))
        viewModel.dispatch(SpecialtiesIntent.Refresh)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isCreating)
        assertNull(viewModel.state.value.selectedId)
        assertEquals("Borrador local", viewModel.state.value.editorName)
    }

    private fun kotlinx.coroutines.test.TestScope.createViewModel(repository: SpecialtyRepository) =
        SpecialtiesViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(repository),
            refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(repository),
            upsertSpecialtyUseCase = UpsertSpecialtyUseCase(repository),
            deleteSpecialtyUseCase = DeleteSpecialtyUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
}

private class SpecialtyRepository : ContentRepository {
    private val now = Instant.parse("2026-07-29T10:00:00Z")
    private val specialties = MutableStateFlow(listOf(specialty("specialty-1", "TCAE")))
    var upsertCalls = 0
        private set

    override fun observeSpecialties(): Flow<List<Specialty>> = specialties

    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> {
        specialties.value = specialties.value.toList()
        return AppResult.Success(specialties.value)
    }

    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> {
        upsertCalls += 1
        val saved = specialty.copy(
            id = specialty.id.ifBlank { "specialty-new" },
            updatedAt = now,
        )
        specialties.value = specialties.value.filterNot { it.id == saved.id } + saved
        return AppResult.Success(saved)
    }

    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> {
        specialties.value = specialties.value.filterNot { it.id == specialtyId }
        return AppResult.Success(Unit)
    }

    override fun observeExams(): Flow<List<Exam>> = flowOf(emptyList())
    override fun observeRafagas(): Flow<List<Rafaga>> = flowOf(emptyList())
    override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(emptyList())
    override suspend fun refreshRafagas(): AppResult<List<Rafaga>> = AppResult.Success(emptyList())
    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> = AppResult.Success(emptyList())
    override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> = AppResult.Success(emptyList())
    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> = error("unused")
    override suspend fun upsertExam(exam: Exam): AppResult<Exam> = error("unused")
    override suspend fun deleteExam(examId: String): AppResult<Unit> = error("unused")
    override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> = error("unused")
    override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> = error("unused")
    override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> = error("unused")
    override suspend fun importQuestions(
        examId: String,
        questions: List<ExamQuestion>,
    ): AppResult<List<ExamQuestion>> = error("unused")

    override suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit> = error("unused")
    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>> = error("unused")

    override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> = error("unused")
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> = error("unused")
    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>> = error("unused")

    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource> = error("unused")

    override suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit> = error("unused")
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> = error("unused")
    override suspend fun clearExamCover(examId: String): AppResult<Exam> = error("unused")

    private fun specialty(id: String, name: String) = Specialty(
        id = id,
        name = name,
        description = "",
        isActive = true,
        sortOrder = 0,
        linkedExams = 0,
        createdAt = now,
        updatedAt = now,
    )
}
