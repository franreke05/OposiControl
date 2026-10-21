package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AnnulledRuleOption
import com.oposicontrol.admin.domain.model.GradingCheckTexts
import com.oposicontrol.admin.domain.model.GradingRules
import com.oposicontrol.admin.domain.model.GradingRulesDraft
import com.oposicontrol.admin.domain.model.GradingRulesValidator
import com.oposicontrol.admin.domain.model.GradingValidation

internal const val GRADING_RULES_TAG = "exam-grading-rules"
internal const val GRADING_AUDIT_TAG = "exam-grading-audit"

/**
 * Cómo se provee el ViewModel de reglas al editor de exámenes. Es `null` por defecto: sin él la sección no se pinta y el
 * editor se comporta exactamente como antes (sus pruebas visuales no cambian). La app lo provee desde su raíz.
 */
val LocalGradingRulesViewModel = staticCompositionLocalOf<ExamGradingRulesViewModel?> { null }

/** Sección de reglas de puntuación dentro de Configuración del examen. Solo se pinta con un examen ya guardado. */
@Composable
internal fun ExamGradingRulesHost(examId: String, isOfficial: Boolean) {
    val viewModel = LocalGradingRulesViewModel.current ?: return
    LaunchedEffect(examId) { viewModel.dispatch(GradingRulesIntent.Load(examId)) }
    val state by viewModel.state.collectAsState()
    ExamGradingRulesContent(state = state, isOfficial = isOfficial, onIntent = viewModel::dispatch)
}

@Composable
internal fun ExamGradingRulesContent(state: GradingRulesUiState, isOfficial: Boolean, onIntent: (GradingRulesIntent) -> Unit) {
    FormSection(
        title = "Reglas de puntuación",
        description = "Qué pasa con las preguntas anuladas y si cada fallo penaliza. El cambio solo afecta a los intentos que empiecen después; " +
            "los que ya están en marcha conservan las reglas con las que empezaron.",
        icon = AppIcon.CHECK,
        modifier = Modifier.testTag(GRADING_RULES_TAG),
    ) {
        AsyncStatePane(
            state = state.rules,
            emptyTitle = "Sin reglas",
            emptyMessage = "El servidor no devolvió las reglas de este examen.",
            onRetry = { onIntent(GradingRulesIntent.Refresh) },
        ) { rules ->
            val draft = state.draft
            if (draft != null) GradingForm(rules, draft, state, isOfficial, onIntent)
        }
    }
    FormSection(
        title = "Historial de reglas",
        description = "Quién cambió las reglas de este examen, cuándo y por qué. Solo se añade, nunca se edita.",
        icon = AppIcon.AUDIT,
    ) {
        AsyncStatePane(
            state = state.audit,
            emptyTitle = "Sin cambios",
            emptyMessage = "Las reglas de este examen no se han modificado.",
            onRetry = { onIntent(GradingRulesIntent.Refresh) },
        ) { entries ->
            AdminTable(
                modifier = Modifier.testTag(GRADING_AUDIT_TAG),
                columns = listOf(AdminTableColumn("Fecha"), AdminTableColumn("Autor"), AdminTableColumn("Cambio", weight = 2f), AdminTableColumn("Motivo")),
                rows = entries.map { e ->
                    AdminTableRow(
                        key = e.id,
                        cells = listOf(
                            e.at?.let { auditWhen(it) } ?: "—",
                            e.actorId?.take(8)?.let { "$it…" } ?: "—",
                            if (e.before != null) GradingChangeText.describe(e.before, e.after) else "Motivo registrado",
                            e.reason ?: "—",
                        ),
                    )
                },
            )
        }
    }
}

private fun auditWhen(iso: String): String = if (iso.length >= 16 && iso[10] == 'T') iso.substring(0, 10) + " " + iso.substring(11, 16) else iso.take(32)

@Composable
private fun GradingForm(
    rules: GradingRules,
    draft: GradingRulesDraft,
    state: GradingRulesUiState,
    isOfficial: Boolean,
    onIntent: (GradingRulesIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    // AsyncStatePane pinta su contenido en un Box: sin esta Column los hijos se apilaban unos encima de otros (UI E2E 2026-10-20).
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        WorkspacePill("${rules.reserves} reservas")
        WorkspacePill("${rules.annulledMain} anuladas principales")
        if (rules.isPublished) WorkspacePill("Publicado")
        if (isOfficial || rules.isOfficial) WorkspacePill("Oficial")
    }
    rules.check?.let { check ->
        check.problem?.let {
            WorkspaceNotice(WorkspaceNoticeTone.DANGER, GradingCheckTexts.problem(it), detail = "El servidor no dejará publicar el examen así.")
        }
        check.warnings.forEach { WorkspaceNotice(WorkspaceNoticeTone.WARNING, GradingCheckTexts.warning(it)) }
    } ?: WorkspaceNotice(WorkspaceNoticeTone.INFO, "No se pudo comprobar el estado de las reservas", detail = "La comprobación del servidor no está disponible ahora mismo.")

    val option = AnnulledRuleOption.fromWire(draft.annulledRule)
    DropdownField(
        label = "Preguntas anuladas",
        selectedLabel = option.label,
        options = AnnulledRuleOption.entries.map { it.wire to it.label },
        onSelect = { onIntent(GradingRulesIntent.SetRule(AnnulledRuleOption.fromWire(it))) },
        supportingText = option.help,
    )
    GlassToggleRow(
        title = "Penalización racional",
        description = "Cada fallo resta 1/(n−1) de un acierto, con n = opciones de esa pregunta (en una de 4 opciones, 3 fallos cancelan 1 acierto). " +
            if (isOfficial || rules.isOfficial) "Lo habitual en exámenes oficiales." else "Normalmente apagada fuera de los oficiales.",
        checked = draft.rationalPenalty,
        onCheckedChange = { onIntent(GradingRulesIntent.SetRational(it)) },
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        LabeledField(
            label = "Fracción fija: numerador",
            value = draft.numerator,
            onValueChange = { onIntent(GradingRulesIntent.SetNumerator(it)) },
            modifier = Modifier.width(240.dp),
            readOnly = draft.rationalPenalty,
            isError = state.validation?.field == GradingValidation.Field.FRACTION,
        )
        LabeledField(
            label = "Denominador",
            value = draft.denominator,
            onValueChange = { onIntent(GradingRulesIntent.SetDenominator(it)) },
            modifier = Modifier.width(240.dp),
            readOnly = draft.rationalPenalty,
            isError = state.validation?.field == GradingValidation.Field.FRACTION,
        )
    }
    Text(
        text = if (draft.rationalPenalty) {
            "La fracción fija no se usa con la penalización racional."
        } else {
            GradingRulesValidator.penaltySummary(false, draft.numerator.trim().toIntOrNull() ?: 0, draft.denominator.trim().toIntOrNull() ?: 1)
        },
        style = MaterialTheme.typography.bodySmall,
        color = tokens.textSecondary,
    )
    if (rules.isPublished || draft.reason.isNotEmpty()) {
        LabeledField(
            label = if (rules.isPublished) "Motivo del cambio (obligatorio)" else "Motivo del cambio",
            value = draft.reason,
            onValueChange = { onIntent(GradingRulesIntent.SetReason(it)) },
            supportingText = "Queda en el historial con tu cuenta.",
            isError = state.validation?.field == GradingValidation.Field.REASON,
            singleLine = false,
            minLines = 2,
        )
    }
    state.validation?.let { WorkspaceNotice(WorkspaceNoticeTone.DANGER, it.message) }
    FormFeedback(state = state.save, successText = "Reglas guardadas.")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        GhostButton(text = "Descartar", size = ButtonSize.COMPACT, enabled = state.isDirty || draft.reason.isNotEmpty(), onClick = { onIntent(GradingRulesIntent.Discard) })
        PrimaryButton(
            text = "Guardar reglas",
            size = ButtonSize.COMPACT,
            enabled = state.isDirty,
            loading = state.save is UiAsync.Loading,
            onClick = { onIntent(GradingRulesIntent.RequestSave) },
        )
    }
    if (state.confirming) {
        ConfirmDialog(
            title = "Cambiar las reglas de un examen publicado",
            message = "Los intentos que empiecen a partir de ahora usarán las reglas nuevas; los que ya están en marcha conservan las suyas. " +
                "Queda registrado con tu cuenta y el motivo que has escrito.",
            confirmLabel = "Cambiar reglas",
            destructive = false,
            onConfirm = { onIntent(GradingRulesIntent.ConfirmSave) },
            onDismiss = { onIntent(GradingRulesIntent.DismissConfirm) },
        )
    }
    }
}
