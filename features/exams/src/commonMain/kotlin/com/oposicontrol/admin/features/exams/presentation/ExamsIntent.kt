package com.oposicontrol.admin.features.exams.presentation

sealed interface ExamsIntent {
    data object RefreshAll : ExamsIntent
    data object RefreshResourceAudit : ExamsIntent

    /**
     * Volver a pedir las preguntas del examen abierto, sin tocar el editor.
     *
     * Es el «Reintentar» de Preguntas y Recursos. Antes reutilizaban [SelectExam],
     * que re-siembra el editor desde el examen guardado: reintentar una carga
     * fallida en una pestaña tiraba en silencio los cambios sin guardar de otra.
     */
    data object RetryLoadQuestions : ExamsIntent
    data object CreateNew : ExamsIntent
    data object CreateRafaga : ExamsIntent
    data object SaveExam : ExamsIntent
    data object SaveRafaga : ExamsIntent
    /**
     * **Borra el examen abierto, para siempre.** Lo ejecuta
     * `ConfirmPendingDelete`; quien lo pide desde la pantalla manda
     * [RequestDeleteSelectedExam], que es lo que abre la confirmación.
     *
     * Hasta EXAM-CRUD esta intención archivaba, porque el endpoint archivaba.
     * Ahora archivar es [ArchiveSelectedExam] y son dos cosas distintas de arriba
     * abajo: dos intenciones, dos confirmaciones, dos rutas y dos estados.
     */
    data object DeleteSelectedExam : ExamsIntent

    /** Archiva el examen abierto (reversible). Ver [RequestArchiveSelectedExam]. */
    data object ArchiveSelectedExam : ExamsIntent
    data object DeleteSelectedRafaga : ExamsIntent

    /** Pide confirmación para BORRAR el examen (irreversible). */
    data object RequestDeleteSelectedExam : ExamsIntent

    /** Pide confirmación para ARCHIVAR el examen (reversible). */
    data object RequestArchiveSelectedExam : ExamsIntent
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

    /**
     * Abrir otro examen, o empezar uno nuevo (`null`), CONFIRMANDO antes si el
     * editor tiene cambios sin guardar.
     *
     * Es distinto de [SelectExam] a propósito y las dos tienen que existir.
     * [SelectExam] abre y punto: es lo que necesita «Descartar» de la barra
     * inferior —descartar ES abrir de nuevo el examen guardado, y preguntar ahí
     * sería preguntar dos veces por lo mismo—. El «Reintentar» de Preguntas y
     * Recursos ya NO la usa: re-sembraría el editor (ver [RetryLoadQuestions]).
     *
     * Ésta es la que se dispara desde el catálogo, donde el clic significa «me
     * voy a otro sitio» y puede costar lo que hubiera escrito.
     */
    data class SwitchExam(val id: String?) : ExamsIntent

    data class SelectExam(val id: String) : ExamsIntent
    data class SelectRafaga(val id: String) : ExamsIntent
    data class SelectQuestion(val id: String) : ExamsIntent
    data class SelectRafagaQuestion(val id: String) : ExamsIntent
    /**
     * Quitar una pregunta de ESTE examen, dejándola en el banco.
     *
     * Es una operación distinta de archivarla y por eso tiene intent propio: el
     * borrado antiguo recibía `examId` y lo ignoraba, archivando la pregunta en
     * todos los exámenes a la vez. Un solo intent para las dos cosas es
     * exactamente cómo se llegó a aquello.
     */
    data class RequestUnlinkQuestion(val questionId: String) : ExamsIntent

    /**
     * Archivar la pregunta en el banco: desaparece de TODOS los exámenes.
     *
     * Va con aviso obligatorio en el diálogo, y el aviso no puede decir de
     * cuántos exámenes desaparece: no existe la consulta inversa
     * pregunta → exámenes.
     */
    data class RequestArchiveQuestion(val questionId: String) : ExamsIntent

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

    /**
     * Deshacer la ELECCIÓN local de portada, no la portada guardada.
     *
     * Sólo tiene sentido cuando hay una imagen en el servidor y otra elegida
     * encima: sin esto, la única forma de arrepentirse de haber pinchado el
     * fichero equivocado era subirlo y luego quitarlo, que sí toca el servidor.
     * No confundir con `RequestClearExamCover`, que borra la portada de verdad y
     * por eso pasa por un diálogo.
     */
    data object CancelExamCoverSelection : ExamsIntent
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
