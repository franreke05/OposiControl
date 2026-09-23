package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.SaveSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpecialtyCapabilitiesViewModelTest {

    @Test
    fun `selecting a specialty loads its modes`() = runTest {
        val repository = FakeCapabilitiesRepository()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)
        advanceUntilIdle()

        val loaded = (viewModel.state.value.capabilities as UiAsync.Success).value
        assertEquals(SPECIALTY_ID, loaded.specialtyId)
        assertEquals(listOf("rafaga"), loaded.enabledExamModes)
    }

    @Test
    fun `toggling a mode marks the form dirty without saving anything`() = runTest {
        val repository = FakeCapabilitiesRepository()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.capabilitiesDirty, "Recien cargado no puede estar sucio.")

        viewModel.dispatch(
            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.INMERSIVOS, true),
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.capabilitiesDirty)
        // Un toggle es una edicion local: no debe escribir hasta pulsar Guardar,
        // porque apagar un modo cambia lo que ve el usuario final de OposiBot.
        assertEquals(0, repository.savedCapabilities.size)
    }

    @Test
    fun `saving sends the edited modes and clears the dirty flag`() = runTest {
        val repository = FakeCapabilitiesRepository()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)
        advanceUntilIdle()

        viewModel.dispatch(
            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.RAZONADOS, true),
        )
        viewModel.dispatch(SpecialtiesIntent.Save)
        advanceUntilIdle()

        val saved = repository.savedCapabilities.single()
        assertEquals(SPECIALTY_ID, saved.specialtyId)
        assertTrue(saved.permiteRazonados)
        assertEquals(listOf("rafaga", "razonado"), saved.enabledExamModes)
        assertFalse(viewModel.state.value.capabilitiesDirty, "Tras guardar, el borrador ya es lo cargado.")
    }

    @Test
    fun `disabling every exam mode without a message is rejected before reaching the repository`() = runTest {
        val repository = FakeCapabilitiesRepository()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)
        advanceUntilIdle()

        viewModel.dispatch(
            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.RAFAGAS, false),
        )
        viewModel.dispatch(SpecialtiesIntent.Save)
        advanceUntilIdle()

        // Dejar al usuario sin ningun examen y sin explicacion es justo el estado
        // que la regla de producto prohibe; debe morir antes de la llamada.
        assertTrue(viewModel.state.value.capabilitiesActionState is UiAsync.Error)
        assertEquals(0, repository.savedCapabilities.size)
    }

    @Test
    fun `disabling every exam mode is allowed when it explains why`() = runTest {
        val repository = FakeCapabilitiesRepository()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)
        advanceUntilIdle()

        viewModel.dispatch(
            SpecialtiesIntent.ToggleCapability(SpecialtyCapabilityToggle.RAFAGAS, false),
        )
        viewModel.dispatch(
            SpecialtiesIntent.UpdateLimitationMessage("Contenido en preparacion."),
        )
        viewModel.dispatch(SpecialtiesIntent.Save)
        advanceUntilIdle()

        val saved = repository.savedCapabilities.single()
        assertTrue(saved.hasNoExamModes)
        assertEquals("Contenido en preparacion.", saved.limitationMessage)
    }

    @Test
    fun `a capabilities read failure does not break the rest of the screen`() = runTest {
        val repository = FakeCapabilitiesRepository(
            capabilitiesFailure = AppError.Validation("capacidades_especialidad no aprovisionada"),
        )
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.capabilities is UiAsync.Error)
        // La lista y el formulario principal siguen usables: el fallo esta acotado
        // a su seccion, no tumba la pantalla entera.
        assertTrue(state.items is UiAsync.Success)
        assertEquals(SPECIALTY_ID, state.selectedId)
    }

    private fun createViewModel(
        dispatcher: TestDispatcher,
        repository: FakeCapabilitiesRepository,
    ) = SpecialtiesViewModel(
        dispatcherProvider = TestDispatcherProvider(dispatcher),
        observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(repository),
        observeAllExamsUseCase = ObserveAllExamsUseCase(repository),
        refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(repository),
        refreshAllExamsUseCase = RefreshAllExamsUseCase(repository),
        upsertSpecialtyUseCase = UpsertSpecialtyUseCase(repository),
        deleteSpecialtyUseCase = DeleteSpecialtyUseCase(repository),
        getSpecialtyCapabilitiesUseCase = GetSpecialtyCapabilitiesUseCase(repository),
        saveSpecialtyCapabilitiesUseCase = SaveSpecialtyCapabilitiesUseCase(repository),
        analyticsTracker = NoOpAnalyticsTracker,
    )

    private companion object {
        const val SPECIALTY_ID = "11111111-2222-3333-4444-555555555555"
    }
}

/**
 * Repositorio de contenido acotado a lo que esta pantalla usa: especialidades y
 * sus capacidades. El resto del contrato lanza, de modo que si alguien lo llama
 * desde este flujo el test lo dice en vez de pasar en silencio con un vacio.
 */
private class FakeCapabilitiesRepository(
    private val capabilitiesFailure: AppError? = null,
) : com.oposicontrol.admin.domain.repository.ContentRepository {

    val savedCapabilities = mutableListOf<SpecialtyCapabilities>()

    private val now = kotlinx.datetime.Instant.parse("2026-09-01T09:00:00Z")

    private var capabilities = SpecialtyCapabilities(
        permiteRafagas = true,
        permiteRazonados = false,
        permiteInmersivos = false,
        permiteDemos = true,
        permiteOposimarket = true,
        specialtyId = "11111111-2222-3333-4444-555555555555",
        limitationMessage = null,
        configured = true,
    )

    private val specialties = kotlinx.coroutines.flow.MutableStateFlow(
        listOf(
            Specialty(
                id = "11111111-2222-3333-4444-555555555555",
                name = "Enfermeria",
                description = "",
                isActive = true,
                sortOrder = 0,
                linkedExams = 0,
                createdAt = now,
                updatedAt = now,
            ),
        ),
    )

    private val exams = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.oposicontrol.admin.domain.model.Exam>())

    override fun observeSpecialties() = specialties
    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> = AppResult.Success(specialties.value)

    // El workspace pide el catalogo completo de examenes para el desglose por
    // tipo (ver SpecialtiesViewModel); esta pantalla no lo usa, así que basta
    // con una lista vacía en vez de `outOfScope()`.
    override fun observeExams() = exams
    override suspend fun refreshExams(): AppResult<List<com.oposicontrol.admin.domain.model.Exam>> =
        AppResult.Success(exams.value)

    override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> =
        capabilitiesFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(capabilities)

    override suspend fun saveSpecialtyCapabilities(
        capabilities: SpecialtyCapabilities,
    ): AppResult<SpecialtyCapabilities> {
        savedCapabilities += capabilities
        this.capabilities = capabilities.copy(configured = true)
        return AppResult.Success(this.capabilities)
    }

    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> = AppResult.Success(specialty)
    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> = AppResult.Success(Unit)

    // --- fuera del alcance de esta pantalla ---
    private fun outOfScope(): Nothing = throw NotImplementedError("No usado por SpecialtiesViewModel")

    override fun observeRafagas() = outOfScope()
    override suspend fun refreshRafagas() = outOfScope()
    override suspend fun getExamQuestions(examId: String) = outOfScope()
    override suspend fun getRafagaQuestions(rafagaId: String) = outOfScope()
    override suspend fun getExamResourceAudit(examId: String) = outOfScope()
    override suspend fun upsertExam(exam: com.oposicontrol.admin.domain.model.Exam) = outOfScope()
    override suspend fun deleteExam(examId: String) = outOfScope()
    override suspend fun publishExam(examId: String) = outOfScope()
    override suspend fun unpublishExam(examId: String) = outOfScope()
    override suspend fun upsertRafaga(rafaga: com.oposicontrol.admin.domain.model.Rafaga) = outOfScope()
    override suspend fun deleteRafaga(rafagaId: String) = outOfScope()
    override suspend fun upsertQuestion(question: com.oposicontrol.admin.domain.model.ExamQuestion) = outOfScope()
    override suspend fun importQuestions(
        examId: String,
        questions: List<com.oposicontrol.admin.domain.model.ExamQuestion>,
    ) = outOfScope()
    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: com.oposicontrol.admin.domain.model.QuestionMoveDirection,
    ) = outOfScope()
    override suspend fun upsertRafagaQuestion(
        question: com.oposicontrol.admin.domain.model.RafagaQuestion,
    ) = outOfScope()
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String) = outOfScope()
    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: com.oposicontrol.admin.domain.model.QuestionMoveDirection,
    ) = outOfScope()
    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: com.oposicontrol.admin.domain.model.QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = outOfScope()
    override suspend fun deleteResource(questionId: String, resourceId: String) = outOfScope()
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String) = outOfScope()
    override suspend fun clearExamCover(examId: String) = outOfScope()
}
