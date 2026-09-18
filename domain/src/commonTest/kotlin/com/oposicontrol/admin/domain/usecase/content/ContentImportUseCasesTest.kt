package com.oposicontrol.admin.domain.usecase.content

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.repository.ContentImportRepository
import kotlin.test.Test
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
