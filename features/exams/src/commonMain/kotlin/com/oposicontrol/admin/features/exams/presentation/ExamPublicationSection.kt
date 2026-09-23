package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SectionLink
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus

/**
 * Si este examen está vivo, y qué hacer para cambiarlo.
 *
 * Cuatro zonas, en el orden en que se usan:
 *
 *  1. **Estado actual** — borrador, publicado o archivado; qué significa para el
 *     catálogo de la app; y sus fechas, `publicado_en` incluida.
 *  2. **Avisos** — lo que conviene mirar ANTES de actuar, y **sólo cuando el rail
 *     no está**: es la misma lista que la tarjeta «Antes de publicar» del resumen,
 *     así que con la columna de resumen visible estaría dos veces en la misma
 *     pantalla. La decisión vive en [examPublicationShowsChecklist], con el porqué
 *     y con qué la sustituye en WIDE.
 *  3. **Acciones inmediatas** — publicar ahora, despublicar, archivar. Se aplican
 *     al momento y no pasan por «Guardar cambios»: por eso van en su propio
 *     bloque, cada una con lo que hace escrito al lado y con confirmación.
 *  4. **Resultado** — sólo cuando lo hay: en curso, hecho o el error humano. Se
 *     trae a la vista cuando aparece.
 *
 * No hay publicación programada, ni «En revisión», ni vista previa de catálogo
 * para un borrador: no existen (ver `ExamPublicationPresentation.kt`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExamPublicationSection(
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    onIntent: (ExamsIntent) -> Unit,
    onOpenAccess: () -> Unit,
    /** Si la columna de resumen está a la vista: ella ya lleva «Antes de publicar». */
    railVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val status = exam?.status
    val visibility = examVisibilitySummary(exam)
    val warnings = examPublicationWarnings(state, exam, questionCount)
    val actions = examPublicationActions(status)
    val result = examPublicationResult(state, exam)
    val busy = examPublicationBusy(state)
    // Publicar un examen sin preguntas lo rechaza el servidor (K-DEC01): con la
    // lista cargada y vacía el botón se apaga y dice por qué, en vez de ofrecer
    // una acción que sólo puede devolver un 409.
    val questionsMissing = examPublishBlockedByQuestions(state)
    val publishing = state.lifecycleActionState is UiAsync.Loading
    // La confirmación es estado de VISTA y es de este examen: al abrir otro, se cierra.
    var pending by remember(exam?.id) { mutableStateOf<ExamPublicationAction?>(null) }
    val resultRequester = remember { BringIntoViewRequester() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // -------------------------------------------------------------------
        // 1 · Estado actual
        // -------------------------------------------------------------------
        FormSection(
            title = "Estado actual",
            description = "Si el examen aparece en el catálogo de la app.",
            help = Help.estadoExamen,
            icon = AppIcon.STATUS,
            iconRole = SemanticRole.INFO,
            // Publicado no es gratuito: quien lo abre lo decide Acceso.
            action = if (status == ExamStatus.PUBLISHED) {
                { SectionLink(text = "Ver acceso", onClick = onOpenAccess) }
            } else {
                null
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    StatusChip(status = visibility.statusLabel, tone = visibility.tone)
                    Text(
                        text = visibility.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (exam != null) {
                    val facts = examPublicationFacts(exam)
                    ExamFieldRow(
                        { ExamPublicationFactCell("Publicado el", facts.publishedAt, it) },
                        { ExamPublicationFactCell("Creado", facts.createdAt, it) },
                        { ExamPublicationFactCell("Última actualización", facts.updatedAt, it) },
                    )
                }
            }
        }

        // -------------------------------------------------------------------
        // 2 · Avisos — antes de actuar, y sólo sin rail
        //
        // Con la columna de resumen a la vista esto era la MISMA lista dos veces
        // en la misma pantalla, a un palmo una de otra. Manda el rail.
        // -------------------------------------------------------------------
        if (examPublicationShowsChecklist(railVisible)) {
            FormSection(
                title = warnings.title,
                description = if (warnings.isEmpty) null else "Lo que conviene mirar antes de actuar.",
                icon = AppIcon.WARNING,
                iconRole = if (warnings.isEmpty) SemanticRole.NEUTRAL else SemanticRole.WARNING,
            ) {
                if (warnings.isEmpty) {
                    Text(
                        text = "Sin avisos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                } else {
                    ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        warnings.savedVersionNote?.let { note ->
                            ExamStatusRow(status = ExamCheckStatus.WARNING, label = "Cambios sin guardar", detail = note)
                        }
                        warnings.checks.forEach { ExamCheckRow(it) }
                        warnings.orientationNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // 3 · Acciones inmediatas
        // -------------------------------------------------------------------
        FormSection(
            title = "Acciones inmediatas",
            description = "Se aplican al momento, sin pasar por «Guardar cambios».",
            icon = AppIcon.UPLOAD,
            iconRole = SemanticRole.INFO,
        ) {
            if (actions.isEmpty()) {
                Text(
                    text = EXAM_PUBLICATION_NEEDS_SAVE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Las REVERSIBLES arriba, juntas y en una lista; la
                    // irreversible abajo, en su propio bloque. Ver el porqué del
                    // corte justo debajo de este `forEach`.
                    actions.filter { it != ExamPublicationAction.DELETE }.forEach { action ->
                        val copy = examPublicationActionCopy(action, status, questionsMissing)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            // Mismo ancho para los tres: la explicación de cada uno
                            // empieza en la misma columna y se lee como una lista.
                            val buttonModifier = Modifier.width(ExamPublicationSizing.actionButtonWidth)
                            when (action) {
                                ExamPublicationAction.PUBLISH -> PrimaryButton(
                                    text = copy.label,
                                    onClick = { pending = action },
                                    modifier = buttonModifier,
                                    enabled = !busy && !questionsMissing,
                                    loading = publishing,
                                )
                                ExamPublicationAction.UNPUBLISH -> SecondaryButton(
                                    text = copy.label,
                                    onClick = { pending = action },
                                    modifier = buttonModifier,
                                    enabled = !busy,
                                    loading = publishing,
                                )
                                // Archivar confirma con el diálogo del ViewModel, que
                                // es el que ya tenía su mensaje.
                                ExamPublicationAction.ARCHIVE -> DangerOutlineButton(
                                    text = copy.label,
                                    onClick = { onIntent(ExamsIntent.RequestArchiveSelectedExam) },
                                    modifier = buttonModifier,
                                    enabled = !busy,
                                )
                                // Vive en su propio bloque, fuera de esta lista.
                                ExamPublicationAction.DELETE -> Unit
                            }
                            Text(
                                text = copy.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        text = EXAM_NO_SCHEDULED_PUBLICATION,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }
        }

        // -------------------------------------------------------------------
        // 3 bis · Eliminar definitivamente — sección aparte, y a propósito
        //
        // Archivar y borrar son las dos «destructivas» y las dos se pintan con
        // `DangerOutlineButton`, así que lo único que las distingue dentro de una
        // lista es su etiqueta. No basta: pulsar el de abajo cuando querías el de
        // arriba es un error de un centímetro, y aquí uno de los dos no tiene
        // vuelta atrás.
        //
        // Separarlo en su propia `FormSection` hace tres cosas que un botón más en
        // la lista no hace: le da un TÍTULO que dice qué clase de acción es, lo
        // saca del alcance del gesto que recorre las acciones normales, y le da
        // sitio al aviso del rechazo justo debajo — que es donde se lee como
        // consecuencia de lo que acabas de pulsar.
        //
        // Sigue siendo contorno y no relleno: un botón sólido rojo pesa más que
        // «Publicar», y la acción principal de esta pantalla es publicar.
        // -------------------------------------------------------------------
        if (actions.contains(ExamPublicationAction.DELETE)) {
            val deleteCopy = examPublicationActionCopy(ExamPublicationAction.DELETE, status)
            val deletion = examDeletionNotice(state)
            FormSection(
                // El título NO repite la etiqueta del botón. Además de redundante
                // —dos veces «Eliminar definitivamente» a dos centímetros—, dejaba
                // dos nodos con el mismo texto y ningún test de UI podía apuntar
                // al botón sin ambigüedad.
                title = "Borrado definitivo",
                description = "No se puede deshacer. Para retirar el examen sin perderlo, archívalo.",
                icon = AppIcon.WARNING,
                iconRole = SemanticRole.DANGER,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        DangerOutlineButton(
                            text = deleteCopy.label,
                            onClick = { onIntent(ExamsIntent.RequestDeleteSelectedExam) },
                            modifier = Modifier.width(ExamPublicationSizing.deleteButtonWidth),
                            enabled = !busy && state.examDeleteState !is UiAsync.Loading,
                        )
                        Text(
                            text = deleteCopy.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // El aviso del rechazo: aquí y no al pie del formulario. Ver
                    // `examDeletionNotice` para por qué no es un error de campo.
                    deletion?.let { aviso ->
                        ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            ExamStatusRow(
                                status = examDeletionNoticeStatus(aviso.kind),
                                label = aviso.title,
                                detail = aviso.detail,
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // 4 · Resultado — sólo cuando lo hay
        //
        // Va después de las acciones porque es su consecuencia, y eso lo deja
        // bajo el pliegue en 1920×1080: un fallo al publicar no se veía — el botón
        // dejaba de girar y nada más cambiaba—. Cuando aparece o cambia, se trae
        // a la vista dentro del scroll del formulario (o de la página, apilado).
        // -------------------------------------------------------------------
        result?.let { outcome ->
            val (icon, role) = examPublicationResultIcon(outcome.kind)
            LaunchedEffect(outcome) {
                // Un frame de espera: el efecto arranca antes de que la sección esté
                // colocada, y pedir que se vea algo sin coordenadas no hace nada.
                androidx.compose.runtime.withFrameNanos { }
                resultRequester.bringIntoView()
            }
            FormSection(
                title = "Resultado",
                icon = icon,
                iconRole = role,
                modifier = Modifier.bringIntoViewRequester(resultRequester),
            ) {
                ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ExamStatusRow(
                        status = examPublicationResultStatus(outcome.kind),
                        label = outcome.title,
                        detail = outcome.detail,
                    )
                }
            }
        }
    }

    pending?.let { action ->
        examPublicationConfirmation(action, state, exam, questionCount)?.let { confirmation ->
            ConfirmDialog(
                title = confirmation.title,
                message = confirmation.message,
                confirmLabel = confirmation.confirmLabel,
                // Publicar y despublicar se deshacen con la acción contraria: no
                // se pintan en rojo como lo que no se puede deshacer.
                destructive = false,
                onConfirm = {
                    pending = null
                    onIntent(
                        if (action == ExamPublicationAction.UNPUBLISH) ExamsIntent.UnpublishExam else ExamsIntent.PublishExam,
                    )
                },
                onDismiss = { pending = null },
            )
        }
    }
}

/** Una fecha del examen: etiqueta y valor, con el valor pintado según su procedencia. */
@Composable
private fun ExamPublicationFactCell(label: String, value: ExamFieldLabel, modifier: Modifier) {
    val tokens = GlassTheme.tokens
    val style = examFieldValueStyle(value.origin, tokens)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        Text(
            text = value.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = style.weight,
            color = style.color,
        )
    }
}

private object ExamPublicationSizing {
    /** Cabe «Publicar ahora» con su indicador de carga sin cambiar de ancho. */
    val actionButtonWidth: Dp = 168.dp

    /**
     * «Eliminar definitivamente» necesita más, y es DISTINTO al de los otros a
     * propósito: el botón irreversible no se alinea con la columna de los
     * reversibles, así que no se puede pulsar por costumbre de posición.
     */
    val deleteButtonWidth: Dp = 216.dp
}
