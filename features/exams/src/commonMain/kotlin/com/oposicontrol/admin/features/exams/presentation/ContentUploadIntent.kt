package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.domain.model.ContentImportStagingItem

sealed interface ContentUploadIntent {
    data object Refresh : ContentUploadIntent
    data class SelectSpecialty(val id: String) : ContentUploadIntent
    data class SelectExam(val id: String) : ContentUploadIntent
    data class SelectType(val value: String) : ContentUploadIntent
    data class UpdateDraftTitle(val value: String) : ContentUploadIntent
    data object CreateDraft : ContentUploadIntent
    data class SelectPdf(val uri: String, val name: String, val mimeType: String?) : ContentUploadIntent
    data object UploadPdf : ContentUploadIntent
    data class OpenBatch(val id: String) : ContentUploadIntent
    data object CloseBatch : ContentUploadIntent
    data class SaveStaging(val item: ContentImportStagingItem) : ContentUploadIntent
    data class SkipStaging(val item: ContentImportStagingItem) : ContentUploadIntent
    data object PublishBatch : ContentUploadIntent
}
