package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AnnulledRuleOption
import com.oposicontrol.admin.domain.model.GradingAuditEntry
import com.oposicontrol.admin.domain.model.GradingRules
import com.oposicontrol.admin.domain.model.GradingRulesDraft
import com.oposicontrol.admin.domain.model.GradingRulesValidator
import com.oposicontrol.admin.domain.model.GradingSnapshot
import com.oposicontrol.admin.domain.model.GradingValidation
import com.oposicontrol.admin.domain.repository.ControlV1Repository

data class GradingRulesUiState(
    val examId: String? = null,
    val rules: UiAsync<GradingRules> = UiAsync.Idle,
    val draft: GradingRulesDraft? = null,
    val validation: GradingValidation.Invalid? = null,
    /** El examen está publicado: guardar pide confirmación explícita. */
    val confirming: Boolean = false,
    val save: UiAsync<Unit> = UiAsync.Idle,
    val audit: UiAsync<List<GradingAuditEntry>> = UiAsync.Idle,
) {
    val current: GradingRules? get() = (rules as? UiAsync.Success)?.value

    /** El borrador difiere de lo guardado (el motivo no cuenta: por sí solo no es un cambio de reglas). */
    val isDirty: Boolean
        get() {
            val rules = current ?: return false
            val d = draft ?: return false
            val saved = GradingRulesDraft.from(rules)
            return d.annulledRule != saved.annulledRule || d.rationalPenalty != saved.rationalPenalty ||
                d.numerator.trim() != saved.numerator || d.denominator.trim() != saved.denominator
        }
}

sealed interface GradingRulesIntent {
    data class Load(val examId: String) : GradingRulesIntent
    data object Refresh : GradingRulesIntent
    data class SetRule(val option: AnnulledRuleOption) : GradingRulesIntent
    data class SetRational(val on: Boolean) : GradingRulesIntent
    data class SetNumerator(val text: String) : GradingRulesIntent
    data class SetDenominator(val text: String) : GradingRulesIntent
    data class SetReason(val text: String) : GradingRulesIntent
    data object RequestSave : GradingRulesIntent
    data object ConfirmSave : GradingRulesIntent
    data object DismissConfirm : GradingRulesIntent
    data object Discard : GradingRulesIntent
}

/**
 * Reglas de puntuación de UN examen: qué pasa con las anuladas y si cada fallo penaliza.
 *
 * - La penalización racional y la fracción fija son excluyentes: activar la primera pone la fracción en 0/1 y no deja
 *   escribir la otra (la UI lo impide; el servidor y la base lo repiten).
 * - En un examen PUBLICADO el motivo es obligatorio y guardar pasa por un diálogo de confirmación: el cambio solo
 *   afecta a los intentos que EMPIECEN después, y queda en auditoría con quién y por qué.
 * - Nada se calcula aquí: el servidor decide, y el chequeo de reservas/anuladas (`admin_official_rules_check`) se enseña
 *   tal cual, sin bloquear ni aprobar por su cuenta.
 */
class ExamGradingRulesViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<GradingRulesUiState, GradingRulesIntent>(GradingRulesUiState(), dispatcherProvider) {
    private var writing = false

    override suspend fun handleIntent(intent: GradingRulesIntent) {
        when (intent) {
            is GradingRulesIntent.Load -> {
                if (currentState().examId == intent.examId && currentState().rules is UiAsync.Success) return
                updateState { GradingRulesUiState(examId = intent.examId, rules = UiAsync.Loading, audit = UiAsync.Loading) }
                load(intent.examId)
            }
            GradingRulesIntent.Refresh -> currentState().examId?.let { load(it) }
            is GradingRulesIntent.SetRule -> edit { it.copy(annulledRule = intent.option.wire) }
            is GradingRulesIntent.SetRational -> edit { GradingRulesValidator.withRational(it, intent.on) }
            is GradingRulesIntent.SetNumerator -> edit { if (it.rationalPenalty) it else it.copy(numerator = intent.text) }
            is GradingRulesIntent.SetDenominator -> edit { if (it.rationalPenalty) it else it.copy(denominator = intent.text) }
            is GradingRulesIntent.SetReason -> edit { it.copy(reason = intent.text) }
            GradingRulesIntent.Discard -> updateState { state ->
                state.copy(draft = state.current?.let { GradingRulesDraft.from(it) }, validation = null, confirming = false, save = UiAsync.Idle)
            }
            GradingRulesIntent.DismissConfirm -> updateState { it.copy(confirming = false) }
            GradingRulesIntent.RequestSave -> requestSave()
            GradingRulesIntent.ConfirmSave -> save()
        }
    }

    private fun edit(transform: (GradingRulesDraft) -> GradingRulesDraft) {
        updateState { state ->
            val d = state.draft ?: return@updateState state
            state.copy(draft = transform(d), validation = null, save = UiAsync.Idle)
        }
    }

    private suspend fun load(examId: String) {
        when (val r = repository.getGradingRules(examId)) {
            is AppResult.Failure -> updateState { it.copy(rules = UiAsync.Error(r.error), draft = null) }
            is AppResult.Success -> updateState {
                // Una relectura con el formulario a medias no pisa lo escrito: solo la primera carga arma el borrador.
                it.copy(rules = UiAsync.Success(r.value), draft = if (it.isDirty) it.draft else GradingRulesDraft.from(r.value))
            }
        }
        when (val a = repository.getGradingAudit(examId)) {
            is AppResult.Failure -> updateState { it.copy(audit = UiAsync.Error(a.error)) }
            is AppResult.Success -> updateState { it.copy(audit = if (a.value.isEmpty()) UiAsync.Empty else UiAsync.Success(a.value)) }
        }
    }

    private fun requestSave() {
        val state = currentState()
        val rules = state.current ?: return
        val draft = state.draft ?: return
        when (val v = GradingRulesValidator.validate(draft, published = rules.isPublished)) {
            is GradingValidation.Invalid -> updateState { it.copy(validation = v) }
            is GradingValidation.Valid -> updateState { it.copy(validation = null, confirming = rules.isPublished) }
        }
        // En un borrador no hay diálogo: se guarda en el acto.
        if (!rules.isPublished && currentState().validation == null) dispatch(GradingRulesIntent.ConfirmSave)
    }

    private suspend fun save() {
        if (writing) return
        val state = currentState()
        val examId = state.examId ?: return
        val rules = state.current ?: return
        val draft = state.draft ?: return
        // Sobre un examen publicado solo se guarda tras la confirmación explícita (RequestSave la abre).
        if (rules.isPublished && !state.confirming) return
        val v = GradingRulesValidator.validate(draft, published = rules.isPublished)
        if (v !is GradingValidation.Valid) {
            updateState { it.copy(validation = v as? GradingValidation.Invalid, confirming = false) }
            return
        }
        writing = true
        try {
            updateState { it.copy(confirming = false, save = UiAsync.Loading) }
            when (val r = repository.saveGradingRules(examId, draft.annulledRule, draft.rationalPenalty, v.numerator, v.denominator, v.reason)) {
                is AppResult.Failure -> updateState { it.copy(save = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(rules = UiAsync.Success(r.value), draft = GradingRulesDraft.from(r.value), save = UiAsync.Success(Unit)) }
                    when (val a = repository.getGradingAudit(examId)) {
                        is AppResult.Failure -> updateState { it.copy(audit = UiAsync.Error(a.error)) }
                        is AppResult.Success -> updateState { it.copy(audit = if (a.value.isEmpty()) UiAsync.Empty else UiAsync.Success(a.value)) }
                    }
                }
            }
        } finally {
            writing = false
        }
    }
}

/** Texto de un cambio de reglas para la pista de auditoría. */
object GradingChangeText {
    fun describe(before: GradingSnapshot?, after: GradingSnapshot?): String {
        if (before == null || after == null) return "—"
        val parts = buildList {
            if (before.annulledRule != after.annulledRule) add("Anuladas: ${rule(before.annulledRule)} → ${rule(after.annulledRule)}")
            if (before.rationalPenalty != after.rationalPenalty) {
                add("Penalización racional: ${if (before.rationalPenalty) "sí" else "no"} → ${if (after.rationalPenalty) "sí" else "no"}")
            }
            if (before.fixedNumerator != after.fixedNumerator || before.fixedDenominator != after.fixedDenominator) {
                add("Fracción: ${before.fixedNumerator}/${before.fixedDenominator} → ${after.fixedNumerator}/${after.fixedDenominator}")
            }
        }
        return parts.joinToString(" · ").ifEmpty { "Sin cambios visibles" }
    }

    private fun rule(wire: String?): String = AnnulledRuleOption.fromWire(wire).label
}
