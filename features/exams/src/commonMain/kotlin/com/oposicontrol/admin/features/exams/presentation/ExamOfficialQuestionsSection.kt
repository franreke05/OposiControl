package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.GradingCheckTexts
import com.oposicontrol.admin.domain.model.OfficialQuestion
import com.oposicontrol.admin.domain.model.OfficialQuestionValidator

internal const val OFFICIAL_QUESTIONS_TAG = "exam-official-questions"

/** `null` por defecto: sin él la sección no se pinta y el editor queda exactamente como estaba. */
val LocalOfficialQuestionsViewModel = staticCompositionLocalOf<ExamOfficialQuestionsViewModel?> { null }

/** Sección «Anuladas y reservas» de un examen oficial ya guardado. */
@Composable
internal fun ExamOfficialQuestionsHost(examId: String) {
    val viewModel = LocalOfficialQuestionsViewModel.current ?: return
    LaunchedEffect(examId) { viewModel.dispatch(OfficialQuestionsIntent.Load(examId)) }
    val state by viewModel.state.collectAsState()
    ExamOfficialQuestionsContent(state, viewModel::dispatch)
}

@Composable
internal fun ExamOfficialQuestionsContent(state: OfficialQuestionsUiState, onIntent: (OfficialQuestionsIntent) -> Unit) {
    FormSection(
        title = "Anuladas y reservas",
        description = "Una anulada se muestra en la app con aviso y no suma ni resta. Una reserva va tras las principales y, " +
            "si se anula alguna principal, entra por orden en su lugar (1 anulada: la reserva 1; 2: las reservas 1 y 2…).",
        icon = AppIcon.WARNING,
        modifier = Modifier.testTag(OFFICIAL_QUESTIONS_TAG),
    ) {
        AsyncStatePane(
            state = state.data,
            emptyTitle = "Sin preguntas",
            emptyMessage = "Este examen aún no tiene preguntas vinculadas.",
            onRetry = { onIntent(OfficialQuestionsIntent.Refresh) },
        ) { data ->
            if (!data.reservesAvailable) {
                WorkspaceNotice(
                    WorkspaceNoticeTone.WARNING,
                    "El servidor conectado aún no admite reservas",
                    detail = "Se muestran las preguntas y sus anulaciones; las reservas se podrán editar cuando el servidor se actualice.",
                )
            }
            data.check?.let { check ->
                check.problem?.let { WorkspaceNotice(WorkspaceNoticeTone.DANGER, GradingCheckTexts.problem(it)) }
                check.warnings.forEach { WorkspaceNotice(WorkspaceNoticeTone.WARNING, GradingCheckTexts.warning(it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                WorkspacePill("${data.questions.size} preguntas")
                WorkspacePill("${data.mainAnnulled} anuladas principales")
                WorkspacePill("${data.reserves.size} reservas")
            }
            AdminTable(
                columns = listOf(
                    AdminTableColumn("Pos.", numeric = true),
                    AdminTableColumn("Pregunta", weight = 3f),
                    AdminTableColumn("Marca"),
                ),
                rows = data.questions.map { q ->
                    AdminTableRow(
                        key = q.questionId,
                        cells = listOf(q.position.toString(), q.title.ifBlank { "Sin enunciado" }, marksLabel(q)),
                        selected = q.questionId == state.selectedId,
                        supporting = q.annulmentReason?.takeIf { q.annulled },
                    )
                },
                onRowClick = { onIntent(OfficialQuestionsIntent.Select(it)) },
            )
            val question = state.selected
            val draft = state.draft
            if (question != null && draft != null) {
                FormSection(title = "Pregunta ${question.position}", description = question.title.take(160)) {
                    if (question.isDemo) {
                        WorkspaceNotice(WorkspaceNoticeTone.INFO, "Pregunta de demostración", detail = "No puede estar anulada ni ser reserva.")
                    }
                    val editable = !question.isDemo
                    GlassToggleRow(
                        title = "Anulada por el tribunal",
                        description = "Se muestra con aviso; no es correcta, ni incorrecta, ni sin responder.",
                        checked = draft.annulled,
                        enabled = editable,
                        onCheckedChange = { onIntent(OfficialQuestionsIntent.SetAnnulled(it)) },
                    )
                    if (draft.annulled) {
                        LabeledField(
                            label = "Motivo de la anulación",
                            value = draft.reason,
                            onValueChange = { onIntent(OfficialQuestionsIntent.SetReason(it)) },
                            supportingText = state.errors["reason"] ?: "Opcional. Máximo ${OfficialQuestionValidator.REASON_MAX} caracteres.",
                            isError = state.errors.containsKey("reason"),
                            singleLine = false,
                            minLines = 2,
                        )
                    }
                    GlassToggleRow(
                        title = "Es una reserva",
                        description = "Se coloca tras la última pregunta principal.",
                        checked = draft.isReserve,
                        enabled = editable && data.reservesAvailable,
                        onCheckedChange = { onIntent(OfficialQuestionsIntent.SetReserve(it)) },
                    )
                    if (draft.isReserve) {
                        LabeledField(
                            label = "Orden de la reserva",
                            value = draft.order,
                            onValueChange = { onIntent(OfficialQuestionsIntent.SetOrder(it)) },
                            supportingText = state.errors["order"] ?: "De 1 a ${OfficialQuestionValidator.ORDER_MAX}, sin huecos entre reservas.",
                            isError = state.errors.containsKey("order"),
                        )
                    }
                    state.errors["demo"]?.let { WorkspaceNotice(WorkspaceNoticeTone.DANGER, it) }
                    FormFeedback(state = state.save, successText = "Marcas guardadas.")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        GhostButton(text = "Cerrar", size = ButtonSize.COMPACT, onClick = { onIntent(OfficialQuestionsIntent.Select(null)) })
                        PrimaryButton(
                            text = "Guardar marcas",
                            size = ButtonSize.COMPACT,
                            enabled = editable,
                            loading = state.save is UiAsync.Loading,
                            onClick = { onIntent(OfficialQuestionsIntent.Save) },
                        )
                    }
                }
            }
        }
    }
}

private fun marksLabel(q: OfficialQuestion): String = buildList {
    if (q.annulled) add("Anulada")
    if (q.isReserve) add("Reserva ${q.reserveOrder ?: "?"}")
    if (q.isDemo) add("Demo")
}.joinToString(" · ").ifEmpty { "—" }
