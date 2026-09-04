package com.oposicontrol.admin.features.exams.presentation

sealed interface ExamsIntent {
    data object RefreshAll : ExamsIntent
    data object RefreshResourceAudit : ExamsIntent
    data object CreateNew : ExamsIntent
    data object CreateRafaga : ExamsIntent
    data object SaveExam : ExamsIntent
    data object SaveRafaga : ExamsIntent
    data object DeleteSelectedExam : ExamsIntent
    data object DeleteSelectedRafaga : ExamsIntent
    data object RequestDeleteSelectedExam : ExamsIntent
    data object RequestDeleteSelectedRafaga : ExamsIntent
    data object CreateSpecialty : ExamsIntent
    data object SaveSpecialty : ExamsIntent
    data object DeleteSelectedSpecialty : ExamsIntent
    data object RequestDeleteSelectedSpecialty : ExamsIntent
    data object CreateQuestion : ExamsIntent
    data object CreateRafagaQuestion : ExamsIntent
    data object SaveQuestion : ExamsIntent
    data object SaveRafagaQuestion : ExamsIntent
    data object DeleteSelectedQuestion : ExamsIntent
    data object DeleteSelectedRafagaQuestion : ExamsIntent
    data object RequestDeleteSelectedQuestion : ExamsIntent
    data object RequestDeleteSelectedRafagaQuestion : ExamsIntent
    data object RequestClearExamCover : ExamsIntent
    data object PublishExam : ExamsIntent
    data object UnpublishExam : ExamsIntent
    data object ConfirmPendingDelete : ExamsIntent
    data object DismissPendingDelete : ExamsIntent
    data class SelectExam(val id: String) : ExamsIntent
    data class SelectRafaga(val id: String) : ExamsIntent
    data class SelectQuestion(val id: String) : ExamsIntent
    data class SelectRafagaQuestion(val id: String) : ExamsIntent
    data class MoveQuestionUp(val id: String) : ExamsIntent
    data class MoveQuestionDown(val id: String) : ExamsIntent
    data class MoveRafagaQuestionUp(val id: String) : ExamsIntent
    data class MoveRafagaQuestionDown(val id: String) : ExamsIntent
    data class UpdateSearch(val value: String) : ExamsIntent
    data class UpdateStatusFilter(val value: String) : ExamsIntent
    data class UpdateQuestionResourceFilter(val value: String) : ExamsIntent
    data class UpdateSpecialtySearch(val value: String) : ExamsIntent
    data class SelectContentSpecialty(val id: String) : ExamsIntent
    data class UpdateSpecialtyName(val value: String) : ExamsIntent
    data class UpdateSpecialtyDescription(val value: String) : ExamsIntent
    data class UpdateSpecialtySortOrder(val value: String) : ExamsIntent
    data class ToggleSpecialtyActive(val value: Boolean) : ExamsIntent
    data class UpdateTitle(val value: String) : ExamsIntent
    data class UpdateDescription(val value: String) : ExamsIntent
    data class UpdateCode(val value: String) : ExamsIntent
    data class UpdatePriceTickets(val value: String) : ExamsIntent
    data class UpdateSpecialtyId(val value: String) : ExamsIntent
    data class UpdateCommunityId(val value: String?) : ExamsIntent
    data class UpdateTypeExam(val value: String) : ExamsIntent
    data class UpdateExamStatus(val value: String) : ExamsIntent
    data class UpdatePublishFrom(val value: String) : ExamsIntent
    data class UpdateTypeAccess(val value: String) : ExamsIntent
    data class UpdateYear(val value: String) : ExamsIntent
    data class ToggleIsOfficial(val value: Boolean) : ExamsIntent
    data class UpdateDurationMinutes(val value: String) : ExamsIntent
    data class SelectExamCoverFile(val uri: String, val suggestedName: String, val mimeType: String?) : ExamsIntent
    data object UploadExamCover : ExamsIntent
    data object ClearExamCover : ExamsIntent
    data class UpdateQuestionTitle(val value: String) : ExamsIntent
    data class UpdateQuestionStatement(val value: String) : ExamsIntent
    data class UpdateOptionContent(val optionId: String, val value: String) : ExamsIntent
    data class SelectCorrectOption(val optionId: String) : ExamsIntent
    data object AddResource : ExamsIntent
    data class RemoveResource(val resourceId: String) : ExamsIntent
    data class RequestRemoveResource(val resourceId: String) : ExamsIntent
    data class UpdateResourceType(val resourceId: String, val value: String) : ExamsIntent
    data class UpdateResourceTitle(val resourceId: String, val value: String) : ExamsIntent
    data class UpdateResourceValue(val resourceId: String, val value: String) : ExamsIntent
    data class SelectResourceFile(
        val resourceId: String,
        val uri: String,
        val suggestedName: String,
        val mimeType: String?,
    ) : ExamsIntent
    data class ImportQuestionsFromText(val fileName: String, val content: String) : ExamsIntent
    data class UploadResource(val resourceId: String) : ExamsIntent
}
