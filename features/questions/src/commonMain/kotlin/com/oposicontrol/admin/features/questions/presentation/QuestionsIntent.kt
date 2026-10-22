package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind

sealed interface QuestionsIntent {
    data object Refresh : QuestionsIntent

    data class Search(val query: String) : QuestionsIntent
    data class SetTypeFilter(val type: QuestionType?) : QuestionsIntent
    data class SetExamFilter(val examId: String?) : QuestionsIntent
    data class SetActiveFilter(val active: QuestionActiveFilter) : QuestionsIntent
    data class SetSort(val sort: QuestionsSort) : QuestionsIntent
    data class SetPageIndex(val index: Int) : QuestionsIntent
    data class SetPageSize(val size: Int) : QuestionsIntent

    data class SelectQuestion(val id: String) : QuestionsIntent
    data object StartCreate : QuestionsIntent
    data class UpdateCreateExamId(val examId: String) : QuestionsIntent

    data class UpdateStatement(val value: String) : QuestionsIntent
    data class UpdateType(val type: QuestionType) : QuestionsIntent
    data class UpdateOptionContent(val label: String, val value: String) : QuestionsIntent
    data class SetCorrectOption(val label: String) : QuestionsIntent
    data class UpdateExplanation(val value: String) : QuestionsIntent
    data class UpdateSource(val value: String) : QuestionsIntent
    data class SetActive(val active: Boolean) : QuestionsIntent

    data object RequestAssociateExam : QuestionsIntent
    data class UpdateAssociateExamId(val examId: String) : QuestionsIntent
    data class UpdateAssociatePosition(val value: String) : QuestionsIntent
    data object ConfirmAssociateExam : QuestionsIntent
    data object CancelAssociateDialog : QuestionsIntent

    /** Quitar la pregunta de un examen (KQ3-2): pide confirmación y después llama a la ruta propia. */
    data class RequestUnlinkExam(val examId: String) : QuestionsIntent
    data object ConfirmUnlinkExam : QuestionsIntent
    data object DismissUnlinkConfirm : QuestionsIntent

    // -----------------------------------------------------------------
    // Recursos de una pregunta inmersiva (KQ4)
    //
    // El alta es un formulario propio y no parte del borrador de la pregunta: se
    // guarda por su ruta, sin reenviar enunciado ni opciones. Es la misma razón por
    // la que vincular a un examen dejó de ser un PATCH con el borrador entero.
    // -----------------------------------------------------------------

    data object RequestAddResource : QuestionsIntent
    data object CancelResourceDialog : QuestionsIntent

    /** Los tres tipos que se pueden crear: imagen, audio y vídeo. Ver [QuestionResourceKind]. */
    data class UpdateResourceKind(val kind: QuestionResourceKind) : QuestionsIntent
    data class UpdateResourceTitle(val value: String) : QuestionsIntent
    data class UpdateResourceDescription(val value: String) : QuestionsIntent

    /** Los bytes ya leídos por el selector de la plataforma. */
    data class PickResourceFile(val file: QuestionResourceFile) : QuestionsIntent
    data object ClearResourceFile : QuestionsIntent
    data object ConfirmAddResource : QuestionsIntent

    /** Archivar un recurso es destructivo y no se puede deshacer: se confirma. */
    data class RequestDeleteResource(val resourceId: String) : QuestionsIntent
    data object ConfirmDeleteResource : QuestionsIntent
    data object DismissDeleteResourceConfirm : QuestionsIntent

    // -----------------------------------------------------------------
    // Etiquetas (temas): selección propia, guardada por su ruta
    // (`PUT /preguntas/{id}/etiquetas`) con «Guardar etiquetas».
    // -----------------------------------------------------------------

    data class AddQuestionTag(val tagId: String) : QuestionsIntent
    data class RemoveQuestionTag(val tagId: String) : QuestionsIntent
    data class UpdateTagSearch(val query: String) : QuestionsIntent
    data object SaveQuestionTags : QuestionsIntent
    /** Vuelve a pedir etiquetas de la pregunta y catálogo tras un fallo. */
    data object ReloadQuestionTags : QuestionsIntent

    data object RequestCreateTag : QuestionsIntent
    data class UpdateNewTagName(val value: String) : QuestionsIntent
    data object CancelCreateTag : QuestionsIntent
    /** Crea la etiqueta (`POST /etiquetas`) y la añade a la selección; no la guarda en la pregunta. */
    data object ConfirmCreateTag : QuestionsIntent

    data object RequestCancel : QuestionsIntent
    data object ConfirmCancel : QuestionsIntent
    data object DismissCancelConfirm : QuestionsIntent

    data object RequestDelete : QuestionsIntent
    data object ConfirmDelete : QuestionsIntent
    data object DismissDeleteConfirm : QuestionsIntent

    data object Save : QuestionsIntent
}
