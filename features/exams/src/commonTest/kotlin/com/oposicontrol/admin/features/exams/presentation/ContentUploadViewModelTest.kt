package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.repository.ContentImportRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.content.CreateContentDraftUseCase
import com.oposicontrol.admin.domain.usecase.content.GetContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.ListContentImportBatchesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpdateContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadPdfToStagingUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun batch(id: String = "b1", status: String = "staging") = ContentImportBatch(
    id = id,
    examId = "e1",
    examType = "rafaga",
    fileName = "temario.pdf",
    status = status,
    detectedCount = 1,
    importedCount = 0,
    staging = emptyList(),
)

/**
 * Solo se implementan de verdad los cuatro miembros que ContentUploadViewModel
 * usa (observar/refrescar especialidades y examenes). El resto lanza a
 * proposito: si una futura version de la pantalla empieza a llamarlos, el test
 * falla de forma ruidosa en vez de pasar sobre un doble silencioso.
 */
private val testExam = Exam(
    id = "e1",
    specialtyId = "s1",
    specialtyName = "Policia Nacional",
    title = "Examen de prueba",
    description = "",
    typeExam = "rafaga",
    priceTickets = 0,
    status = ExamStatus.DRAFT,
    publishFrom = null,
    publishedAt = null,
    sortOrder = 0,
    questionCount = 0,
    createdAt = Instant.parse("2026-08-23T10:00:00Z"),
    updatedAt = Instant.parse("2026-08-23T10:00:00Z"),
)

private class ContentUploadTestContentRepository : ContentRepository {
    override fun observeSpecialties(): Flow<List<Specialty>> = flowOf(emptyList())

    // Un examen real: sin el, `selectedExamId` se queda null y
    // UploadPdfToStagingUseCase rechaza la subida antes de llegar al
    // repositorio, con lo que el test de doble subida no probaria nada.
    override fun observeExams(): Flow<List<Exam>> = flowOf(listOf(testExam))
    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> = AppResult.Success(emptyList())
    override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(emptyList())

    override fun observeRafagas(): Flow<List<Rafaga>> = error("not used by ContentUploadViewModel")
    override suspend fun refreshRafagas(): AppResult<List<Rafaga>> = error("not used by ContentUploadViewModel")
    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> = error("not used by ContentUploadViewModel")
    override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> = error("not used by ContentUploadViewModel")
    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> = error("not used by ContentUploadViewModel")
    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> = error("not used by ContentUploadViewModel")
    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> = error("not used by ContentUploadViewModel")
    override suspend fun upsertExam(exam: Exam): AppResult<Exam> = error("not used by ContentUploadViewModel")
    override suspend fun deleteExam(examId: String): AppResult<Unit> = error("not used by ContentUploadViewModel")
    override suspend fun publishExam(examId: String): AppResult<Exam> = error("not used by ContentUploadViewModel")
    override suspend fun unpublishExam(examId: String): AppResult<Exam> = error("not used by ContentUploadViewModel")
    override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> = error("not used by ContentUploadViewModel")
    override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> = error("not used by ContentUploadViewModel")
    override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> = error("not used by ContentUploadViewModel")
    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> = error("not used by ContentUploadViewModel")
    override suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit> = error("not used by ContentUploadViewModel")
    override suspend fun moveQuestion(examId: String, questionId: String, direction: QuestionMoveDirection): AppResult<List<ExamQuestion>> = error("not used by ContentUploadViewModel")
    override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> = error("not used by ContentUploadViewModel")
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> = error("not used by ContentUploadViewModel")
    override suspend fun moveRafagaQuestion(rafagaId: String, questionId: String, direction: QuestionMoveDirection): AppResult<List<RafagaQuestion>> = error("not used by ContentUploadViewModel")
    override suspend fun uploadResource(questionId: String, resourceId: String, type: QuestionResourceType, localPathOrUrl: String, title: String): AppResult<QuestionResource> = error("not used by ContentUploadViewModel")
    override suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit> = error("not used by ContentUploadViewModel")
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> = error("not used by ContentUploadViewModel")
    override suspend fun clearExamCover(examId: String): AppResult<Exam> = error("not used by ContentUploadViewModel")

    // Añadidos al integrar: `ContentRepository` creció con las capacidades de
    // especialidad en la rama de escritorio, después de que se escribiera este
    // doble en la rama de QA móvil. No los usa `ContentUploadViewModel`, así que
    // siguen el mismo criterio que el resto: fallar si alguien los llama, en vez
    // de devolver un valor inventado que enmascararía el error.
    override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> =
        error("not used by ContentUploadViewModel")

    override suspend fun saveSpecialtyCapabilities(capabilities: SpecialtyCapabilities): AppResult<SpecialtyCapabilities> =
        error("not used by ContentUploadViewModel")
}

/**
 * Publicar y subir se quedan suspendidos hasta que el test los libera. Modelar
 * esa suspension es el punto del test: con un fake sincrono la primera
 * peticion termina antes de que el segundo toque llegue a ejecutarse, asi que
 * la guarda de doble envio nunca entraria en juego y el test pasaria incluso
 * sin ella.
 */
private class FakeContentImportRepository : ContentImportRepository {
    var publishCalls = 0
    var uploadCalls = 0
    private var gate = CompletableDeferred<Unit>()

    fun release() {
        gate.complete(Unit)
        gate = CompletableDeferred()
    }

    override suspend fun listImportBatches(): AppResult<List<ContentImportBatch>> = AppResult.Success(listOf(batch()))
    override suspend fun getImportBatch(batchId: String): AppResult<ContentImportBatch> = AppResult.Success(batch(batchId))

    override suspend fun uploadPdfToStaging(examId: String, examType: String, fileUri: String, fileName: String): AppResult<ContentImportBatch> {
        val current = gate
        uploadCalls++
        current.await()
        return AppResult.Success(batch())
    }

    override suspend fun updateStaging(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> = AppResult.Success(item)

    override suspend fun publishImportBatch(batchId: String): AppResult<ContentImportBatch> {
        val current = gate
        publishCalls++
        current.await()
        return AppResult.Success(batch(batchId, status = "importado"))
    }
}

private fun newViewModel(
    testScheduler: TestCoroutineScheduler,
    importRepository: ContentImportRepository,
    contentRepository: ContentRepository = ContentUploadTestContentRepository(),
) = ContentUploadViewModel(
    dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
    observeSpecialtiesUseCase = ObserveSpecialtiesUseCase(contentRepository),
    observeExamsUseCase = ObserveExamsUseCase(contentRepository),
    refreshSpecialtiesUseCase = RefreshSpecialtiesUseCase(contentRepository),
    refreshExamsUseCase = RefreshExamsUseCase(contentRepository),
    listBatchesUseCase = ListContentImportBatchesUseCase(importRepository),
    getBatchUseCase = GetContentImportBatchUseCase(importRepository),
    uploadPdfUseCase = UploadPdfToStagingUseCase(importRepository),
    updateStagingUseCase = UpdateContentImportStagingUseCase(importRepository),
    publishBatchUseCase = PublishContentImportBatchUseCase(importRepository),
    createDraftUseCase = CreateContentDraftUseCase(contentRepository),
    analyticsTracker = NoOpAnalyticsTracker,
)

class ContentUploadViewModelTest {

    /**
     * Publicar escribe en el contenido canonico por un camino que todavia NO es
     * atomico (content_import_atomicity_candidate_v1 sigue sin aplicarse), asi
     * que dos peticiones solapadas podrian duplicar preguntas. Un doble toque
     * debe producir exactamente UNA publicacion.
     */
    @Test
    fun publishBatch_dispatchedTwiceBeforeItSettles_onlyPublishesOnce() = runTest {
        val importRepository = FakeContentImportRepository()
        val viewModel = newViewModel(testScheduler, importRepository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.OpenBatch("b1"))
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.PublishBatch)
        viewModel.dispatch(ContentUploadIntent.PublishBatch)
        // La primera publicacion queda en vuelo dentro del repositorio: es el
        // instante en el que un segundo toque podria colarse.
        testScheduler.advanceUntilIdle()

        assertEquals(1, importRepository.publishCalls)

        importRepository.release()
        testScheduler.advanceUntilIdle()
        assertEquals(1, importRepository.publishCalls)
    }

    /**
     * Misma guarda en la subida: un doble toque crearia dos lotes en staging
     * para el mismo PDF.
     */
    @Test
    fun uploadPdf_dispatchedTwiceBeforeItSettles_onlyCreatesOneBatch() = runTest {
        val importRepository = FakeContentImportRepository()
        val viewModel = newViewModel(testScheduler, importRepository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.SelectPdf("file://t.pdf", "t.pdf", "application/pdf"))
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.UploadPdf)
        viewModel.dispatch(ContentUploadIntent.UploadPdf)
        testScheduler.advanceUntilIdle()

        assertEquals(1, importRepository.uploadCalls)

        importRepository.release()
        testScheduler.advanceUntilIdle()
        assertEquals(1, importRepository.uploadCalls)
    }

    /**
     * La guarda no debe dejar la pantalla bloqueada para siempre: una vez
     * resuelta la primera publicacion, una segunda accion deliberada del
     * usuario si tiene que llegar al repositorio.
     */
    @Test
    fun publishBatch_afterTheFirstOneSettles_canBeDispatchedAgain() = runTest {
        val importRepository = FakeContentImportRepository()
        val viewModel = newViewModel(testScheduler, importRepository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.OpenBatch("b1"))
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.PublishBatch)
        testScheduler.advanceUntilIdle()
        importRepository.release()
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.PublishBatch)
        testScheduler.advanceUntilIdle()

        assertEquals(2, importRepository.publishCalls)
    }

    @Test
    fun publishBatch_withoutAnOpenBatch_reportsAValidationErrorInsteadOfCalling() = runTest {
        val importRepository = FakeContentImportRepository()
        val viewModel = newViewModel(testScheduler, importRepository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ContentUploadIntent.PublishBatch)
        testScheduler.advanceUntilIdle()

        assertEquals(0, importRepository.publishCalls)
        val error = viewModel.state.value.actionState
        assertEquals(true, error is UiAsync.Error)
    }
}
