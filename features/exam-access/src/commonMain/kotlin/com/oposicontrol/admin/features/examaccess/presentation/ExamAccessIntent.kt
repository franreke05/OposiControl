package com.oposicontrol.admin.features.examaccess.presentation

sealed interface ExamAccessIntent {
    data object Refresh : ExamAccessIntent

    /** Abre el formulario de una concesión nueva, en blanco. */
    data object CreateNew : ExamAccessIntent

    /** Cierra el formulario sin conceder nada. */
    data object DiscardDraft : ExamAccessIntent
    data class Select(val id: String) : ExamAccessIntent
    data class UpdateSearch(val value: String) : ExamAccessIntent
    data class UpdateFilter(val filter: AccessFilter) : ExamAccessIntent

    data class UpdateUserQuery(val value: String) : ExamAccessIntent

    /** Busca personas en el servidor. Es explícito (botón), no por pulsación: cada búsqueda es una petición. */
    data object SearchUsers : ExamAccessIntent
    data class PickUser(val userId: String) : ExamAccessIntent
    data object ClearUser : ExamAccessIntent

    data class UpdateExamQuery(val value: String) : ExamAccessIntent
    data class PickExam(val examId: String) : ExamAccessIntent
    data object ClearExam : ExamAccessIntent
    data object RetryExams : ExamAccessIntent

    data class UpdateReason(val code: String) : ExamAccessIntent
    data object Grant : ExamAccessIntent

    /** Retirar pide confirmación: [RequestRevoke] abre el diálogo y [ConfirmRevoke] escribe. */
    data object RequestRevoke : ExamAccessIntent
    data object ConfirmRevoke : ExamAccessIntent
    data object DismissRevoke : ExamAccessIntent
}
