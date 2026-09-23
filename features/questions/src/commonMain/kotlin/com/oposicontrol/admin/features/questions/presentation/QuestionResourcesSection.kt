package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionResourceType

/**
 * Los recursos de una pregunta inmersiva.
 *
 * **Sólo Inmersiva.** Los recursos pertenecen a la pregunta, nunca al examen, y
 * sólo este tipo puede apoyarse en ellos: quien llama a esta sección ya lo ha
 * comprobado con [questionShowsResources].
 *
 * **Nunca se enseña `storagePath`.** Es una ruta interna del almacenamiento y no
 * le dice nada a quien administra; lo que importa es si el recurso se puede abrir.
 * Lo decide [questionResourceRows], que también resuelve el caso del recurso de
 * texto —se sostiene con su propio cuerpo y no necesita dirección pública—.
 *
 * **P0-4.** Subir o vincular recursos no funciona, así que el control está
 * desactivado con su motivo al lado. Un botón que pareciera funcionar y devolviera
 * un éxito falso sería peor que no tenerlo.
 */
@Composable
internal fun QuestionResourcesSection(detail: QuestionDetail?) {
    val tokens = GlassTheme.tokens
    val rows = questionResourceRows(detail?.resources.orEmpty())
    FormSection(
        title = "Recursos",
        description = "Material de apoyo de esta pregunta inmersiva.",
        icon = AppIcon.RESOURCES,
        iconRole = SemanticRole.INFO,
        action = {
            SecondaryButton(
                text = "Añadir recurso",
                size = ButtonSize.COMPACT,
                onClick = {},
                enabled = false,
                icon = { AppIconImage(AppIcon.ADD, size = 13.dp) },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (rows.isEmpty()) {
                Text(
                    text = if (detail == null) {
                        "Los recursos aparecerán aquí cuando la pregunta exista."
                    } else {
                        QUESTION_RESOURCES_EMPTY
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                )
            } else {
                rows.forEach { row -> QuestionResourceRowView(row, detail) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppIconImage(AppIcon.INFO, size = 13.dp, tint = tokens.textSecondary)
                Text(
                    text = QUESTION_RESOURCE_UPLOAD_BLOCKED_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun QuestionResourceRowView(row: QuestionResourceRow, detail: QuestionDetail?) {
    val tokens = GlassTheme.tokens
    val icon = detail?.resources
        ?.firstOrNull { it.title.trim().ifBlank { "Recurso sin título" } == row.title }
        ?.let { questionResourceIcon(it.type) }
        ?: AppIcon.RESOURCES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, Radius.md)
            .padding(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SemanticIconContainer(
            icon = icon,
            role = SemanticRole.INFO,
            size = Sizing.iconButtonSmall,
            iconSize = 16.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
        // Accesible o no, con palabra: el estado no va sólo en color.
        Text(
            text = row.accessLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (row.accessible) tokens.textPrimary else tokens.textSecondary,
        )
    }
}

internal fun questionResourceIcon(type: QuestionResourceType): AppIcon = when (type) {
    QuestionResourceType.IMAGE -> AppIcon.ASSETS
    QuestionResourceType.AUDIO -> AppIcon.EVENTS
    QuestionResourceType.VIDEO -> AppIcon.VIEW
    QuestionResourceType.TEXT -> AppIcon.NEWS_TYPES
    QuestionResourceType.DOCUMENT -> AppIcon.REPORTS
}
