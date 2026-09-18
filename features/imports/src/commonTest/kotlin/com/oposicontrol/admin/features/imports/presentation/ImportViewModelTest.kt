package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ContentExamType
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
import com.oposicontrol.admin.domain.usecase.content.GetContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.ListContentImportBatchesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.UpdateContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadPdfToStagingUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val exam = Exam(
        id = "ex-1", code = "EX-0012", specialtyId = "sp-1", specialtyName = "Administrativo",
        title = "Administrativo del Estado 2024", description = "", priceTickets = 0,
        status = ExamStatus.PUBLISHED, publishFrom = null, publishedAt = null, sortOrder = 0,
        questionCount = 1, typeExam = "rafaga",
        createdAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun stagingItem(id: String, parseStatus: String = "ok", parseError: String? = null) = ContentImportStagingItem(
        id = id, position = 1, title = "Enunciado $id", optionA = "A", optionB = "B", optionC = "C", optionD = "D",
        correctAnswer = "A", reasoning = "", sourceText = "", tagText = "", originalBlock = "",
        parseStatus = parseStatus, parseError = parseError,
    )

    private fun batch(staging: List<ContentImportStagingItem> = listOf(stagingItem("s-1"))) = ContentImportBatch(
        id = "b-1", examId = "ex-1", fileName = "bloque.pdf", examType = "rafaga", status = "parseado",
        detectedCount = staging.size, importedCount = 0, staging = staging,
    )

    private fun TestScope.buildViewModel(importRepo: FakeImportRepository, exams: List<Exam> = listOf(exam)): ImportViewModel {
        val contentRepo = FakeContentRepo(exams)
        return ImportViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeExamsUseCase = ObserveAllExamsUseCase(contentRepo),
            refreshExamsUseCase = RefreshAllExamsUseCase(contentRepo),
            listBatchesUseCase = ListContentImportBatchesUseCase(importRepo),
            getBatchUseCase = GetContentImportBatchUseCase(importRepo),
            uploadPdfUseCase = UploadPdfToStagingUseCase(importRepo),
            updateStagingUseCase = UpdateContentImportStagingUseCase(importRepo),
            publishBatchUseCase = PublishContentImportBatchUseCase(importRepo),
        )
    }

    @Test
    fun loadsRecentBatchesOnStartup() = runTest {
        val repo = FakeImportRepository(listResult = AppResult.Success(listOf(batch())))
        val vm = buildViewModel(repo)
        advanceUntilIdle()

        assertIs<UiAsync.Success<List<ContentImportBatch>>>(vm.state.value.batches)
    }

    @Test
    fun selectingTypeResetsExamSelection() = runTest {
        val repo = FakeImportRepository()
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.SelectExam("ex-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.SelectType(ContentExamType.INMERSIVO))
        advanceUntilIdle()

        assertEquals(ContentExamType.INMERSIVO, vm.state.value.selectedType)
        assertEquals(null, vm.state.value.selectedExamId)
    }

    @Test
    fun uploadWithoutExamIsRejectedClientSideWithoutACall() = runTest {
        val repo = FakeImportRepository()
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.PdfPicked(PickedPdf("/tmp/a.pdf", "a.pdf", 100)))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.UploadPdf)
        advanceUntilIdle()

        assertEquals(0, repo.uploadCallCount)
        assertIs<UiAsync.Error>(vm.state.value.actionState)
    }

    @Test
    fun uploadingOpensTheBatchInStaging() = runTest {
        val repo = FakeImportRepository(uploadResult = AppResult.Success(batch()))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.SelectExam("ex-1"))
        vm.dispatch(ImportIntent.PdfPicked(PickedPdf("/tmp/a.pdf", "a.pdf", 100)))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.UploadPdf)
        advanceUntilIdle()

        assertIs<UiAsync.Success<ContentImportBatch>>(vm.state.value.activeBatch)
        assertEquals("", vm.state.value.pickedPdfName)
    }

    @Test
    fun brokenRowIsClassifiedAsErrorNotAsLista() = runTest {
        val broken = stagingItem("s-err", parseStatus = "ok", parseError = "ID_EXAMEN EX-0099 no existe.")
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(broken))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()

        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        val active = (vm.state.value.activeBatch as UiAsync.Success).value
        assertEquals(ReviewFilter.ERROR, stagingTone(active.staging.first()))
    }

    @Test
    fun reviewFilterNarrowsVisibleStaging() = runTest {
        val rows = listOf(stagingItem("ok-1", "ok"), stagingItem("skip-1", "omitida"))
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(rows)))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.SetReviewFilter(ReviewFilter.LISTA))
        advanceUntilIdle()

        assertEquals(listOf("ok-1"), vm.state.value.visibleStaging().map { it.id })
    }

    @Test
    fun approvingAStagingRowUpdatesItInPlace() = runTest {
        val row = stagingItem("s-1", "ok")
        val repo = FakeImportRepository(
            getResult = AppResult.Success(batch(listOf(row))),
            updateResult = { item -> AppResult.Success(item) },
        )
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.ApproveStaging(row))
        advanceUntilIdle()

        val active = (vm.state.value.activeBatch as UiAsync.Success).value
        assertEquals("aprobada", active.staging.first().parseStatus)
    }

    @Test
    fun publishDisabledWhileErrorsRemainIsEnforcedByTheScreenNotTheViewModelAlone() = runTest {
        // El ViewModel no bloquea la llamada en sí (eso lo hace el botón en la
        // pantalla), pero el resultado real del backend debe reflejarse
        // honestamente, incluida una publicación parcial.
        val repo = FakeImportRepository(
            getResult = AppResult.Success(batch()),
            publishResult = AppResult.Success(batch().copy(status = "parcial", importedCount = 0)),
        )
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()

        val message = (vm.state.value.actionState as UiAsync.Success).value
        assertTrue(message.contains("parcial"), "esperaba mensaje de importación parcial, fue: $message")
    }

    @Test
    fun backendFailureOnPublishIsHonestNotSilentSuccess() = runTest {
        val repo = FakeImportRepository(
            getResult = AppResult.Success(batch()),
            publishResult = AppResult.Failure(AppError.Validation("el lote no existe")),
        )
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(vm.state.value.actionState)
    }

    /** KI0-1 (IMP-01): el selector observa TODOS los exámenes; con Ráfaga elegida, la ráfaga aparece. */
    @Test
    fun rafagaExamsAppearForTheRafagaType() = runTest {
        val razonado = exam.copy(id = "ex-2", code = "EX-0013", typeExam = "razonado")
        val vm = buildViewModel(FakeImportRepository(), exams = listOf(exam, razonado))
        advanceUntilIdle()

        assertEquals(ContentExamType.RAFAGA, vm.state.value.selectedType)
        assertEquals(listOf("ex-1"), vm.state.value.examsForSelectedType.map { it.id })

        vm.dispatch(ImportIntent.SelectType(ContentExamType.RAZONADO))
        advanceUntilIdle()
        assertEquals(listOf("ex-2"), vm.state.value.examsForSelectedType.map { it.id })
    }

    /** IM3: el servidor pide confirmar porque el examen está publicado; la segunda llamada lleva la respuesta. */
    @Test
    fun aPublishedExamAsksForConfirmationAndRetriesWithIt() = runTest {
        val repo = FakeImportRepository(
            getResult = AppResult.Success(batch()),
            publishResponder = { confirmed ->
                if (confirmed) {
                    AppResult.Success(batch().copy(status = "importado", importedCount = 1))
                } else {
                    AppResult.Failure(AppError.Validation("El examen está publicado", code = IMPORT_EXAM_PUBLISHED_CONFIRMATION_CODE))
                }
            },
        )
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()
        assertTrue(vm.state.value.showPublishConfirm, "sin confirmar no se publica: se pregunta")
        assertEquals(listOf(false), repo.publishConfirmations)

        vm.dispatch(ImportIntent.ConfirmPublishOnPublishedExam)
        advanceUntilIdle()
        assertEquals(listOf(false, true), repo.publishConfirmations)
        assertEquals("importado", (vm.state.value.activeBatch as UiAsync.Success).value.status)
        assertTrue((vm.state.value.actionState as UiAsync.Success).value.contains("publicado"))
    }

    /** Idempotencia ante timeout: tras un corte de red se RELEE el lote antes de dar nada por fallido. */
    @Test
    fun aNetworkCutRereadsTheBatchBeforeDeclaringFailure() = runTest {
        val repo = FakeImportRepository(
            getResults = listOf(
                AppResult.Success(batch()),
                AppResult.Success(batch().copy(status = "importado", importedCount = 1)),
            ),
            publishResult = AppResult.Failure(AppError.Network("timeout")),
        )
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()

        assertEquals(1, repo.publishConfirmations.size, "no se reintenta a ciegas")
        assertEquals("importado", (vm.state.value.activeBatch as UiAsync.Success).value.status)
        assertIs<UiAsync.Success<String>>(vm.state.value.actionState)
    }

    @Test
    fun closingBatchResetsReviewState() = runTest {
        val repo = FakeImportRepository(getResult = AppResult.Success(batch()))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        vm.dispatch(ImportIntent.SetReviewSearch("algo"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.CloseBatch)
        advanceUntilIdle()

        assertEquals(UiAsync.Idle, vm.state.value.activeBatch)
        assertEquals("", vm.state.value.reviewSearch)
    }
}

private class FakeImportRepository(
    private val listResult: AppResult<List<ContentImportBatch>> = AppResult.Success(emptyList()),
    private val getResult: AppResult<ContentImportBatch>? = null,
    /** Una respuesta por lectura, en orden; la última se repite. Para «relee y encuentra el lote ya publicado». */
    private val getResults: List<AppResult<ContentImportBatch>> = emptyList(),
    private val uploadResult: AppResult<ContentImportBatch>? = null,
    private val updateResult: ((ContentImportStagingItem) -> AppResult<ContentImportStagingItem>)? = null,
    private val publishResult: AppResult<ContentImportBatch>? = null,
    private val publishResponder: ((confirmed: Boolean) -> AppResult<ContentImportBatch>)? = null,
) : ContentImportRepository {
    var uploadCallCount = 0
        private set
    private var getCalls = 0
    /** El valor de `confirmExamPublished` de cada publicación, en orden. */
    val publishConfirmations = mutableListOf<Boolean>()

    override suspend fun listImportBatches(): AppResult<List<ContentImportBatch>> = listResult
    override suspend fun getImportBatch(batchId: String): AppResult<ContentImportBatch> {
        val sequenced = getResults.getOrNull(getCalls) ?: getResults.lastOrNull()
        getCalls += 1
        return sequenced ?: getResult ?: AppResult.Failure(AppError.Validation("no batch configured"))
    }

    override suspend fun uploadPdfToStaging(examId: String, examType: String, fileUri: String, fileName: String): AppResult<ContentImportBatch> {
        uploadCallCount += 1
        return uploadResult ?: AppResult.Failure(AppError.Validation("no upload configured"))
    }

    override suspend fun updateStaging(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> =
        updateResult?.invoke(item) ?: AppResult.Success(item)

    override suspend fun publishImportBatch(batchId: String, confirmExamPublished: Boolean): AppResult<ContentImportBatch> {
        publishConfirmations += confirmExamPublished
        return publishResponder?.invoke(confirmExamPublished)
            ?: publishResult
            ?: AppResult.Failure(AppError.Validation("no publish configured"))
    }
}

private class FakeContentRepo(private val exams: List<Exam>) : ContentRepository {
    override fun observeSpecialties(): Flow<List<Specialty>> = MutableStateFlow(emptyList())
    override fun observeExams(): Flow<List<Exam>> = MutableStateFlow(exams)
    override fun observeRafagas(): Flow<List<Rafaga>> = MutableStateFlow(emptyList())
    override suspend fun refreshSpecialties() = AppResult.Success(emptyList<Specialty>())
    override suspend fun refreshExams() = AppResult.Success(exams)
    override suspend fun refreshRafagas() = AppResult.Success(emptyList<Rafaga>())
    override suspend fun getExamQuestions(examId: String) = AppResult.Success(emptyList<ExamQuestion>())
    override suspend fun getRafagaQuestions(rafagaId: String) = AppResult.Success(emptyList<RafagaQuestion>())
    override suspend fun getExamResourceAudit(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun upsertSpecialty(specialty: Specialty) = AppResult.Success(specialty)
    override suspend fun deleteSpecialty(specialtyId: String) = AppResult.Success(Unit)
    override suspend fun getSpecialtyCapabilities(specialtyId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun saveSpecialtyCapabilities(capabilities: SpecialtyCapabilities) = AppResult.Success(capabilities)
    override suspend fun upsertExam(exam: Exam) = AppResult.Success(exam)
    override suspend fun publishExam(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun unpublishExam(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun deleteExam(examId: String) = AppResult.Success(Unit)
    override suspend fun upsertRafaga(rafaga: Rafaga) = AppResult.Success(rafaga)
    override suspend fun deleteRafaga(rafagaId: String) = AppResult.Success(Unit)
    override suspend fun upsertQuestion(question: ExamQuestion) = AppResult.Success(question)
    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>) = AppResult.Success(questions)
    override suspend fun moveQuestion(examId: String, questionId: String, direction: QuestionMoveDirection) =
        AppResult.Success(emptyList<ExamQuestion>())
    override suspend fun upsertRafagaQuestion(question: RafagaQuestion) = AppResult.Success(question)
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String) = AppResult.Success(Unit)
    override suspend fun moveRafagaQuestion(rafagaId: String, questionId: String, direction: QuestionMoveDirection) =
        AppResult.Success(emptyList<RafagaQuestion>())
    override suspend fun uploadResource(questionId: String, resourceId: String, type: QuestionResourceType, localPathOrUrl: String, title: String) =
        AppResult.Failure(AppError.Validation("not used"))
    override suspend fun deleteResource(questionId: String, resourceId: String) = AppResult.Success(Unit)
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun clearExamCover(examId: String) = AppResult.Failure(AppError.Validation("not used"))
}
