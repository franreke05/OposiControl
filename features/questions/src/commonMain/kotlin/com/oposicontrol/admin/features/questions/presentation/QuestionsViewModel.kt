package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel de Preguntas (banco global, JVM/Desktop).
 *
 * Depende de [QuestionsRepository] (propio, ver diseño aprobado) para todo lo
 * específico de preguntas, y de [ContentRepository] sólo para los catálogos de
 * exámenes — capacidad que ya existía y que usan otras
 * pantallas; no añade nada a ese contrato.
 *
 * **Los catálogos los pide esta pantalla (KQ0-5, QN-10).** Antes sólo observaba
 * `observeExams()` —sin ráfagas— y nunca los refrescaba: si no se había abierto
 * Exámenes antes, el selector de examen salía vacío y una pregunta de ráfaga no se
 * podía vincular. Un fallo al pedirlos es un `Error`, nunca una lista vacía.
 */
class QuestionsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val questionsRepository: QuestionsRepository,
    private val contentRepository: ContentRepository,
) : MviViewModel<QuestionsState, QuestionsIntent>(
    initialState = QuestionsState(),
    dispatcherProvider = dispatcherProvider,
) {
    /** La búsqueda que espera a que se deje de teclear ([QUESTION_SEARCH_DEBOUNCE_MILLIS]). */
    private var searchJob: Job? = null

    /**
     * Número de la última carga pedida. Una respuesta de una carga anterior —la
     * búsqueda de hace dos teclas— no puede pisar la de la consulta vigente.
     */
    private var loadGeneration = 0

    /**
     * Número de la última selección. Los vínculos se cargan en PARALELO al detalle
     * (KQ3-2), así que los de una pregunta que ya no está seleccionada no pueden
     * aterrizar sobre la siguiente.
     */
    private var selectionGeneration = 0

    init {
        scope.launch {
            contentRepository.observeAllExams().collectLatest { exams ->
                updateState { it.copy(exams = seededCatalog(exams, it.exams)) }
            }
        }
        scope.launch { refreshCatalogs() }
        scope.launch { loadQuestions() }
    }

    override suspend fun handleIntent(intent: QuestionsIntent) {
        when (intent) {
            QuestionsIntent.Refresh -> {
                if (currentState().exams is UiAsync.Error) {
                    refreshCatalogs()
                }
                reloadQuestions()
            }

            // La búsqueda espera a que se deje de escribir: cada tecla sería una
            // consulta al servidor. El resto de filtros recarga al momento, y todos
            // vuelven a la primera página: la página 7 de otra consulta no existe.
            is QuestionsIntent.Search -> {
                updateState { it.copy(searchQuery = intent.query, pageIndex = 0) }
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(QUESTION_SEARCH_DEBOUNCE_MILLIS)
                    loadQuestions()
                }
            }
            is QuestionsIntent.SetTypeFilter -> {
                updateState { it.copy(typeFilter = intent.type, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetActiveFilter -> {
                updateState { it.copy(activeFilter = intent.active, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetSort -> {
                updateState { it.copy(sort = intent.sort, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetPageIndex -> {
                updateState { it.copy(pageIndex = intent.index.coerceAtLeast(0)) }
                reloadQuestions()
            }
            is QuestionsIntent.SetPageSize -> {
                updateState { it.copy(pageSize = intent.size, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetExamFilter -> {
                updateState { it.copy(examFilter = intent.examId, pageIndex = 0) }
                reloadQuestions()
            }

            is QuestionsIntent.SelectQuestion -> selectQuestion(intent.id)
            QuestionsIntent.StartCreate -> startCreate()
            is QuestionsIntent.UpdateCreateExamId -> updateState { it.copy(draftCreateExamId = intent.examId) }

            is QuestionsIntent.UpdateStatement -> updateState { it.copy(draftStatement = intent.value, dirty = true) }
            // Cambiar el tipo de un alta suelta el examen inicial si ya no lo admite:
            // la base lo rechazaría (`QUESTION_EXAM_TYPE_MISMATCH`), y el selector ya
            // no lo ofrece, así que quedaría elegido algo invisible.
            //
            // Y en una pregunta guardada con vínculos o con recursos el tipo está
            // BLOQUEADO (KQ3-3): la base lo rechaza (`QUESTION_TYPE_LOCKED_BY_*`) y
            // la tarjeta no es seleccionable; si el intento llega igual, se ignora
            // en vez de dejar un borrador que sólo puede fallar al guardar.
            is QuestionsIntent.UpdateType -> updateState { state ->
                if (!state.isCreating && questionTypeLockReason(state) != null && intent.type != state.draftType) return@updateState state
                val createExam = (state.exams as? UiAsync.Success)?.value?.firstOrNull { it.id == state.draftCreateExamId }
                state.copy(
                    draftType = intent.type,
                    dirty = true,
                    draftCreateExamId = if (createExam != null && !examAcceptsQuestionType(createExam, intent.type)) {
                        ""
                    } else {
                        state.draftCreateExamId
                    },
                )
            }
            is QuestionsIntent.UpdateOptionContent -> updateState {
                it.copy(
                    draftOptions = it.draftOptions.map { option ->
                        if (option.label == intent.label) option.copy(content = intent.value) else option
                    },
                    dirty = true,
                    optionsDirty = true,
                )
            }
            is QuestionsIntent.SetCorrectOption -> updateState {
                it.copy(
                    draftOptions = it.draftOptions.map { option -> option.copy(isCorrect = option.label == intent.label) },
                    dirty = true,
                    optionsDirty = true,
                )
            }
            is QuestionsIntent.UpdateExplanation -> updateState { it.copy(draftExplanation = intent.value, dirty = true) }
            is QuestionsIntent.UpdateSource -> updateState { it.copy(draftSource = intent.value, dirty = true) }
            is QuestionsIntent.SetActive -> updateState { it.copy(draftActive = intent.active, dirty = true) }

            QuestionsIntent.RequestAssociateExam -> updateState {
                it.copy(showAssociateDialog = true, associateExamId = "", associatePosition = "", saveState = UiAsync.Idle)
            }
            is QuestionsIntent.UpdateAssociateExamId -> updateState { it.copy(associateExamId = intent.examId) }
            is QuestionsIntent.UpdateAssociatePosition -> updateState { it.copy(associatePosition = intent.value) }
            QuestionsIntent.CancelAssociateDialog -> updateState { it.copy(showAssociateDialog = false) }
            QuestionsIntent.ConfirmAssociateExam -> associateExam()

            is QuestionsIntent.RequestUnlinkExam -> updateState { it.copy(unlinkExamId = intent.examId, saveState = UiAsync.Idle) }
            QuestionsIntent.DismissUnlinkConfirm -> updateState { it.copy(unlinkExamId = null) }
            QuestionsIntent.ConfirmUnlinkExam -> unlinkExam()

            QuestionsIntent.RequestCancel -> requestCancel()
            QuestionsIntent.ConfirmCancel -> confirmCancel()
            QuestionsIntent.DismissCancelConfirm -> updateState { it.copy(showCancelConfirm = false) }

            QuestionsIntent.RequestDelete -> updateState { it.copy(showDeleteConfirm = true) }
            QuestionsIntent.ConfirmDelete -> confirmDelete()
            QuestionsIntent.DismissDeleteConfirm -> updateState { it.copy(showDeleteConfirm = false) }

            QuestionsIntent.Save -> save()
        }
    }

    /** Una recarga explícita manda sobre una búsqueda que todavía esperaba. */
    private suspend fun reloadQuestions() {
        searchJob?.cancel()
        loadQuestions()
    }

    /**
     * Pide al servidor la página que describen los filtros (KQ0-4).
     *
     * - Cero resultados SIN filtros es un banco vacío (`Empty`); con filtros es una
     *   página vacía (`Success`), que la pantalla dice como «nada coincide».
     * - Una página más allá del final —se archivó lo último de la última página— se
     *   corrige a la última que existe en vez de enseñar un vacío falso.
     */
    private suspend fun loadQuestions() {
        val generation = ++loadGeneration
        val state = currentState()
        val query = state.toListQuery()
        updateState { it.copy(questions = UiAsync.Loading) }
        val result = questionsRepository.listQuestions(query)
        if (generation != loadGeneration) return
        when (result) {
            is AppResult.Failure -> updateState { it.copy(questions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val page = result.value
                if (page.items.isEmpty() && page.total > 0 && query.offset > 0) {
                    val lastPage = (page.total - 1) / query.limit
                    updateState { it.copy(pageIndex = lastPage) }
                    return loadQuestions()
                }
                updateState {
                    it.copy(
                        questions = if (page.total == 0 && !state.hasBankFilters()) UiAsync.Empty else UiAsync.Success(page),
                    )
                }
            }
        }
    }

    /**
     * Pide el catálogo de exámenes que usan los selectores (KQ0-5). La especialidad de
     * cada examen ya viene resuelta en él: la pregunta no tiene especialidad (M8).
     */
    private suspend fun refreshCatalogs() {
        when (val exams = contentRepository.refreshAllExams()) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(exams.error)) }
            is AppResult.Success -> updateState { it.copy(exams = loadedCatalog(exams.value)) }
        }
    }

    private fun startCreate() {
        selectionGeneration++
        updateState {
            it.copy(
                selectedQuestionId = null,
                isCreating = true,
                detail = UiAsync.Idle,
                draftStatement = "",
                draftType = QuestionType.RAZONADA,
                draftOptions = defaultDraftOptions(),
                draftExplanation = "",
                draftSource = "",
                draftActive = true,
                // Sin definir (NULL): la pantalla no edita la dificultad y no la inventa (KQ0-2).
                draftDifficulty = "",
                // UNA vez por alta (KQ2-4): sobrevive a cada reintento hasta que la
                // pregunta exista o el alta se cancele.
                draftQuestionId = randomUuid(),
                draftCreateExamId = "",
                optionsDirty = true, // alta siempre manda opciones
                dirty = false,
                examLinks = UiAsync.Idle,
                saveState = UiAsync.Idle,
            )
        }
    }

    /**
     * Detalle y vínculos en PARALELO (KQ3-2): son dos consultas independientes y el
     * panel de relaciones no tiene por qué esperar al editor. [selectionGeneration]
     * descarta lo que llegue de una selección anterior.
     */
    private suspend fun selectQuestion(id: String) {
        val generation = ++selectionGeneration
        updateState {
            it.copy(
                selectedQuestionId = id,
                isCreating = false,
                detail = UiAsync.Loading,
                dirty = false,
                optionsDirty = false,
                saveState = UiAsync.Idle,
                examLinks = UiAsync.Loading,
                unlinkExamId = null,
            )
        }
        scope.launch { loadExamLinks(id, generation) }
        val result = questionsRepository.getQuestionDetail(id)
        if (generation != selectionGeneration) return
        when (result) {
            is AppResult.Failure -> updateState { it.copy(detail = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val detail = result.value
                updateState {
                    it.copy(
                        detail = UiAsync.Success(detail),
                        draftStatement = detail.statement,
                        draftType = detail.type ?: QuestionType.RAZONADA,
                        draftOptions = if (detail.options.size == 4) {
                            detail.options.sortedBy { option -> option.sortOrder }
                        } else {
                            defaultDraftOptions()
                        },
                        draftExplanation = detail.explanation,
                        draftSource = detail.source,
                        draftActive = detail.active,
                        draftDifficulty = detail.difficulty,
                        dirty = false,
                        optionsDirty = false,
                    )
                }
            }
        }
    }

    /** Los vínculos vienen del servidor, nunca de la sesión: vacío es «en ningún examen», y un fallo es un fallo. */
    private suspend fun loadExamLinks(questionId: String, generation: Int) {
        val result = questionsRepository.listQuestionExams(questionId)
        if (generation != selectionGeneration) return
        updateState {
            it.copy(
                examLinks = when (result) {
                    is AppResult.Failure -> UiAsync.Error(result.error)
                    is AppResult.Success -> UiAsync.Success(result.value)
                },
            )
        }
    }

    private fun requestCancel() {
        val state = currentState()
        if (state.dirty) {
            updateState { it.copy(showCancelConfirm = true) }
        } else {
            confirmCancel()
        }
    }

    private fun confirmCancel() {
        val state = currentState()
        val detail = (state.detail as? UiAsync.Success)?.value
        updateState {
            if (detail != null) {
                it.copy(
                    isCreating = false,
                    draftStatement = detail.statement,
                    draftType = detail.type ?: QuestionType.RAZONADA,
                    draftOptions = if (detail.options.size == 4) detail.options.sortedBy { o -> o.sortOrder } else defaultDraftOptions(),
                    draftExplanation = detail.explanation,
                    draftSource = detail.source,
                    draftActive = detail.active,
                    draftDifficulty = detail.difficulty,
                    dirty = false,
                    optionsDirty = false,
                    showCancelConfirm = false,
                    saveState = UiAsync.Idle,
                )
            } else {
                it.copy(
                    selectedQuestionId = null,
                    isCreating = false,
                    detail = UiAsync.Idle,
                    dirty = false,
                    optionsDirty = false,
                    showCancelConfirm = false,
                    saveState = UiAsync.Idle,
                )
            }
        }
    }

    private suspend fun confirmDelete() {
        val id = currentState().selectedQuestionId ?: return updateState { it.copy(showDeleteConfirm = false) }
        updateState { it.copy(archiveState = UiAsync.Loading) }
        when (val result = questionsRepository.archiveQuestion(id)) {
            is AppResult.Failure -> updateState { it.copy(archiveState = UiAsync.Error(result.error), showDeleteConfirm = false) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        archiveState = UiAsync.Success(Unit),
                        showDeleteConfirm = false,
                        selectedQuestionId = null,
                        detail = UiAsync.Idle,
                    )
                }
                loadQuestions()
            }
        }
    }

    /**
     * Vincular es una llamada PROPIA (`POST /preguntas/{id}/examenes`, KQ3-2) y no
     * un PATCH con el borrador entero: antes «Vincular» guardaba en silencio las
     * ediciones sin confirmar, o fallaba por ellas. La lista que vuelve del servidor
     * SUSTITUYE a la conocida: es la real, con la posición que la base asignó.
     */
    private suspend fun associateExam() {
        val state = currentState()
        val questionId = state.selectedQuestionId ?: return
        val examId = state.associateExamId
        val positionText = state.associatePosition.trim()
        val position = positionText.toIntOrNull()
        if (examId.isBlank() || (positionText.isNotEmpty() && (position == null || position <= 0))) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Elige un examen y, si quieres fijarla, una posición de 1 en adelante."))) }
        }
        if (state.knownExamLinks().any { it.examId == examId }) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Esta pregunta ya está en ese examen."))) }
        }
        updateState { it.copy(saveState = UiAsync.Loading) }
        when (val result = questionsRepository.linkQuestionToExam(questionId, examId, position)) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        saveState = UiAsync.Success(Unit),
                        showAssociateDialog = false,
                        examLinks = UiAsync.Success(result.value),
                    )
                }
                // La fila del banco puede llevar la posición del examen filtrado.
                if (state.examFilter != null) loadQuestions()
            }
        }
    }

    /** Quitar de un examen deja la pregunta intacta en el banco; la lista vuelve releída del servidor. */
    private suspend fun unlinkExam() {
        val state = currentState()
        val questionId = state.selectedQuestionId ?: return updateState { it.copy(unlinkExamId = null) }
        val examId = state.unlinkExamId ?: return
        updateState { it.copy(saveState = UiAsync.Loading) }
        when (val result = questionsRepository.unlinkQuestionFromExam(questionId, examId)) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error), unlinkExamId = null) }
            is AppResult.Success -> {
                updateState {
                    it.copy(saveState = UiAsync.Success(Unit), unlinkExamId = null, examLinks = UiAsync.Success(result.value))
                }
                if (state.examFilter != null) loadQuestions()
            }
        }
    }

    /**
     * Guardar. En un alta el id viaja desde [QuestionsState.draftQuestionId] y NO se
     * regenera si falla: un timeout con la pregunta ya creada en el servidor tiene
     * que poder reintentarse con el MISMO id, que es lo que impide duplicarla.
     */
    private suspend fun save() {
        val state = currentState()
        if (!state.isDraftValid()) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Revisa enunciado, opciones y respuesta correcta antes de guardar."))) }
        }
        updateState { it.copy(saveState = UiAsync.Loading) }
        val draft = state.toDraft()
        val result = if (state.isCreating) {
            questionsRepository.createQuestion(
                questionId = state.draftQuestionId,
                draft = draft,
                examId = state.draftCreateExamId.takeIf { it.isNotBlank() },
            )
        } else {
            val id = state.selectedQuestionId ?: return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Selecciona una pregunta."))) }
            questionsRepository.updateQuestion(id, draft, optionsChanged = state.optionsDirty)
        }
        when (result) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val detail = result.value
                val wasCreating = state.isCreating
                updateState {
                    it.copy(
                        saveState = UiAsync.Success(Unit),
                        isCreating = false,
                        selectedQuestionId = detail.id,
                        detail = UiAsync.Success(detail),
                        dirty = false,
                        optionsDirty = false,
                        draftQuestionId = "",
                    )
                }
                if (wasCreating) {
                    // La pregunta ya existe: sus vínculos (el examen inicial, si lo hubo) son del servidor.
                    val generation = ++selectionGeneration
                    updateState { it.copy(examLinks = UiAsync.Loading) }
                    scope.launch { loadExamLinks(detail.id, generation) }
                }
                loadQuestions()
            }
        }
    }
}

/**
 * La emisión SEMILLA de un repositorio no resuelve un catálogo: sus `StateFlow`
 * arrancan en `emptyList()`, que significa «todavía no se ha preguntado», no «no
 * hay ninguno». Una lista vacía sólo pasa a `Empty` si ya había datos; el vacío
 * confirmado lo decide el refresco ([loadedCatalog]).
 */
private fun <T> seededCatalog(values: List<T>, current: UiAsync<List<T>>): UiAsync<List<T>> = when {
    values.isNotEmpty() -> UiAsync.Success(values)
    current is UiAsync.Loading || current is UiAsync.Idle || current is UiAsync.Error -> current
    else -> UiAsync.Empty
}

/** Una lista que viene de hablar con el servidor: vacía es `Empty`, no `Loading`. */
private fun <T> loadedCatalog(values: List<T>): UiAsync<List<T>> =
    if (values.isEmpty()) UiAsync.Empty else UiAsync.Success(values)

private fun QuestionsState.toDraft() = QuestionDraft(
    statement = draftStatement.trim(),
    type = draftType,
    options = draftOptions,
    explanation = draftExplanation.trim(),
    source = draftSource.trim(),
    active = draftActive,
    difficulty = draftDifficulty,
)

/**
 * Validación cliente — el backend sigue siendo la autoridad final (P0 de
 * opciones/creación puede rechazarlo igual aunque esto pase).
 */
fun QuestionsState.isDraftValid(): Boolean {
    if (draftStatement.isBlank()) return false
    // KQ2-4: el examen inicial es opcional; la pregunta no tiene especialidad (M8).
    if (optionsDirty || isCreating) {
        if (draftOptions.size != 4) return false
        if (draftOptions.any { it.content.isBlank() }) return false
        if (draftOptions.count { it.isCorrect } != 1) return false
    }
    return true
}
