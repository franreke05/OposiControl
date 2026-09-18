package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionListSort
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionPage
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter

enum class QuestionsSort { MOST_RECENT, BY_CODE }

/**
 * Estado de la pantalla Preguntas (banco global, JVM/Desktop).
 *
 * `questions` es SIEMPRE una página que devolvió el servidor para la consulta que
 * describen los filtros ([toListQuery]): búsqueda, tipo, especialidad, estado,
 * examen, orden y paginación se resuelven allí (KQ0-4). Antes se descargaba el
 * banco entero y se filtraba lo cargado, y «Inactivas» filtraba un conjunto del
 * que las inactivas ya se habían ido.
 */
data class QuestionsState(
    val questions: UiAsync<QuestionPage> = UiAsync.Loading,
    /** TODOS los exámenes, ráfagas incluidas (KQ0-5): una pregunta de ráfaga también se vincula. */
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,
    val specialties: UiAsync<List<Specialty>> = UiAsync.Loading,

    val searchQuery: String = "",
    val typeFilter: QuestionType? = null,
    val specialtyFilter: String? = null,
    val examFilter: String? = null,
    /** Activas por defecto, como el servidor; «Inactivas» y «Todas» se piden de verdad. */
    val activeFilter: QuestionActiveFilter = QuestionActiveFilter.ACTIVE,
    val sort: QuestionsSort = QuestionsSort.MOST_RECENT,
    val pageIndex: Int = 0,
    val pageSize: Int = QUESTION_PAGE_SIZE_DEFAULT,

    val selectedQuestionId: String? = null,
    val detail: UiAsync<QuestionDetail> = UiAsync.Idle,
    val isCreating: Boolean = false,

    val draftStatement: String = "",
    val draftType: QuestionType = QuestionType.RAZONADA,
    val draftOptions: List<QuestionOption> = defaultDraftOptions(),
    val draftExplanation: String = "",
    val draftSource: String = "",
    val draftActive: Boolean = true,
    /** `""` = sin definir (columna NULL). Nunca un `"media"` inventado (KQ0-2). */
    val draftDifficulty: String = "",
    /**
     * Sólo para alta (KQ2-4): el id de la pregunta que se va a crear, generado UNA
     * vez al abrir el alta y conservado en cada reintento. Es la clave de
     * idempotencia de `admin_create_question`: si el primer intento llegó pero su
     * respuesta se perdió, el segundo no crea otra pregunta.
     */
    val draftQuestionId: String = "",
    /** Sólo para alta: la especialidad se elige directamente; ya no la aporta el examen. */
    val draftSpecialtyId: String = "",
    /** Sólo para alta: examen inicial OPCIONAL, vinculado en la misma transacción (KQ3-4). */
    val draftCreateExamId: String = "",
    val optionsDirty: Boolean = false,
    val dirty: Boolean = false,

    /**
     * Los exámenes REALES en los que está la pregunta seleccionada
     * (`GET /preguntas/{id}/examenes`, KQ3-2), cargados en paralelo al detalle.
     *
     * Antes se INFERÍAN de la sesión —sólo aparecía un vínculo si habías llegado
     * filtrando por ese examen— y una lista vacía no significaba nada. Ahora vacía
     * significa «no está en ningún examen», y `Error` significa que no se pudo saber.
     */
    val examLinks: UiAsync<List<QuestionExamLink>> = UiAsync.Idle,

    val showAssociateDialog: Boolean = false,
    val associateExamId: String = "",
    /** Vacía = al final del examen (KQ3-2): la base la asigna. */
    val associatePosition: String = "",
    /** El examen del que se está a punto de quitar la pregunta (confirmación pendiente). */
    val unlinkExamId: String? = null,

    val showCancelConfirm: Boolean = false,
    val showDeleteConfirm: Boolean = false,

    val saveState: UiAsync<Unit> = UiAsync.Idle,
    val archiveState: UiAsync<Unit> = UiAsync.Idle,
)

fun defaultDraftOptions(): List<QuestionOption> = listOf("A", "B", "C", "D").mapIndexed { index, label ->
    QuestionOption(id = "", questionId = "", label = label, content = "", isCorrect = index == 0, sortOrder = index)
}

/** Tamaño de página por defecto del banco (KQ0-4); el servidor admite hasta 100. */
const val QUESTION_PAGE_SIZE_DEFAULT: Int = 25

/** Tamaños de página que se ofrecen. Ninguno supera el máximo del servidor. */
val QUESTION_PAGE_SIZES: List<Int> = listOf(25, 50, 100)

/** Espera tras la última tecla antes de buscar en el servidor (KQ0-4). */
const val QUESTION_SEARCH_DEBOUNCE_MILLIS: Long = 300

/**
 * La consulta que describen los filtros, tal cual la pide el repositorio.
 *
 * Con examen elegido el orden es su posición y [QuestionsState.sort] no viaja al
 * servidor como criterio útil: la pantalla oculta el selector en ese modo.
 */
fun QuestionsState.toListQuery(): QuestionListQuery = QuestionListQuery(
    search = searchQuery.trim(),
    type = typeFilter,
    specialtyId = specialtyFilter,
    active = activeFilter,
    examId = examFilter,
    sort = when (sort) {
        QuestionsSort.MOST_RECENT -> QuestionListSort.MOST_RECENT
        QuestionsSort.BY_CODE -> QuestionListSort.BY_CODE
    },
    offset = pageIndex * pageSize,
    limit = pageSize,
)

/**
 * Si hay algún filtro puesto: con ellos, cero resultados es «nada coincide» y no
 * «el banco está vacío» — son dos cosas distintas y se dicen distinto.
 */
fun QuestionsState.hasBankFilters(): Boolean =
    searchQuery.isNotBlank() ||
        typeFilter != null ||
        specialtyFilter != null ||
        examFilter != null ||
        activeFilter != QuestionActiveFilter.ACTIVE

/** Las filas de la página cargada; vacío mientras carga o si falló. */
fun QuestionsState.visibleQuestions(): List<QuestionListItem> =
    (questions as? UiAsync.Success)?.value?.items.orEmpty()

/** Los vínculos ya cargados; vacío mientras cargan, si fallaron o en un alta. */
fun QuestionsState.knownExamLinks(): List<QuestionExamLink> =
    (examLinks as? UiAsync.Success)?.value.orEmpty()

/** El total de la CONSULTA, no de la página: es lo que dice el recuento. */
fun QuestionsState.totalQuestions(): Int =
    (questions as? UiAsync.Success)?.value?.total ?: 0

/** Páginas del total del servidor (`ceil(total / pageSize)`), y al menos una. */
fun QuestionsState.totalPages(): Int {
    val total = totalQuestions()
    if (total == 0) return 1
    return (total + pageSize - 1) / pageSize
}
