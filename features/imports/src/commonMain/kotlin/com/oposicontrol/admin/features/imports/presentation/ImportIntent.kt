package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportStagingItem

sealed interface ImportIntent {
    data object Refresh : ImportIntent
    data class SelectType(val type: ContentExamType) : ImportIntent
    data class SelectExam(val examId: String) : ImportIntent
    data class PdfPicked(val pdf: PickedPdf) : ImportIntent
    data object UploadPdf : ImportIntent

    data class OpenBatch(val id: String) : ImportIntent
    data object CloseBatch : ImportIntent

    data class SetReviewFilter(val filter: ReviewFilter) : ImportIntent
    data class SetReviewSearch(val query: String) : ImportIntent
    data class SelectStaging(val id: String?) : ImportIntent
    data class UpdateEditDraft(val item: ContentImportStagingItem) : ImportIntent
    data object SaveEditDraft : ImportIntent
    data class ApproveStaging(val item: ContentImportStagingItem) : ImportIntent
    data class SkipStaging(val item: ContentImportStagingItem) : ImportIntent

    data object PublishBatch : ImportIntent
    data object ToggleHowItWorks : ImportIntent
    data object DismissAction : ImportIntent
}
