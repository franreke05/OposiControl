package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * En qué exámenes vive esta pregunta.
 *
 * Aquí es donde se gestiona la relación desde que Preguntas dejó de ser una
 * subpestaña de Exámenes.
 *
 * **Lo que se sabe es lo que dice el servidor (KQ3-2).** La lista viene de
 * `GET /preguntas/{id}/examenes`: vacía significa «en ningún examen», y un fallo se
 * dice como fallo. Antes se inferían de la sesión y una lista vacía no significaba
 * nada, así que la pantalla tenía que disculparse con una nota.
 *
 * Vincular y quitar tienen ruta propia, sin reenviar el borrador. En un alta el
 * examen inicial es opcional —la pregunta no tiene especialidad (M8)—: si se elige, la
 * base lo vincula en la misma transacción que crea la pregunta.
 */
@Composable
internal fun QuestionRelationsSection(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = "Exámenes vinculados",
        description = "Dónde se usa esta pregunta y en qué posición.",
        icon = AppIcon.EXAMS,
        iconRole = SemanticRole.INFO,
        action = if (!state.isCreating && state.selectedQuestionId != null) {
            {
                SecondaryButton(
                    text = "Vincular a examen",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(QuestionsIntent.RequestAssociateExam) },
                    icon = { AppIconImage(AppIcon.ADD, size = 13.dp) },
                )
            }
        } else {
            null
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (state.isCreating) {
                QuestionCreateTargets(state, onIntent)
            } else {
                QuestionExamLinksList(state, onIntent)
            }
        }
    }
}

/** Alta: sólo el examen inicial, opcional. La pregunta no tiene especialidad (M8). */
@Composable
private fun QuestionCreateTargets(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = "Examen inicial (opcional)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
        // Todos los exámenes del TIPO elegido, de cualquier especialidad y estado
        // (M8). El selector siempre existe —«Sin examen inicial» es una respuesta
        // válida—; si no hay candidatos o el catálogo no cargó, se dice debajo en vez
        // de fingir una lista vacía.
        val examsView = questionLinkableExamsView(state.exams, state.draftType)
        val linkable = (examsView as? QuestionLinkableExamsView.Options)?.options.orEmpty()
        QuestionSelect(
            selectedLabel = linkable.firstOrNull { it.value == state.draftCreateExamId }?.label ?: "Sin examen inicial",
            options = listOf(QuestionFilterOption("", "Sin examen inicial")) + linkable,
            onSelect = { onIntent(QuestionsIntent.UpdateCreateExamId(it)) },
            accessibilityLabel = "Examen inicial de la pregunta",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Si lo eliges, la pregunta se crea y se vincula al examen en la misma operación.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        questionLinkableExamsNote(examsView)?.let { (note, isError) ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) tokens.dangerInk else tokens.textSecondary,
            )
        }
    }
}

/** Pregunta guardada: la lista real, con estado y posición, y «Quitar» por vínculo. */
@Composable
private fun QuestionExamLinksList(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    when (val links = state.examLinks) {
        is UiAsync.Loading, UiAsync.Idle -> Text(
            text = "Consultando en qué exámenes está…",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
        )
        is UiAsync.Error -> Text(
            text = QUESTION_LINKS_UNAVAILABLE_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.dangerInk,
        )
        is UiAsync.Empty -> Text(
            text = QUESTION_LINKS_EMPTY_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textPrimary,
        )
        is UiAsync.Success -> if (links.value.isEmpty()) {
            Text(
                text = QUESTION_LINKS_EMPTY_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                links.value.forEach { link ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            text = link.examCode.ifBlank { link.examTitle },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.textPrimary,
                        )
                        Text(
                            text = link.examTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = questionExamLinkStatusLabel(link.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (link.isPublished) tokens.textPrimary else tokens.textSecondary,
                        )
                        Text(
                            text = questionExamLinkPositionLabel(link.position),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                        )
                        SecondaryButton(
                            text = "Quitar",
                            size = ButtonSize.COMPACT,
                            onClick = { onIntent(QuestionsIntent.RequestUnlinkExam(link.examId)) },
                        )
                    }
                }
            }
        }
    }
    questionTypeLockReason(state)?.let { reason ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AppIconImage(AppIcon.INFO, size = 13.dp, tint = tokens.textSecondary)
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}
