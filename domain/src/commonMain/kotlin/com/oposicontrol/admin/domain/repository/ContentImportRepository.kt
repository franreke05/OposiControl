package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem

interface ContentImportRepository {
    suspend fun listImportBatches(): AppResult<List<ContentImportBatch>>
    suspend fun getImportBatch(batchId: String): AppResult<ContentImportBatch>
    suspend fun uploadPdfToStaging(
        examId: String,
        examType: String,
        fileUri: String,
        fileName: String,
    ): AppResult<ContentImportBatch>
    suspend fun updateStaging(item: ContentImportStagingItem): AppResult<ContentImportStagingItem>
    suspend fun publishImportBatch(batchId: String): AppResult<ContentImportBatch>
}
