package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.Exam

data class ContentUploadState(
    val specialties: UiAsync<List<Specialty>> = UiAsync.Loading,
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,
    val batches: UiAsync<List<ContentImportBatch>> = UiAsync.Loading,
    val selectedSpecialtyId: String? = null,
    val selectedExamId: String? = null,
    val selectedExamType: String = "rafaga",
    val draftTitle: String = "",
    val selectedPdfUri: String = "",
    val selectedPdfName: String = "",
    val activeBatch: UiAsync<ContentImportBatch> = UiAsync.Idle,
    val actionState: UiAsync<String> = UiAsync.Idle,
) {
    val activeStaging: List<ContentImportStagingItem>
        get() = (activeBatch as? UiAsync.Success)?.value?.staging.orEmpty()
}
