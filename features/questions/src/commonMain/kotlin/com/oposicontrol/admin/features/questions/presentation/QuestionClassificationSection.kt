package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Lo que clasifica a la pregunta — con lo que el contrato tiene.
 *
 * **No hay «Tema» ni «Especialidad».** `QuestionDetail` no los trae y
 * `QuestionsRepository` no los expone. El banco anterior pintaba un desplegable
 * «Tema» con una sola opción y `onSelect = {}`, y esta sección decía «Tema: No
 * disponible»: dos sitios ocupados por un campo que no existe. Se dice una vez y
 * en su sitio ([QUESTION_NO_TOPIC_NOTE]).
 *
 * «Dificultad» se lee y no se edita: es del contrato y se reenvía intacta al
 * guardar, pero esta pantalla no tiene control para ella.
 */
@Composable
internal fun QuestionClassificationSection(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = "Clasificación",
        description = "Si la pregunta está en uso y cómo está catalogada.",
        icon = AppIcon.LAYERS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            GlassToggleRow(
                title = "Pregunta activa",
                description = "Una pregunta inactiva se queda en el banco pero no se sirve.",
                checked = state.draftActive,
                onCheckedChange = { onIntent(QuestionsIntent.SetActive(it)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = "Dificultad",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
                Text(
                    text = questionDifficultyLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "· se conserva al guardar, no se edita aquí",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppIconImage(AppIcon.INFO, size = 13.dp, tint = tokens.textSecondary)
                Text(
                    text = QUESTION_NO_TOPIC_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}
