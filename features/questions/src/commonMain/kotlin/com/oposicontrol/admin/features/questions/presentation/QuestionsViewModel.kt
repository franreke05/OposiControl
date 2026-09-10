package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionExamLinkRequest
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel de Preguntas (banco global, JVM/Desktop).
 *
 * Depende de [QuestionsRepository] (propio, ver diseño aprobado) para todo lo
 * específico de preguntas, y de [ContentRepository] solo para el catálogo de
 * exámenes (`observeExams`) — lectura de una capacidad que ya existía y que
 * usan otras pantallas; no añade nada a ese contrato.
 */
class QuestionsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val questionsRepository: QuestionsRepository,
    private val contentRepository: ContentRepository,
) : MviViewModel<QuestionsState, QuestionsIntent>(
    initialState = QuestionsState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            contentRepository.observeExams().collectLatest { exams ->
                updateState { it.copy(exams = if (exams.isEmpty()) UiAsync.Empty else UiAsync.Success(exams)) }
            }
        }
        scope.launch { loadQuestions() }
    }

    override suspend fun handleIntent(intent: QuestionsIntent) {
        when (intent) {
            QuestionsIntent.Refresh -> loadQuestions()

            is QuestionsIntent.Search -> updateState { it.copy(searchQuery = intent.query, pageIndex = 0) }
            is QuestionsIntent.SetTypeFilter -> updateState { it.copy(typeFilter = intent.type, pageIndex = 0) }
            is QuestionsIntent.SetActiveFilter -> updateState { it.copy(activeFilter = intent.active, pageIndex = 0) }
            is QuestionsIntent.SetSort -> updateState { it.copy(sort = intent.sort, pageIndex = 0) }
            is QuestionsIntent.SetPageIndex -> updateState { it.copy(pageIndex = intent.index) }
            is QuestionsIntent.SetPageSize -> updateState { it.copy(pageSize = intent.size, pageIndex = 0) }
            is QuestionsIntent.SetExamFilter -> {
                updateState { it.copy(examFilter = intent.examId, pageIndex = 0) }
                loadQuestions()
            }

            is QuestionsIntent.SelectQuestion -> selectQuestion(intent.id)
            QuestionsIntent.StartCreate -> startCreate()
            is QuestionsIntent.UpdateCreateExamId -> updateState { it.copy(draftCreateExamId = intent.examId) }

            is QuestionsIntent.UpdateStatement -> updateState { it.copy(draftStatement = intent.value, dirty = true) }
            is QuestionsIntent.UpdateType -> updateState { it.copy(draftType = intent.type, dirty = true) }
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
                it.copy(showAssociateDialog = true, associateExamId = "", associatePosition = "1")
            }
            is QuestionsIntent.UpdateAssociateExamId -> updateState { it.copy(associateExamId = intent.examId) }
            is QuestionsIntent.UpdateAssociatePosition -> updateState { it.copy(associatePosition = intent.value) }
            QuestionsIntent.CancelAssociateDialog -> updateState { it.copy(showAssociateDialog = false) }
            QuestionsIntent.ConfirmAssociateExam -> associateExam()

            QuestionsIntent.RequestCancel -> requestCancel()
            QuestionsIntent.ConfirmCancel -> confirmCancel()
            QuestionsIntent.DismissCancelConfirm -> updateState { it.copy(showCancelConfirm = false) }

            QuestionsIntent.RequestDelete -> updateState { it.copy(showDeleteConfirm = true) }
            QuestionsIntent.ConfirmDelete -> confirmDelete()
            QuestionsIntent.DismissDeleteConfirm -> updateState { it.copy(showDeleteConfirm = false) }

            QuestionsIntent.Save -> save()
        }
    }

    private suspend fun loadQuestions() {
        val examFilter = currentState().examFilter
        updateState { it.copy(questions = UiAsync.Loading) }
        val result = if (examFilter.isNullOrBlank()) {
            questionsRepository.listQuestions()
        } else {
            questionsRepository.listQuestionsForExam(examFilter)
        }
        when (result) {
            is AppResult.Failure -> updateState { it.copy(questions = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(questions = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value))
            }
        }
    }

    private fun startCreate() {
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
                draftDifficulty = "media",
                draftCreateExamId = "",
                optionsDirty = true, // alta siempre manda opciones
                dirty = false,
                examLinks = emptyList(),
                saveState = UiAsync.Idle,
            )
        }
    }

    private suspend fun selectQuestion(id: String) {
        updateState {
            it.copy(
                selectedQuestionId = id,
                isCreating = false,
                detail = UiAsync.Loading,
                dirty = false,
                optionsDirty = false,
                saveState = UiAsync.Idle,
                examLinks = knownExamLinksFor(it, id),
            )
        }
        when (val result = questionsRepository.getQuestionDetail(id)) {
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

    /**
     * Única fuente honesta de asociaciones sin fan-out: si la lista activa
     * ahora mismo viene de un examen concreto (`examFilter`), esa fila trae
     * `examId`/`position` reales — se resuelve el código/título desde el
     * catálogo ya cargado. Si no, no se inventa nada: lista vacía.
     */
    private fun knownExamLinksFor(state: QuestionsState, questionId: String): List<QuestionExamLink> {
        val examId = state.examFilter ?: return emptyList()
        val row = (state.questions as? UiAsync.Success)?.value?.firstOrNull { it.id == questionId } ?: return emptyList()
        val position = row.position ?: return emptyList()
        val exam = (state.exams as? UiAsync.Success)?.value?.firstOrNull { it.id == examId } ?: return emptyList()
        return listOf(QuestionExamLink(examId = exam.id, examCode = exam.code, examTitle = exam.title, position = position))
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

    private suspend fun associateExam() {
        val state = currentState()
        val questionId = state.selectedQuestionId ?: return
        val examId = state.associateExamId
        val position = state.associatePosition.toIntOrNull()
        if (examId.isBlank() || position == null || position <= 0) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Elige un examen y una posición válida."))) }
        }
        if (state.examLinks.any { it.examId == examId }) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Esta pregunta ya está asociada a ese examen."))) }
        }
        updateState { it.copy(saveState = UiAsync.Loading) }
        val draft = state.toDraft()
        when (val result = questionsRepository.updateQuestion(questionId, draft, optionsChanged = false, linkToExam = QuestionExamLinkRequest(examId, position))) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val exam = (state.exams as? UiAsync.Success)?.value?.firstOrNull { it.id == examId }
                updateState {
                    it.copy(
                        saveState = UiAsync.Success(Unit),
                        showAssociateDialog = false,
                        examLinks = it.examLinks + QuestionExamLink(
                            examId = examId,
                            examCode = exam?.code.orEmpty(),
                            examTitle = exam?.title ?: examId,
                            position = position,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun save() {
        val state = currentState()
        if (!state.isDraftValid()) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Revisa enunciado, opciones y respuesta correcta antes de guardar."))) }
        }
        updateState { it.copy(saveState = UiAsync.Loading) }
        val draft = state.toDraft()
        val result = if (state.isCreating) {
            questionsRepository.createQuestion(draft, examId = state.draftCreateExamId, position = 1)
        } else {
            val id = state.selectedQuestionId ?: return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Selecciona una pregunta."))) }
            questionsRepository.updateQuestion(id, draft, optionsChanged = state.optionsDirty)
        }
        when (result) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val detail = result.value
                updateState {
                    it.copy(
                        saveState = UiAsync.Success(Unit),
                        isCreating = false,
                        selectedQuestionId = detail.id,
                        detail = UiAsync.Success(detail),
                        dirty = false,
                        optionsDirty = false,
                    )
                }
                loadQuestions()
            }
        }
    }
}

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
    if (isCreating && draftCreateExamId.isBlank()) return false
    if (optionsDirty || isCreating) {
        if (draftOptions.size != 4) return false
        if (draftOptions.any { it.content.isBlank() }) return false
        if (draftOptions.count { it.isCorrect } != 1) return false
    }
    return true
}
