package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing

@Composable
fun QuestionsScreen(viewModel: QuestionsViewModel) {
    val state by viewModel.state.collectAsState()
    QuestionsContent(state = state, onIntent = { viewModel.dispatch(it) })
}

/**
 * Preguntas: banco a la izquierda, editor a la derecha.
 *
 * Entra por estado y no por ViewModel a propósito: así el banco de pruebas visual
 * puede renderizar la pantalla real contra un `QuestionsState` fabricado, sin
 * levantar Koin ni Ktor. Es la misma separación que usa el workspace de Exámenes.
 *
 * **Por qué no usa `CrudSplitLayout`.** Aquel reparte 32% de lista en WIDE, y aquí
 * la columna izquierda no es un índice: lleva búsqueda, tres filtros, recuento,
 * orden, la lista y su paginación. Con 32% el enunciado de cada fila se cortaba a
 * media palabra. [QuestionsSizing] la deja en 36%, que es lo que pide su contenido
 * sin quitarle sitio al editor, y ancla los dos extremos para que ni en 1366 se
 * quede corta ni en 2560 se estire sola.
 *
 * Por debajo de 1100dp —donde el escritorio ya no sostiene dos columnas— se apila,
 * igual que el resto del panel.
 */
@Composable
internal fun QuestionsContent(
    state: QuestionsState,
    onIntent: (QuestionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Umbral sobre el ancho de CONTENIDO, no sobre el de la ventana.
        //
        // `desktopBreakpointFor` está calibrado para la ventana (COMPACT por
        // debajo de 1100dp), y aquí lo que se mide es lo que queda DENTRO del
        // shell: a 1366 el contenido —ventana menos sidebar menos márgenes— mide
        // ~1080dp y caía en COMPACT, así que la pantalla se apilaba justo en el
        // portátil más común. Con el umbral propio, 1366 conserva sus dos
        // columnas y sólo apila cuando de verdad no caben.
        if (maxWidth >= QuestionsSizing.twoColumnMinWidth) {
            val bankWidth = (maxWidth * QuestionsSizing.BANK_FRACTION)
                .coerceIn(QuestionsSizing.bankMinWidth, QuestionsSizing.bankMaxWidth)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                QuestionBankPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier.width(bankWidth).fillMaxSize(),
                )
                QuestionEditorPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        } else {
            // Apilado: el banco cede altura para que el editor empiece a verse sin
            // desplazar la página entera.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                QuestionBankPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier.fillMaxWidth().heightIn(max = QuestionsSizing.stackedBankMaxHeight),
                )
                QuestionEditorPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = QuestionsSizing.stackedEditorMinHeight),
                )
            }
        }
    }

    if (state.showCancelConfirm) {
        ConfirmDialog(
            title = "Descartar cambios",
            message = "Los cambios sin guardar de esta pregunta se perderán.",
            confirmLabel = "Descartar",
            onConfirm = { onIntent(QuestionsIntent.ConfirmCancel) },
            onDismiss = { onIntent(QuestionsIntent.DismissCancelConfirm) },
        )
    }
    if (state.showDeleteConfirm) {
        ConfirmDialog(
            // `archiveQuestion` marca `activa = false`: archiva, no borra. El
            // diálogo anterior decía «Eliminar pregunta» para una operación que no
            // elimina nada, y después que «puede seguir apareciendo en los
            // exámenes», que es al revés (KQ0-7).
            title = "Archivar pregunta",
            message = QUESTION_ARCHIVE_WARNING,
            confirmLabel = "Archivar",
            onConfirm = { onIntent(QuestionsIntent.ConfirmDelete) },
            onDismiss = { onIntent(QuestionsIntent.DismissDeleteConfirm) },
        )
    }
    if (state.showAssociateDialog) {
        QuestionLinkExamDialog(state, onIntent)
    }
    state.unlinkExamId?.let { examId ->
        val link = state.knownExamLinks().firstOrNull { it.examId == examId }
        ConfirmDialog(
            title = "Quitar de ${link?.examCode?.ifBlank { null } ?: "este examen"}",
            message = if (link?.isPublished == true) {
                "$QUESTION_UNLINK_WARNING $QUESTION_UNLINK_PUBLISHED_WARNING"
            } else {
                QUESTION_UNLINK_WARNING
            },
            confirmLabel = "Quitar",
            onConfirm = { onIntent(QuestionsIntent.ConfirmUnlinkExam) },
            onDismiss = { onIntent(QuestionsIntent.DismissUnlinkConfirm) },
        )
    }
}

/**
 * Vincular a un examen: sólo se ofrecen los exámenes que la base aceptaría para
 * ESTA pregunta —su tipo guardado y su especialidad— con su estado a la vista
 * (KQ0-5). Va por la puerta canónica (`POST /preguntas/{id}/examenes`, M4), nunca
 * por el PATCH de la pregunta.
 */
@Composable
private fun QuestionLinkExamDialog(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val saved = (state.detail as? UiAsync.Success)?.value
    val examsView = questionLinkableExamsView(state.exams, saved?.type, saved?.specialtyId)
    val linkable = (examsView as? QuestionLinkableExamsView.Options)?.options.orEmpty()
    Dialog(onDismissRequest = { onIntent(QuestionsIntent.CancelAssociateDialog) }) {
        GlassSurface(
            modifier = Modifier.width(400.dp),
            shape = Radius.lg,
            tint = tokens.surfaceSolid,
            strokeColor = tokens.border,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = "Vincular a examen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                // «Todavía no hay catálogo» (cargando/fallo) y «no hay candidatos»
                // (catálogo real, cero compatibles) son hechos distintos: antes los
                // tres casos caían en el mismo selector vacío, indistinguible de un
                // fallo silencioso.
                when (examsView) {
                    QuestionLinkableExamsView.Loading -> Text(
                        text = "Cargando exámenes…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                    )
                    QuestionLinkableExamsView.Unavailable -> Text(
                        text = QUESTION_LINKABLE_EXAMS_UNAVAILABLE_NOTE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.dangerInk,
                    )
                    QuestionLinkableExamsView.NoCandidates -> Text(
                        text = QUESTION_LINKABLE_EXAMS_EMPTY_NOTE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textPrimary,
                    )
                    is QuestionLinkableExamsView.Options -> QuestionSelect(
                        selectedLabel = linkable.firstOrNull { it.value == state.associateExamId }?.label
                            ?: "Elige un examen",
                        options = linkable,
                        onSelect = { onIntent(QuestionsIntent.UpdateAssociateExamId(it)) },
                        accessibilityLabel = "Examen al que vincular",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                QuestionTextField(
                    label = "Posición",
                    value = state.associatePosition,
                    onValueChange = { value ->
                        onIntent(QuestionsIntent.UpdateAssociatePosition(value.filter { it.isDigit() }))
                    },
                    placeholder = "Al final",
                    supportingText = "Vacía = al final del examen. Si la fijas, las demás se recolocan.",
                )
                if (state.saveState is UiAsync.Error) {
                    Text(
                        text = (state.saveState as UiAsync.Error).error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.dangerInk,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                ) {
                    SecondaryButton(
                        text = "Cancelar",
                        onClick = { onIntent(QuestionsIntent.CancelAssociateDialog) },
                    )
                    PrimaryButton(
                        text = "Vincular",
                        onClick = { onIntent(QuestionsIntent.ConfirmAssociateExam) },
                        enabled = linkable.isNotEmpty(),
                        loading = state.saveState is UiAsync.Loading,
                    )
                }
            }
        }
    }
}

private object QuestionsSizing {
    /**
     * Ancho de CONTENIDO a partir del cual caben banco y editor en paralelo.
     *
     * **1000, medido.** A 1920 el contenido da ~1612dp; a 1366, ~1080; a 1080 con
     * la sidebar plegada, ~972. Con 1000 el portátil de 1366 conserva las dos
     * columnas —banco ~389dp, editor ~675— y 1080 apila, que es donde de verdad
     * no caben: un editor de 350dp no sirve para escribir un enunciado.
     */
    val twoColumnMinWidth: Dp = 1000.dp

    /**
     * Qué parte del ancho se lleva el banco.
     *
     * **0,36.** La columna no es un índice: búsqueda + tres filtros + recuento +
     * orden + lista + paginación. Con el 0,32 de `CrudSplitLayout` el enunciado de
     * cada fila se partía; con 0,36 a 1920 mide ~600dp y entra en dos líneas.
     */
    const val BANK_FRACTION: Float = 0.36f

    /** Ni más estrecha que su fila de filtros a 1366, ni estirada a lo tonto en 2560. */
    val bankMinWidth: Dp = 360.dp
    val bankMaxWidth: Dp = 620.dp

    /** Apilado, el banco cede altura para que el editor empiece a verse. */
    val stackedBankMaxHeight: Dp = 460.dp

    /** Y el editor pide la suya: apilado, un editor de 200px no sirve para trabajar. */
    val stackedEditorMinHeight: Dp = 560.dp
}
