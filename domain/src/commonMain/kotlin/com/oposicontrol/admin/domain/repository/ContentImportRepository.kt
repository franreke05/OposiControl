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

    /**
     * Publica el lote con UNA llamada (KI3-1) y devuelve el lote releído.
     *
     * [confirmExamPublished] es la respuesta del administrador al aviso
     * `IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED` («el examen está publicado: las
     * preguntas se verán al instante»). Viaja `true` sólo tras confirmarlo en un
     * diálogo; por defecto se pregunta.
     */
    suspend fun publishImportBatch(batchId: String, confirmExamPublished: Boolean = false): AppResult<ContentImportBatch>
}
