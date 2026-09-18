package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.QuestionResourceType

/**
 * Los recursos de las preguntas de este examen. En plural y con esa preposición.
 *
 * **No existe la relación examen → recurso.** `recursos_pregunta` tiene
 * `pregunta_id` y no tiene `examen_id`, y todas las rutas de recurso cuelgan de
 * `/preguntas/{id}/recursos`. La cadena real es
 * examen → preguntas_examen → preguntas → recursos_pregunta, así que esta
 * pestaña es una **vista agregada de sólo lectura**: sirve para revisar de una
 * vez lo que hay repartido por las preguntas, no para gestionarlo.
 *
 * De ahí tres decisiones que conviene no deshacer:
 *
 *  - **No hay estado de recurso.** El único que la base guarda es `activo`, y ni
 *    siquiera llega al cliente. «Válido», «pendiente» y «revisado» no existen en
 *    ninguna parte del sistema: eran una deducción del propio panel («no es
 *    texto y no tiene URL»), no un dato.
 *  - **No se ofrece subir nada.** Hoy los recursos no suben ficheros: el campo de
 *    URL acaba conteniendo rutas locales de quien administra. Un botón de subida
 *    aquí prometería un almacenamiento que no está.
 *  - **La pestaña se muestra siempre**, sea cual sea el tipo del EXAMEN: un
 *    examen de cualquier tipo puede contener preguntas inmersivas, o ninguna.
 *  - **Sólo las preguntas inmersivas pueden llevar recursos**, y eso sí ata la
 *    regla al tipo de la PREGUNTA, no al del examen. El backend no la impone, así
 *    que en los datos pueden existir recursos colgados de otra cosa: se enseñan
 *    como anomalía ([EXAM_RESOURCE_NOT_IMMERSIVE]) en vez de esconderlos. De una
 *    pregunta que no sea inmersiva sólo se afirma que **no consta** como tal: un
 *    `tipo_pregunta` ausente llega como «rafaga» y no se puede distinguir.
 */
@Composable
internal fun ExamResourcesSection(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val isNew = state.selectedExamId == null

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        FormSection(
            title = "Recursos de las preguntas de este examen",
            description = "Vista de sólo lectura: un recurso pertenece a su pregunta y sólo las inmersivas pueden llevarlos. " +
                "Subir ficheros todavía no funciona desde la consola, así que aquí no se ofrece.",
            help = Help.recursosExamen,
            icon = AppIcon.RESOURCES,
            iconRole = SemanticRole.INFO,
        ) {
            if (isNew) {
                EmptyStateCard(
                    title = "Todavía no hay examen",
                    message = "Guarda el examen y vincúlale preguntas: sus recursos aparecerán aquí.",
                    icon = AppIcon.RESOURCES,
                )
            } else {
                AsyncStatePane(
                    state = state.questions,
                    emptyTitle = "Sin preguntas",
                    emptyMessage = "Este examen todavía no tiene preguntas asociadas, así que tampoco tiene recursos.",
                    emptyIcon = AppIcon.RESOURCES,
                    loadingRows = 4,
                    onRetry = { onIntent(ExamsIntent.RetryLoadQuestions) },
                ) { questions ->
                    val groups = examResourceGroups(questions)
                    val totals = examResourceTotals(questions)
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            text = examResourceTotalsText(totals),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                        )
                        if (groups.isEmpty()) {
                            EmptyStateCard(
                                title = "Sin recursos",
                                message = "No hay recursos asociados a las preguntas de este examen.",
                                icon = AppIcon.RESOURCES,
                            )
                        } else {
                            groups.forEach { group -> ExamResourceGroupPanel(group) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamResourceGroupPanel(group: ExamResourceGroup) {
    val tokens = GlassTheme.tokens
    ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "${group.position}. ${group.questionTitle}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // El tipo de la pregunta, porque es lo que decide si puede llevar
        // recursos. Se dice siempre, también cuando no se reconoce.
        Text(
            text = questionTypeLabel(group.questionType),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary,
        )
        if (!group.resourcesAllowed) {
            ExamResourceNote(EXAM_RESOURCE_NOT_IMMERSIVE)
        }
        group.resources.forEach { resource ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SemanticIconContainer(
                    icon = resource.type.icon(),
                    role = SemanticRole.INFO,
                    size = Sizing.iconButtonSmall,
                    iconSize = 14.dp,
                    contentDescription = resource.typeLabel,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = resource.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = resource.preview.ifBlank { "Sin contenido guardado" },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Una subida que nunca ocurrió. Se dice aquí, pegado al
                    // recurso, porque es lo que decide si sirve para algo.
                    if (resource.isUnavailable) {
                        ExamResourceNote(EXAM_RESOURCE_UNAVAILABLE)
                    }
                }
                Text(
                    text = resource.typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * Icono por tipo de recurso.
 *
 * Los cinco tipos son los que la base admite. Un valor que no sea ninguno de
 * ellos no llega hasta aquí: el mapper lo resuelve antes, y esta pantalla no es
 * el sitio donde arreglar eso.
 */
private fun QuestionResourceType.icon(): AppIcon = when (this) {
    QuestionResourceType.IMAGE -> AppIcon.ASSETS
    QuestionResourceType.AUDIO -> AppIcon.EVENTS
    QuestionResourceType.VIDEO -> AppIcon.VIEW
    QuestionResourceType.DOCUMENT -> AppIcon.RESOURCES
    QuestionResourceType.TEXT -> AppIcon.NEWS_TYPES
}

/**
 * Un aviso corto, pegado a lo que describe.
 *
 * El texto va en `textSecondary` y no en ámbar a propósito: el ámbar sobre
 * superficie sólida se queda en 4,40:1 en claro, por debajo de AA para texto de
 * cuerpo. El tono lo lleva el icono, que es elemento de interfaz y le basta con
 * 3:1; el significado lo lleva la frase.
 */
@Composable
private fun ExamResourceNote(text: String) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SemanticIconContainer(
            icon = AppIcon.WARNING,
            role = SemanticRole.WARNING,
            size = Sizing.iconButtonSmall,
            iconSize = 12.dp,
            contentDescription = null,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}
