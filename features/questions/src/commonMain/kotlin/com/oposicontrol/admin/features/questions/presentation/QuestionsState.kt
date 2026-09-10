package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionType

enum class QuestionsSort { MOST_RECENT, BY_CODE }

/**
 * Estado de la pantalla Preguntas (banco global, JVM/Desktop).
 *
 * `questions` es siempre lo que devolvió el backend para la fuente activa:
 * banco global (`examFilter == null`) o una lista por examen
 * (`examFilter != null`, vía `listQuestionsForExam`) — nunca un filtrado
 * simulado sobre datos que no existen. Búsqueda/tipo/estado/orden/paginación
 * sí son client-side sobre lo ya cargado (ver [visibleQuestions]).
 */
data class QuestionsState(
    val questions: UiAsync<List<QuestionListItem>> = UiAsync.Loading,
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,

    val searchQuery: String = "",
    val typeFilter: QuestionType? = null,
    val examFilter: String? = null,
    val activeFilter: Boolean? = null,
    val sort: QuestionsSort = QuestionsSort.MOST_RECENT,
    val pageIndex: Int = 0,
    val pageSize: Int = 10,

    val selectedQuestionId: String? = null,
    val detail: UiAsync<QuestionDetail> = UiAsync.Idle,
    val isCreating: Boolean = false,

    val draftStatement: String = "",
    val draftType: QuestionType = QuestionType.RAZONADA,
    val draftOptions: List<QuestionOption> = defaultDraftOptions(),
    val draftExplanation: String = "",
    val draftSource: String = "",
    val draftActive: Boolean = true,
    val draftDifficulty: String = "media",
    /** Solo para alta: examen inicial obligatorio (especialidad_id lo exige el backend). */
    val draftCreateExamId: String = "",
    val optionsDirty: Boolean = false,
    val dirty: Boolean = false,

    /**
     * Asociaciones a examen CONOCIDAS para la pregunta seleccionada — nunca
     * el histórico completo: no existe backend para "en qué exámenes está
     * esta pregunta" (ver [QuestionsRepository][com.oposicontrol.admin.domain.repository.QuestionsRepository]).
     * Se siembra desde el contexto (llegaste filtrando por examen) y se
     * amplía tras un "Asociar a examen" real en esta sesión.
     */
    val examLinks: List<QuestionExamLink> = emptyList(),

    val showAssociateDialog: Boolean = false,
    val associateExamId: String = "",
    val associatePosition: String = "1",

    val showCancelConfirm: Boolean = false,
    val showDeleteConfirm: Boolean = false,

    val saveState: UiAsync<Unit> = UiAsync.Idle,
    val archiveState: UiAsync<Unit> = UiAsync.Idle,
)

fun defaultDraftOptions(): List<QuestionOption> = listOf("A", "B", "C", "D").mapIndexed { index, label ->
    QuestionOption(id = "", questionId = "", label = label, content = "", isCorrect = index == 0, sortOrder = index)
}

/** Filtro + orden + paginación, client-side, sobre lo que de verdad se cargó. */
fun QuestionsState.filteredQuestions(): List<QuestionListItem> {
    val query = searchQuery.trim()
    val source = (questions as? UiAsync.Success)?.value.orEmpty()
    val filtered = source.filter { item ->
        (query.isBlank() || item.code.contains(query, ignoreCase = true) || item.statement.contains(query, ignoreCase = true)) &&
            (typeFilter == null || item.type == typeFilter) &&
            (activeFilter == null || item.active == activeFilter)
    }
    return when (sort) {
        QuestionsSort.MOST_RECENT -> filtered // el backend ya devuelve creado_en desc; no hay timestamp en la fila para reordenar en cliente
        QuestionsSort.BY_CODE -> filtered.sortedBy { it.code }
    }
}

fun QuestionsState.totalPages(): Int {
    val count = filteredQuestions().size
    if (count == 0) return 1
    return (count + pageSize - 1) / pageSize
}

fun QuestionsState.pagedQuestions(): List<QuestionListItem> {
    val filtered = filteredQuestions()
    val from = (pageIndex * pageSize).coerceIn(0, filtered.size)
    val to = (from + pageSize).coerceIn(from, filtered.size)
    return filtered.subList(from, to)
}
