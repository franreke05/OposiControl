package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.OfficialQuestion
import com.oposicontrol.admin.domain.model.OfficialQuestionDraft
import com.oposicontrol.admin.domain.model.OfficialQuestionValidator
import com.oposicontrol.admin.domain.model.OfficialQuestions
import com.oposicontrol.admin.domain.repository.ControlV1Repository

data class OfficialQuestionsUiState(
    val examId: String? = null,
    val data: UiAsync<OfficialQuestions> = UiAsync.Idle,
    val selectedId: String? = null,
    val draft: OfficialQuestionDraft? = null,
    val errors: Map<String, String> = emptyMap(),
    val save: UiAsync<Unit> = UiAsync.Idle,
) {
    val current: OfficialQuestions? get() = (data as? UiAsync.Success)?.value
    val selected: OfficialQuestion? get() = current?.questions?.firstOrNull { it.questionId == selectedId }
}

sealed interface OfficialQuestionsIntent {
    data class Load(val examId: String) : OfficialQuestionsIntent
    data object Refresh : OfficialQuestionsIntent
    data class Select(val questionId: String?) : OfficialQuestionsIntent
    data class SetAnnulled(val on: Boolean) : OfficialQuestionsIntent
    data class SetReason(val text: String) : OfficialQuestionsIntent
    data class SetReserve(val on: Boolean) : OfficialQuestionsIntent
    data class SetOrder(val text: String) : OfficialQuestionsIntent
    data object Save : OfficialQuestionsIntent
}

/**
 * Marcas de cada pregunta de un examen oficial: anulada (con motivo) y reserva (con su orden).
 *
 * - Una pregunta de demostración no admite ninguna de las dos marcas (la interfaz las bloquea; la base también).
 * - Activar «reserva» propone el siguiente orden libre; quitarla limpia el orden. El servidor recoloca la posición para
 *   que toda reserva quede tras la última principal.
 * - Si el servidor aún no tiene la función de escritura, el error llega como mensaje legible y NO se cambia nada.
 */
class ExamOfficialQuestionsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<OfficialQuestionsUiState, OfficialQuestionsIntent>(OfficialQuestionsUiState(), dispatcherProvider) {
    private var writing = false

    override suspend fun handleIntent(intent: OfficialQuestionsIntent) {
        when (intent) {
            is OfficialQuestionsIntent.Load -> {
                if (currentState().examId == intent.examId && currentState().data is UiAsync.Success) return
                updateState { OfficialQuestionsUiState(examId = intent.examId, data = UiAsync.Loading) }
                load(intent.examId)
            }
            OfficialQuestionsIntent.Refresh -> currentState().examId?.let { load(it) }
            is OfficialQuestionsIntent.Select -> updateState { state ->
                val q = state.current?.questions?.firstOrNull { it.questionId == intent.questionId }
                if (q == null) state.copy(selectedId = null, draft = null, errors = emptyMap(), save = UiAsync.Idle)
                else state.copy(selectedId = q.questionId, draft = OfficialQuestionDraft.from(q), errors = emptyMap(), save = UiAsync.Idle)
            }
            is OfficialQuestionsIntent.SetAnnulled -> edit { it.copy(annulled = intent.on, reason = if (intent.on) it.reason else "") }
            is OfficialQuestionsIntent.SetReason -> edit { it.copy(reason = intent.text) }
            is OfficialQuestionsIntent.SetReserve -> {
                val next = currentState().current?.questions?.let { OfficialQuestionValidator.nextOrder(it) } ?: 1
                edit { it.copy(isReserve = intent.on, order = if (intent.on) it.order.ifBlank { next.toString() } else "") }
            }
            is OfficialQuestionsIntent.SetOrder -> edit { if (it.isReserve) it.copy(order = intent.text) else it }
            OfficialQuestionsIntent.Save -> save()
        }
    }

    private fun edit(transform: (OfficialQuestionDraft) -> OfficialQuestionDraft) {
        updateState { state ->
            val d = state.draft ?: return@updateState state
            state.copy(draft = transform(d), errors = emptyMap(), save = UiAsync.Idle)
        }
    }

    private suspend fun load(examId: String) {
        when (val r = repository.officialQuestions(examId)) {
            is AppResult.Failure -> updateState { it.copy(data = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState {
                it.copy(data = if (r.value.questions.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value))
            }
        }
    }

    private suspend fun save() {
        if (writing) return
        val state = currentState()
        val examId = state.examId ?: return
        val question = state.selected ?: return
        val draft = state.draft ?: return
        val all = state.current?.questions.orEmpty()
        val errors = OfficialQuestionValidator.check(draft, question, all)
        if (errors.isNotEmpty()) {
            updateState { it.copy(errors = errors) }
            return
        }
        writing = true
        try {
            updateState { it.copy(save = UiAsync.Loading) }
            when (val r = repository.saveOfficialQuestion(examId, question.questionId, draft)) {
                is AppResult.Failure -> updateState { it.copy(save = UiAsync.Error(r.error)) }
                is AppResult.Success -> updateState {
                    val refreshed = r.value.questions.firstOrNull { q -> q.questionId == question.questionId }
                    it.copy(
                        data = UiAsync.Success(r.value),
                        draft = refreshed?.let { q -> OfficialQuestionDraft.from(q) } ?: it.draft,
                        save = UiAsync.Success(Unit),
                    )
                }
            }
        } finally {
            writing = false
        }
    }
}
