package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Las cuatro opciones y cuál es la correcta.
 *
 * Una fila por opción, en columna: A, B, C y D empiezan en la misma vertical y se
 * leen de arriba abajo como se responde. La correcta lleva TRES señales — el
 * radio marcado, el borde verde y la palabra «Correcta» — porque el color solo
 * deja fuera a quien no lo distingue.
 *
 * **Sobre el bloqueo de opciones (P0-5).** No se simula edición: los campos
 * escriben de verdad en el borrador y el guardado los manda. Lo que se dice es la
 * verdad concreta ([QUESTION_OPTIONS_RISK_NOTE]): ese camino depende de una RPC
 * que hoy puede rechazar el PATCH, y si lo rechaza se ve el error, nunca un
 * «guardado» falso.
 */
@Composable
internal fun QuestionAnswersSection(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val rows = questionAnswerRows(state)
    FormSection(
        title = "Respuestas",
        description = "Cuatro opciones y una única correcta.",
        icon = AppIcon.CHECK,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            rows.forEach { row ->
                QuestionAnswerRowEditor(
                    row = row,
                    onContentChange = { onIntent(QuestionsIntent.UpdateOptionContent(row.label, it)) },
                    onSelectCorrect = { onIntent(QuestionsIntent.SetCorrectOption(row.label)) },
                )
            }
            Text(
                text = questionCorrectAnswerNote(state),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppIconImage(AppIcon.INFO, size = 13.dp, tint = tokens.textSecondary)
                Text(
                    text = QUESTION_OPTIONS_RISK_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun QuestionAnswerRowEditor(
    row: QuestionAnswerRow,
    onContentChange: (String) -> Unit,
    onSelectCorrect: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(if (row.correct) tokens.surfaceSelected else tokens.surfaceSolid)
            .border(
                width = 1.dp,
                color = if (row.correct) tokens.success.copy(alpha = 0.55f) else tokens.border,
                shape = Radius.md,
            )
            .padding(Spacing.xs)
            .semantics {
                contentDescription = "Opción ${row.label}. " +
                    if (row.correct) "Respuesta correcta." else "No es la correcta."
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        RadioButton(
            selected = row.correct,
            onClick = onSelectCorrect,
            colors = RadioButtonDefaults.colors(
                selectedColor = tokens.success,
                unselectedColor = tokens.textSecondary,
            ),
        )
        Text(
            text = row.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
            modifier = Modifier.padding(top = 10.dp).size(width = 18.dp, height = 24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            QuestionTextField(
                value = row.content,
                onValueChange = onContentChange,
                placeholder = "Texto de la opción ${row.label}",
                singleLine = false,
                // Cuatro filas: lo que se ahorra aquí se multiplica por cuatro y
                // decide si Clasificación y Recursos caen bajo el pliegue.
                minHeight = 40.dp,
            )
            if (row.empty) {
                Text(
                    text = "Sin texto: no se puede guardar una opción vacía.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
        if (row.correct) {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(Radius.pill)
                    .background(tokens.surfaceSolid)
                    .border(1.dp, tokens.success.copy(alpha = 0.45f), Radius.pill)
                    .padding(horizontal = Spacing.xs, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                AppIconImage(AppIcon.CHECK, size = 12.dp, tint = tokens.success)
                Text(
                    text = "Correcta",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
            }
        } else {
            Box(modifier = Modifier.size(0.dp))
        }
    }
}
