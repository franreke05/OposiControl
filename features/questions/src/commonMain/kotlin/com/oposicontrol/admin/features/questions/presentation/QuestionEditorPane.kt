package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ButtonVariant
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.QuestionDetail

/**
 * Donde se trabaja: la pregunta abierta, o el alta.
 *
 * Cabecera fija arriba, secciones que desplazan en medio y barra de guardado
 * anclada abajo — la misma forma que el editor de Exámenes, por el mismo motivo:
 * un editor largo que esconde su propio botón de guardar al bajar es el motivo
 * por el que la gente pierde cambios.
 *
 * Sin selección no se deja media aplicación en blanco con una frase suelta: hay
 * un estado vacío con su icono, lo que hace el módulo y cómo empezar.
 */
@Composable
internal fun QuestionEditorPane(
    state: QuestionsState,
    onIntent: (QuestionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (questionEditorMode(state)) {
        QuestionEditorMode.EMPTY -> QuestionEditorEmpty(onIntent, modifier)
        QuestionEditorMode.NEW, QuestionEditorMode.EXISTING -> QuestionEditorContent(state, onIntent, modifier)
    }
}

/**
 * Sin selección.
 *
 * **Sin el cristal de sección alrededor.** Envolverlo dejaba una tarjeta pequeña
 * centrada dentro de una caja vacía de media pantalla: el «editor vacío» de manual.
 * La tarjeta se apoya directamente en el fondo de la página y no finge que ahí
 * hay un contenedor con contenido.
 */
@Composable
private fun QuestionEditorEmpty(onIntent: (QuestionsIntent) -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        EmptyStateCard(
            title = QUESTION_EDITOR_EMPTY_TITLE,
            message = QUESTION_EDITOR_EMPTY_MESSAGE,
            icon = AppIcon.QUESTIONS,
            action = {
                PrimaryButton(
                    text = "Nueva pregunta",
                    onClick = { onIntent(QuestionsIntent.StartCreate) },
                )
            },
        )
    }
}

@Composable
private fun QuestionEditorContent(
    state: QuestionsState,
    onIntent: (QuestionsIntent) -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    GlassSectionSurface(
        modifier = modifier.fillMaxSize(),
        shape = Radius.xl,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        QuestionEditorHeader(state, onIntent)

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    // Hueco para la barra: sin él, el pulgar se dibuja encima del
                    // borde redondeado de las secciones.
                    .padding(end = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.isCreating) {
                    QuestionEditorSections(state, onIntent, detail = null)
                } else {
                    AsyncStatePane(
                        state = state.detail,
                        onRetry = {
                            state.selectedQuestionId?.let { onIntent(QuestionsIntent.SelectQuestion(it)) }
                        },
                    ) { detail ->
                        QuestionEditorSections(state, onIntent, detail = detail)
                    }
                }
            }
            // **El editor enseña que desplaza.** Sin esto, con Respuestas ocupando
            // el pliegue, nada decía que debajo hubiera explicación, clasificación,
            // exámenes vinculados y recursos: se veían dos secciones y parecía que
            // no había más. Aparece sólo cuando hay algo debajo.
            DesktopVerticalScrollbar(
                scrollState = scroll,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }

        QuestionSaveBarRow(state, onIntent)
    }
}

/**
 * Cabecera: qué pregunta es y las acciones que no son guardar.
 *
 * Archivar va aparte y con su propio color: no es una variante de «Guardar
 * cambios». El botón dice «Archivar» y no «Eliminar» porque eso es lo que hace
 * `archiveQuestion` — marca la pregunta como inactiva, no la borra.
 */
@Composable
private fun QuestionEditorHeader(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = questionEditorTitle(state),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tokens.textPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                QuestionTypePill(state.draftType)
                StatusChip(status = questionStatusLabel(state.draftActive))
                if (state.isCreating) {
                    Text(
                        text = QUESTION_CODE_ON_CREATE,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
        if (!state.isCreating && state.selectedQuestionId != null) {
            DesktopIconButton(
                label = "Archivar pregunta",
                onClick = { onIntent(QuestionsIntent.RequestDelete) },
                variant = ButtonVariant.DANGER,
                enabled = state.archiveState !is UiAsync.Loading,
            ) { AppIconImage(AppIcon.DELETE, size = 16.dp) }
        }
    }
}

@Composable
private fun QuestionEditorSections(
    state: QuestionsState,
    onIntent: (QuestionsIntent) -> Unit,
    detail: QuestionDetail?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        QuestionContentSection(state, onIntent)
        QuestionAnswersSection(state, onIntent)
        QuestionExplanationSection(state, onIntent)
        QuestionClassificationSection(state, onIntent)
        QuestionRelationsSection(state, onIntent)
        if (questionShowsResources(state.draftType)) {
            QuestionResourcesSection(detail)
        }
    }
}

// ---------------------------------------------------------------------------
// 1 · Contenido: tipo y enunciado
// ---------------------------------------------------------------------------

@Composable
private fun QuestionContentSection(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    FormSection(
        title = "Contenido",
        description = "Cómo se sirve la pregunta y qué se lee en ella.",
        icon = AppIcon.EXAMS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                questionTypeChoices(state.draftType, locked = questionTypeLockReason(state) != null).forEach { choice ->
                    QuestionTypeCard(
                        choice = choice,
                        onClick = { onIntent(QuestionsIntent.UpdateType(choice.type)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            QuestionTextField(
                label = "Enunciado",
                value = state.draftStatement,
                onValueChange = { onIntent(QuestionsIntent.UpdateStatement(it)) },
                placeholder = "Escribe el enunciado completo de la pregunta",
                singleLine = false,
                // 96 y no 120: con cuatro respuestas debajo, cada dp del enunciado
                // empuja una sección entera bajo el pliegue. Sigue siendo un área
                // de varias líneas, que es lo que un enunciado necesita.
                minHeight = 96.dp,
                supportingText = "Es lo que lee quien responde.",
            )
        }
    }
}

/** Una tarjeta de tipo, con el mismo sistema que las de Configuración de Exámenes. */
@Composable
private fun QuestionTypeCard(
    choice: QuestionTypeChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            choice.selected -> tokens.surfaceSelected
            hovered -> tokens.surfaceHover
            else -> tokens.surfaceSolid
        },
        animationSpec = tween(140),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            choice.selected -> tokens.accent.copy(alpha = 0.55f)
            hovered -> tokens.borderStrong
            else -> tokens.border
        },
        animationSpec = tween(140),
    )

    Column(
        modifier = modifier
            .clip(Radius.card)
            .background(background)
            .border(1.dp, borderColor, Radius.card)
            .then(
                if (choice.selectable) {
                    Modifier
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics {
                selected = choice.selected
                contentDescription = questionTypeChoiceDescription(choice)
            }
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SemanticIconContainer(
                icon = questionTypeIcon(choice.type),
                role = SemanticRole.INFO,
                size = Sizing.iconButtonSmall,
                iconSize = 16.dp,
            )
            Text(
                text = choice.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (choice.selected) {
                AppIconImage(AppIcon.CHECK, size = 16.dp, tint = tokens.accentInk)
            }
        }
        Text(
            text = choice.description,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------
// 3 · Explicación y fuente
// ---------------------------------------------------------------------------

/**
 * La explicación sólo donde existe.
 *
 * Una Ráfaga no muestra razonamiento al responder, así que no se le pinta un área
 * vacía: se dice por qué y se deja la fuente, que sí es de cualquier pregunta.
 */
@Composable
private fun QuestionExplanationSection(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    FormSection(
        title = "Explicación y fuente",
        description = "La justificación que se enseña tras responder, y de dónde sale la pregunta.",
        icon = AppIcon.INFO,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            if (questionShowsExplanation(state.draftType)) {
                QuestionTextField(
                    label = "Explicación",
                    value = state.draftExplanation,
                    onValueChange = { onIntent(QuestionsIntent.UpdateExplanation(it)) },
                    placeholder = "Por qué la respuesta correcta lo es",
                    singleLine = false,
                    minHeight = 96.dp,
                )
            } else {
                Text(
                    text = "Una Ráfaga no enseña explicación al responder: es un test encadenado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
            QuestionTextField(
                label = "Fuente",
                value = state.draftSource,
                onValueChange = { onIntent(QuestionsIntent.UpdateSource(it)) },
                placeholder = "Referencia normativa, bibliográfica o documental",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Barra de guardado
// ---------------------------------------------------------------------------

/**
 * Estado del borrador y las dos acciones que lo cierran.
 *
 * Fuera del scroll a propósito, y sin mezclar aquí lo destructivo: archivar vive
 * en la cabecera.
 */
@Composable
private fun QuestionSaveBarRow(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val bar = questionSaveBar(state)
    val dotColor = when (bar.tone) {
        QuestionSaveTone.ERROR -> tokens.danger
        QuestionSaveTone.DIRTY -> tokens.warning
        QuestionSaveTone.SAVED -> tokens.success
        QuestionSaveTone.SAVING, QuestionSaveTone.CLEAN -> tokens.textSecondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(Radius.pill)
                .background(dotColor),
        )
        Text(
            text = bar.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (bar.tone == QuestionSaveTone.ERROR) tokens.dangerInk else tokens.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (bar.showDiscard) {
            GhostButton(
                text = "Descartar",
                size = ButtonSize.COMPACT,
                onClick = { onIntent(QuestionsIntent.RequestCancel) },
            )
        }
        PrimaryButton(
            text = bar.primaryLabel,
            onClick = { onIntent(QuestionsIntent.Save) },
            enabled = bar.primaryEnabled,
            loading = state.saveState is UiAsync.Loading,
        )
    }
}
