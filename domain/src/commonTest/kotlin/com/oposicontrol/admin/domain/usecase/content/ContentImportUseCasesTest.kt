package com.oposicontrol.admin.domain.usecase.content

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.IMPORT_ANNULMENT_REASON_MAX_LENGTH
import com.oposicontrol.admin.domain.repository.ContentImportRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ContentImportUseCasesTest {
    private val repository = object : ContentImportRepository {
        override suspend fun listImportBatches() = AppResult.Success(emptyList<ContentImportBatch>())
        override suspend fun getImportBatch(batchId: String) = AppResult.Success(sampleBatch())
        override suspend fun uploadPdfToStaging(examId: String, examType: String, fileUri: String, fileName: String) = AppResult.Success(sampleBatch())
        override suspend fun updateStaging(item: ContentImportStagingItem) = AppResult.Success(item)
        override suspend fun publishImportBatch(batchId: String, confirmExamPublished: Boolean) = AppResult.Success(sampleBatch())
    }

    @Test
    fun uploadRejectsNonPdfBeforeNetwork() = runTest {
        val result = UploadPdfToStagingUseCase(repository)("exam", "rafaga", "content://file", "questions.txt")

        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun stagingValidationRequiresFourOptionsAndCanonicalAnswer() = runTest {
        val item = sampleBatch().staging.single().copy(optionD = "", correctAnswer = "X")
        val result = UpdateContentImportStagingUseCase(repository)(item)

        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun blankBatchCannotBeLoadedOrPublished() = runTest {
        assertTrue(GetContentImportBatchUseCase(repository)("") is AppResult.Failure)
        assertTrue(PublishContentImportBatchUseCase(repository)("") is AppResult.Failure)
    }

    // -----------------------------------------------------------------
    // Omitir NO es guardar: no exige que la fila esté completa
    // -----------------------------------------------------------------

    private class RecordingRepository : ContentImportRepository {
        val updates = mutableListOf<ContentImportStagingItem>()
        override suspend fun listImportBatches() = AppResult.Success(emptyList<ContentImportBatch>())
        override suspend fun getImportBatch(batchId: String): AppResult<ContentImportBatch> = error("no usado")
        override suspend fun uploadPdfToStaging(examId: String, examType: String, fileUri: String, fileName: String): AppResult<ContentImportBatch> = error("no usado")
        override suspend fun updateStaging(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> {
            updates += item
            return AppResult.Success(item)
        }
        override suspend fun publishImportBatch(batchId: String, confirmExamPublished: Boolean): AppResult<ContentImportBatch> = error("no usado")
    }

    private fun emptyRow(status: String = "error") = ContentImportStagingItem(
        id = "row", position = 3, title = "", optionA = "", optionB = "", optionC = "", optionD = "",
        correctAnswer = "", reasoning = "", sourceText = "", tagText = "", originalBlock = "bloque", parseStatus = status,
        parseError = "No se ha podido leer el enunciado.",
    )

    @Test
    fun skippingARowWithEverythingEmptyMarksItOmittedWithoutValidation() = runTest {
        val repo = RecordingRepository()

        val result = SkipContentImportStagingUseCase(repo)(emptyRow())

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("omitida"), repo.updates.map { it.parseStatus })
        assertEquals(null, repo.updates.single().parseError, "omitir borra el diagnóstico del parser")
    }

    @Test
    fun approvingAnIncompleteRowIsStillRejectedWithAClearMessageAndNoCall() = runTest {
        val repo = RecordingRepository()

        val result = UpdateContentImportStagingUseCase(repo)(emptyRow().copy(parseStatus = "aprobada"))

        assertTrue(result is AppResult.Failure)
        assertEquals("La pregunta necesita título.", result.error.message)
        assertTrue(repo.updates.isEmpty(), "no se llama al servidor con una fila incompleta")

        val noOptions = UpdateContentImportStagingUseCase(repo)(emptyRow().copy(title = "T", parseStatus = "aprobada"))
        assertEquals("Las cuatro opciones deben tener contenido.", (noOptions as AppResult.Failure).error.message)
    }

    @Test
    fun aRowWithoutIdCannotBeSkipped() = runTest {
        assertTrue(SkipContentImportStagingUseCase(RecordingRepository())(emptyRow().copy(id = "")) is AppResult.Failure)
    }

    // -----------------------------------------------------------------
    // Anulada por el tribunal: el motivo sólo viaja con la anulación
    // -----------------------------------------------------------------

    @Test
    fun theAnnulmentReasonIsTrimmedAndOnlyKeptWhileTheRowIsAnnulled() = runTest {
        val repo = RecordingRepository()
        val base = sampleBatch().staging.single()

        UpdateContentImportStagingUseCase(repo)(base.copy(annulled = true, annulmentReason = "  Error del tribunal  "))
        UpdateContentImportStagingUseCase(repo)(base.copy(annulled = false, annulmentReason = "huérfano"))
        UpdateContentImportStagingUseCase(repo)(base.copy(annulled = true, annulmentReason = "   "))

        assertEquals(listOf("Error del tribunal", null, null), repo.updates.map { it.annulmentReason })
        assertEquals(listOf(true, false, true), repo.updates.map { it.annulled })
    }

    @Test
    fun anAnnulmentReasonOverTheServerLimitIsRejectedBeforeTheCall() = runTest {
        val repo = RecordingRepository()
        val long = "x".repeat(IMPORT_ANNULMENT_REASON_MAX_LENGTH + 1)

        val result = UpdateContentImportStagingUseCase(repo)(sampleBatch().staging.single().copy(annulled = true, annulmentReason = long))

        assertTrue(result is AppResult.Failure)
        assertTrue(repo.updates.isEmpty())
    }

    private fun sampleBatch() = ContentImportBatch(
        id = "batch",
        examId = "exam",
        fileName = "questions.pdf",
        examType = "rafaga",
        status = "parseado",
        detectedCount = 1,
        importedCount = 0,
        staging = listOf(
            ContentImportStagingItem("item", 1, "Title", "A", "B", "C", "D", "A", "", "", "", "", "ok"),
        ),
    )
}
