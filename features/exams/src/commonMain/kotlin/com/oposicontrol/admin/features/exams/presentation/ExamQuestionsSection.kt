package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SectionLink
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusDotChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam

/**
 * Qué preguntas tiene el examen, en qué orden, y qué se puede hacer con ellas.
 *
 * La relación real es `preguntas_examen(examen_id, pregunta_id, posicion)`. De las
 * operaciones que existen sobre ella, esta pestaña ofrece las cuatro que
 * funcionan de verdad —listar, reordenar, desvincular y archivar— y explica las
 * que no en vez de fingirlas:
 *
 *  - **Ordenar** (`PATCH …/preguntas/orden`) es inmediato y persiste: la lista que
 *    se ve después de mover es la que el servidor acaba de escribir.
 *  - **Desvincular** (`DELETE …/examenes/{id}/preguntas/{preguntaId}`) borra sólo
 *    la fila de `preguntas_examen` y recompacta `posicion`. La pregunta sigue en
 *    el banco.
 *  - **Archivar** (`DELETE …/preguntas/{id}`) la saca del banco y, por tanto, de
 *    todos los exámenes que la usen. Las dos confirman, con el diálogo del
 *    ViewModel, y el de archivar admite que **no puede decir de cuántos**: no
 *    existe la consulta inversa pregunta → exámenes.
 *  - **Crear y editar opciones** están bloqueadas en el servidor (P0-5). El bloque
 *    existe, con su botón deshabilitado y el motivo al lado: prepararlo sin
 *    decirlo sería un botón que sólo puede terminar en error.
 *  - **Vincular una pregunta existente** no tiene ruta. No se inventa un botón:
 *    el vacío explica el camino que sí funciona, que es la pantalla de Preguntas.
 *
 * **Inmediatas ≠ formulario.** Todo lo de esta pestaña viaja al pulsarlo; el tipo,
 * la duración o el acceso esperan a «Guardar cambios». Se dice arriba, una vez, y
 * es lo que impide que alguien pulse «Descartar» creyendo que deshace una
 * desvinculación.
 *
 * Lo que decide qué se pinta está en `ExamQuestionsPresentation.kt`; aquí sólo se
 * pinta. El recuento sale de la misma fuente que la cabecera y la insignia de la
 * pestaña ([examQuestionsCountLabel]), que es la única forma de que los tres no se
 * contradigan.
 */
@Composable
internal fun ExamQuestionsSection(
    state: ExamsState,
    exam: Exam?,
    onIntent: (ExamsIntent) -> Unit,
    onOpenQuestionBank: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val isNew = state.selectedExamId == null
    val busy = examQuestionsBusy(state)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        FormSection(
            title = "Preguntas vinculadas",
            description = "El orden de esta lista es el orden en el que el usuario las verá.",
            help = Help.numeroPreguntas,
            icon = AppIcon.QUESTIONS,
            iconRole = SemanticRole.INFO,
            action = {
                if (onOpenQuestionBank != null && !isNew) {
                    SectionLink(text = "Ir a Preguntas", onClick = onOpenQuestionBank)
                }
            },
        ) {
            if (isNew) {
                EmptyStateCard(
                    title = EXAM_QUESTIONS_NEW_EXAM_TITLE,
                    message = EXAM_QUESTIONS_NEW_EXAM_MESSAGE,
                    icon = AppIcon.QUESTIONS,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // El recuento, con su procedencia: valor, provisional «sin
                    // confirmar» mientras carga, o sin cifra si falló.
                    ExamQuestionsCountLine(state = state, exam = exam)
                    Text(
                        text = EXAM_QUESTIONS_IMMEDIATE_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )

                    when (val questions = state.questions) {
                        // Idle y Loading son lo mismo para quien mira: todavía no
                        // están. Lo que NO se hace es pintar una lista vacía, que
                        // se leería como «este examen no tiene preguntas».
                        UiAsync.Idle, UiAsync.Loading -> ExamSolidPanel {
                            ExamStatusRow(
                                status = ExamCheckStatus.UNKNOWN,
                                label = EXAM_QUESTIONS_LOADING_LABEL,
                                detail = EXAM_QUESTIONS_LOADING_DETAIL,
                            )
                        }

                        UiAsync.Empty -> ExamQuestionsEmpty(onOpenQuestionBank)

                        is UiAsync.Error -> ErrorStateCard(
                            title = EXAM_QUESTIONS_ERROR_TITLE,
                            message = examQuestionsLoadError(questions).orEmpty(),
                            onRetry = { onIntent(ExamsIntent.RetryLoadQuestions) },
                        )

                        is UiAsync.Success -> {
                            val rows = examQuestionRows(questions.value)
                            if (rows.isEmpty()) {
                                ExamQuestionsEmpty(onOpenQuestionBank)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    rows.forEach { row ->
                                        ExamQuestionRowPanel(
                                            row = row,
                                            enabled = !busy,
                                            onMoveUp = { onIntent(ExamsIntent.MoveQuestionUp(row.id)) },
                                            onMoveDown = { onIntent(ExamsIntent.MoveQuestionDown(row.id)) },
                                            onUnlink = { onIntent(ExamsIntent.RequestUnlinkQuestion(row.id)) },
                                            onArchive = { onIntent(ExamsIntent.RequestArchiveQuestion(row.id)) },
                                        )
                                    }
                                    Text(
                                        text = EXAM_QUESTION_UNLINK_NOTE,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.textSecondary,
                                    )
                                    // La reserva sobre el tipo va aquí, una sola
                                    // vez: es una limitación del contrato, no un
                                    // problema de una fila concreta.
                                    Text(
                                        text = EXAM_QUESTION_TYPE_CAVEAT,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // Crear preguntas: preparado y BLOQUEADO, con el motivo a la vista
        // -------------------------------------------------------------------
        FormSection(
            title = "Crear preguntas",
            description = "Todavía no se puede hacer desde aquí.",
            icon = AppIcon.ADD,
            iconRole = SemanticRole.NEUTRAL,
            action = {
                if (onOpenQuestionBank != null) {
                    SectionLink(text = "Ir a Preguntas", onClick = onOpenQuestionBank)
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SecondaryButton(
                    text = "Crear pregunta",
                    // Deshabilitado de verdad, no un clic que devuelve un error:
                    // el servidor no admite la operación y eso no depende de nada
                    // que pueda hacer quien mira la pantalla.
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.width(ExamQuestionsSizing.blockedButtonWidth),
                )
                Text(
                    text = EXAM_QUESTIONS_CREATE_BLOCKED,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * El recuento de preguntas, pintado según de dónde sale.
 *
 * Un «5 preguntas vinculadas» confirmado y un «5 preguntas vinculadas · sin
 * confirmar» no pueden leerse igual, así que el peso y el color los da
 * [examFieldValueStyle] a partir del origen, como en el resto del workspace.
 */
@Composable
private fun ExamQuestionsCountLine(state: ExamsState, exam: Exam?) {
    val tokens = GlassTheme.tokens
    val label = examQuestionsCountLabel(state, exam)
    val style = examFieldValueStyle(label.origin, tokens)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = style.weight,
            color = style.color,
        )
        label.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

/** Examen guardado y sin preguntas: un dato, con el camino real para cambiarlo. */
@Composable
private fun ExamQuestionsEmpty(onOpenQuestionBank: (() -> Unit)?) {
    EmptyStateCard(
        title = EXAM_QUESTIONS_EMPTY_TITLE,
        message = EXAM_QUESTIONS_EMPTY_MESSAGE,
        icon = AppIcon.QUESTIONS,
        action = {
            if (onOpenQuestionBank != null) {
                SectionLink(text = "Ir a Preguntas", onClick = onOpenQuestionBank)
            }
        },
    )
}

/**
 * Una pregunta del examen.
 *
 * Enseña su posición, de qué va, **de qué tipo es** y lo que se sabe de ella:
 * respuesta correcta y recursos. Ningún estado, porque no existe ninguno.
 *
 * Las acciones van en dos grupos separados por un hueco mayor: a la izquierda las
 * dos que sólo mueven —subir y bajar—, a la derecha la que quita.
 *
 * **Archivar no está entre ellas: vive en el menú de desbordamiento.** Estuvo un
 * momento como papelera roja pegada a la ✕, y eso dejaba dos iconos sin etiqueta,
 * adyacentes, para «sale de este examen» y «sale de todos los exámenes». La
 * asimetría de consecuencias entre las dos es enorme y toda la protección
 * consistía en que alguien leyera el diálogo DESPUÉS de pulsar. En el menú la
 * acción aparece con su nombre y en rojo antes del clic, y la fila se queda con la
 * operación frecuente y reversible.
 */
@Composable
private fun ExamQuestionRowPanel(
    row: ExamQuestionRow,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUnlink: () -> Unit,
    onArchive: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    var menuOpen by remember(row.id) { mutableStateOf(false) }

    ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(modifier = Modifier.width(24.dp)) {
                Text(
                    text = row.position.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.facts,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.annulmentDetail?.let { reason ->
                    Text(
                        text = "Anulada: $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // El tipo, con el tono que dice si se sabe: NEUTRAL cuando resuelve,
            // UNKNOWN cuando no. Un tipo sin identificar no puede pintarse igual
            // que uno conocido, o volvería a leerse como un valor más.
            // Anulada por el tribunal en ESTE examen: etiqueta propia; la posición no cambia.
            if (row.annulled) {
                StatusDotChip(label = EXAM_QUESTION_ANNULLED_LABEL, tone = EXAM_QUESTION_ANNULLED_TONE)
            }
            StatusDotChip(
                label = row.typeLabel,
                tone = if (row.typeKnown) StatusTone.NEUTRAL else StatusTone.UNKNOWN,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    DesktopIconButton(
                        label = "Subir la pregunta ${row.position}",
                        enabled = enabled && row.canMoveUp,
                        size = Sizing.iconButtonSmall,
                        onClick = onMoveUp,
                    ) {
                        // No hay «chevron arriba» en el set y no se añade uno por
                        // esto: girar el de abajo da la misma forma con el mismo
                        // grosor de trazo, sin que el juego de iconos crezca con
                        // una variante que ya tiene.
                        AppIconImage(AppIcon.CHEVRON_DOWN, size = 14.dp, modifier = Modifier.rotate(180f))
                    }
                    DesktopIconButton(
                        label = "Bajar la pregunta ${row.position}",
                        enabled = enabled && row.canMoveDown,
                        size = Sizing.iconButtonSmall,
                        onClick = onMoveDown,
                    ) {
                        AppIconImage(AppIcon.CHEVRON_DOWN, size = 14.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    DesktopIconButton(
                        label = "Quitar la pregunta ${row.position} de este examen",
                        enabled = enabled,
                        size = Sizing.iconButtonSmall,
                        onClick = onUnlink,
                    ) {
                        AppIconImage(AppIcon.CLOSE, size = 13.dp)
                    }
                    Box {
                        DesktopIconButton(
                            label = "Más acciones sobre la pregunta ${row.position}",
                            enabled = enabled,
                            size = Sizing.iconButtonSmall,
                            onClick = { menuOpen = true },
                        ) {
                            AppIconImage(AppIcon.MORE, size = 14.dp)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Archivar del banco…",
                                        color = tokens.danger,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onArchive()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private object ExamQuestionsSizing {
    /** El mismo ancho que tendrá cuando se pueda pulsar: el bloqueo no cambia la forma. */
    val blockedButtonWidth = 168.dp
}
