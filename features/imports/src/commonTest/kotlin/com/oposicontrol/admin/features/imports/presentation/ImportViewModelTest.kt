package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportDuplicate
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
import com.oposicontrol.admin.domain.usecase.content.SkipContentImportStagingUseCase
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
        status = ExamStatus.DRAFT, publishFrom = null, publishedAt = null, sortOrder = 0,
        questionCount = 1, typeExam = "rafaga",
        createdAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun stagingItem(
        id: String,
        parseStatus: String = "ok",
        parseError: String? = null,
        duplicate: ContentImportDuplicate? = null,
        link: String? = null,
        blank: Boolean = false,
    ) = ContentImportStagingItem(
        id = id, position = 1, title = if (blank) "" else "Enunciado $id",
        optionA = if (blank) "" else "A", optionB = if (blank) "" else "B", optionC = if (blank) "" else "C", optionD = if (blank) "" else "D",
        correctAnswer = if (blank) "" else "A", reasoning = "", sourceText = "", tagText = "", originalBlock = "",
        parseStatus = parseStatus, parseError = parseError, duplicate = duplicate, linkQuestionId = link,
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
            skipStagingUseCase = SkipContentImportStagingUseCase(importRepo),
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

    /** F1: publicar termina en `importado`: el lote sale «Publicado», sin errores y sin poder repetirse. */
    @Test
    fun publishingLeavesTheBatchPublishedWithNoErrorsAndTheButtonOff() = runTest {
        val published = batch(listOf(stagingItem("s-1", "importada"))).copy(status = "importado", importedCount = 1)
        val repo = FakeImportRepository(
            getResult = AppResult.Success(batch()),
            publishResult = AppResult.Success(published),
        )
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()

        val state = vm.state.value
        val message = (state.actionState as UiAsync.Success).value
        assertTrue(message.startsWith("Lote publicado: 1 de 1"), message)
        val active = (state.activeBatch as UiAsync.Success).value
        assertEquals("Publicado", importBatchStatusLabel(active.status))
        assertEquals(0, active.metrics().errors)
        val gate = importPublishGate(state, active)
        assertFalse(gate.enabled)
        assertEquals("Este lote ya se ha publicado.", gate.reason)
    }

    private suspend fun TestScope.publishAndReadMessage(annulledRows: Int): String {
        val rows = List(annulledRows) { stagingItem("s-$it", "importada").copy(annulled = true) } + stagingItem("n-1", "importada")
        val published = batch(rows).copy(status = "importado", importedCount = rows.size)
        val repo = FakeImportRepository(getResult = AppResult.Success(batch()), publishResult = AppResult.Success(published))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()
        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()
        return (vm.state.value.actionState as UiAsync.Success).value
    }

    /** Concordancia: «1 anulada», no «1 anuladas». */
    @Test
    fun theAnnulledCountInThePublishMessageAgreesInNumber() = runTest {
        val one = publishAndReadMessage(annulledRows = 1)
        assertTrue(one.endsWith("(1 anulada por el tribunal)."), one)
        assertFalse(one.contains("1 anuladas"), one)

        val two = publishAndReadMessage(annulledRows = 2)
        assertTrue(two.endsWith("(2 anuladas por el tribunal)."), two)

        val none = publishAndReadMessage(annulledRows = 0)
        assertFalse(none.contains("anulada"), none)
        assertTrue(none.endsWith("ya están en el examen."), none)
    }

    /** El preflight es una previsión: si ya sabe que hay un bloqueo, no se llama al servidor a ciegas. */
    @Test
    fun aKnownBlockerStopsThePublishBeforeTheServerIsCalled() = runTest {
        val broken = batch(listOf(stagingItem("ok-1"), stagingItem("bad", parseError = "falta la opción D")))
        val repo = FakeImportRepository(getResult = AppResult.Success(broken), publishResult = AppResult.Success(batch()))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()

        assertTrue(repo.publishConfirmations.isEmpty(), "sin llamada al servidor")
        val error = vm.state.value.actionState as UiAsync.Error
        assertEquals("Corrige el error antes de publicar.", error.error.message)
    }

    /** El límite de 200 se avisa antes de llamar. */
    @Test
    fun theExamLimitStopsThePublishWithTheNumbers() = runTest {
        val full = exam.copy(questionCount = 200)
        val repo = FakeImportRepository(getResult = AppResult.Success(batch()), publishResult = AppResult.Success(batch()))
        val vm = buildViewModel(repo, exams = listOf(full))
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()

        assertTrue(repo.publishConfirmations.isEmpty())
        assertTrue((vm.state.value.actionState as UiAsync.Error).error.message.contains("201"))
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

    /** KI3-3: con el examen publicado a la vista, la confirmación se pide ANTES de llamar; la llamada lleva la respuesta. */
    @Test
    fun aPublishedExamAsksForConfirmationBeforeTheCall() = runTest {
        val repo = FakeImportRepository(
            getResult = AppResult.Success(batch()),
            publishResult = AppResult.Success(batch().copy(status = "importado", importedCount = 1)),
        )
        val vm = buildViewModel(repo, exams = listOf(exam.copy(status = ExamStatus.PUBLISHED)))
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()
        assertTrue(vm.state.value.showPublishConfirm, "se pregunta antes de publicar")
        assertTrue(repo.publishConfirmations.isEmpty(), "todavía no se ha llamado")

        vm.dispatch(ImportIntent.ConfirmPublishOnPublishedExam)
        advanceUntilIdle()
        assertEquals(listOf(true), repo.publishConfirmations)
        assertEquals("importado", (vm.state.value.activeBatch as UiAsync.Success).value.status)

        // Y descartarla no publica nada.
        val repo2 = FakeImportRepository(getResult = AppResult.Success(batch()), publishResult = AppResult.Success(batch()))
        val vm2 = buildViewModel(repo2, exams = listOf(exam.copy(status = ExamStatus.PUBLISHED)))
        advanceUntilIdle()
        vm2.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()
        vm2.dispatch(ImportIntent.PublishBatch)
        advanceUntilIdle()
        vm2.dispatch(ImportIntent.DismissPublishConfirm)
        advanceUntilIdle()
        assertFalse(vm2.state.value.showPublishConfirm)
        assertTrue(repo2.publishConfirmations.isEmpty())
    }

    /** IM3: si el cliente no sabía que el examen está publicado, el servidor sigue siendo la autoridad y pide confirmar. */
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

    // -----------------------------------------------------------------
    // F2 — Omitir no exige que la fila esté completa
    // -----------------------------------------------------------------

    @Test
    fun skippingARowWithEverythingEmptyMarksItOmitted() = runTest {
        val empty = stagingItem("s-empty", parseStatus = "error", parseError = "sin enunciado", blank = true)
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(empty))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.SkipStaging(empty))
        advanceUntilIdle()

        assertEquals(listOf("omitida"), repo.updates.map { it.parseStatus })
        val active = (vm.state.value.activeBatch as UiAsync.Success).value
        assertEquals("omitida", active.staging.single().parseStatus)
        assertEquals(ReviewFilter.OMITIDA, stagingTone(active.staging.single()))
        assertIs<UiAsync.Success<String>>(vm.state.value.actionState)
    }

    @Test
    fun approvingAnIncompleteRowIsStillRejectedWithAClearMessage() = runTest {
        val empty = stagingItem("s-empty", parseStatus = "error", blank = true)
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(empty))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.ApproveStaging(empty))
        advanceUntilIdle()

        assertTrue(repo.updates.isEmpty(), "no se llama al servidor con una fila incompleta")
        assertEquals("La pregunta necesita título.", (vm.state.value.actionState as UiAsync.Error).error.message)
    }

    // -----------------------------------------------------------------
    // Vincular a existente
    // -----------------------------------------------------------------

    private val bankDuplicate = ContentImportDuplicate("banco", "q-77", "PREG-0077")

    @Test
    fun linkingToTheExistingQuestionApprovesTheRowLinkedAndCreatesNothingNew() = runTest {
        val dup = stagingItem("s-dup", duplicate = bankDuplicate)
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(dup))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()
        assertEquals(ReviewFilter.DUPLICADA, stagingTone(dup))

        vm.dispatch(ImportIntent.LinkStagingToExisting(dup))
        advanceUntilIdle()

        val sent = repo.updates.single()
        assertEquals("aprobada", sent.parseStatus)
        assertEquals("q-77", sent.linkQuestionId)
        val row = (vm.state.value.activeBatch as UiAsync.Success).value.staging.single()
        assertEquals(ReviewFilter.LISTA, stagingTone(row))
        assertEquals("Vinculada a PREG-0077", importRowLinkLabel(row))
        // El PATCH del servidor no devuelve la coincidencia: se conserva, para poder deshacer.
        assertEquals("PREG-0077", row.duplicate?.questionCode)
        assertEquals(row, vm.state.value.editDraft, "el editor sigue abierto y refleja el vínculo")
        assertTrue((vm.state.value.actionState as UiAsync.Success).value.contains("PREG-0077"))
        assertEquals(0, repo.uploadCallCount)
    }

    @Test
    fun linkingUsesTheStoredRowNotADraftThatNoLongerMatchesTheExistingQuestion() = runTest {
        val dup = stagingItem("s-dup", duplicate = bankDuplicate)
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(dup))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.LinkStagingToExisting(dup.copy(title = "Texto a medio editar")))
        advanceUntilIdle()

        assertEquals("Enunciado s-dup", repo.updates.single().title)
    }

    @Test
    fun aBatchDuplicateCannotBeLinked() = runTest {
        val dup = stagingItem("s-dup", duplicate = ContentImportDuplicate("lote", null, "", "s-1"))
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(dup))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.LinkStagingToExisting(dup))
        advanceUntilIdle()

        assertTrue(repo.updates.isEmpty())
        assertIs<UiAsync.Error>(vm.state.value.actionState)
    }

    @Test
    fun unlinkingLeavesTheRowAsAnUnresolvedDuplicateAgain() = runTest {
        val linked = stagingItem("s-dup", parseStatus = "aprobada", duplicate = bankDuplicate, link = "q-77")
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(linked))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.UnlinkStaging(linked))
        advanceUntilIdle()

        val sent = repo.updates.single()
        assertEquals("aprobada", sent.parseStatus)
        assertEquals(null, sent.linkQuestionId)
        val row = (vm.state.value.activeBatch as UiAsync.Success).value.staging.single()
        assertEquals(ReviewFilter.DUPLICADA, stagingTone(row))
        assertEquals(null, importRowLinkLabel(row))
        // Y otra vez se puede vincular.
        assertEquals("Vincular a existente (PREG-0077)", importRowActions(row, batchPublished = false).linkLabel)
    }

    // -----------------------------------------------------------------
    // Anulada por el tribunal: se corrige con el mismo guardado de fila
    // -----------------------------------------------------------------

    @Test
    fun theAnnulmentIsCorrectedAndSavedWithTheSameRowSave() = runTest {
        val row = stagingItem("s-1")
        val repo = FakeImportRepository(getResult = AppResult.Success(batch(listOf(row))))
        val vm = buildViewModel(repo)
        advanceUntilIdle()
        vm.dispatch(ImportIntent.OpenBatch("b-1"))
        vm.dispatch(ImportIntent.SelectStaging("s-1"))
        advanceUntilIdle()

        vm.dispatch(ImportIntent.UpdateEditDraft(vm.state.value.editDraft!!.copy(annulled = true, annulmentReason = " Error del tribunal ")))
        vm.dispatch(ImportIntent.SaveEditDraft)
        advanceUntilIdle()

        val sent = repo.updates.single()
        assertTrue(sent.annulled)
        assertEquals("Error del tribunal", sent.annulmentReason)
        val saved = (vm.state.value.activeBatch as UiAsync.Success).value.staging.single()
        assertTrue(saved.annulled)
        // Estado de parseo intacto: `ok` + anulada es una fila lista y anulada.
        assertEquals(ReviewFilter.LISTA, stagingTone(saved))
        assertEquals(1, (vm.state.value.activeBatch as UiAsync.Success).value.metrics().annulled)

        // Desanular borra el motivo.
        vm.dispatch(ImportIntent.UpdateEditDraft(vm.state.value.editDraft!!.copy(annulled = false)))
        vm.dispatch(ImportIntent.SaveEditDraft)
        advanceUntilIdle()
        assertEquals(null, repo.updates.last().annulmentReason)
        assertFalse(repo.updates.last().annulled)
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

    /** Lo que llegó a `updateStaging`, en orden. */
    val updates = mutableListOf<ContentImportStagingItem>()

    override suspend fun updateStaging(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> {
        updates += item
        // Como el servidor real, la respuesta NO trae la coincidencia detectada (`duplicado`).
        return updateResult?.invoke(item) ?: AppResult.Success(item.copy(duplicate = null))
    }

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
