package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Etiquetas (temas) de la pregunta: la selección como chips con «quitar», un
 * buscador que propone las activas del catálogo, «Crear etiqueta» y un guardado
 * PROPIO (`PUT /preguntas/{id}/etiquetas`), separado del de la pregunta igual que
 * los vínculos con exámenes.
 *
 * Sólo en una pregunta ya guardada: sin id no hay a qué colgarlas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionTagsBlock(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "Etiquetas (temas)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
        if (state.isCreating || state.selectedQuestionId.isNullOrBlank()) {
            Text(
                text = QUESTION_TAGS_NEED_SAVED_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            return@Column
        }
        when (val saved = state.questionTags) {
            UiAsync.Idle, UiAsync.Loading -> Text(
                text = "Consultando las etiquetas de la pregunta…",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            is UiAsync.Error -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = QUESTION_TAGS_UNAVAILABLE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.dangerInk,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Reintentar",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(QuestionsIntent.ReloadQuestionTags) },
                )
            }
            UiAsync.Empty, is UiAsync.Success -> QuestionTagsEditor(state, onIntent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionTagsEditor(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val chips = state.selectedTagChips()
    if (chips.isEmpty()) {
        Text(
            text = QUESTION_TAGS_EMPTY_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(QuestionTagsSizing.chipGap),
            verticalArrangement = Arrangement.spacedBy(QuestionTagsSizing.chipGap),
        ) {
            chips.forEach { chip ->
                QuestionSelectedTagChip(chip, onRemove = { onIntent(QuestionsIntent.RemoveQuestionTag(chip.id)) })
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        QuestionTextField(
            value = state.tagSearch,
            onValueChange = { onIntent(QuestionsIntent.UpdateTagSearch(it)) },
            placeholder = "Buscar etiqueta para añadir",
            modifier = Modifier.width(QuestionTagsSizing.searchWidth),
        )
        SecondaryButton(
            text = "Crear etiqueta",
            size = ButtonSize.COMPACT,
            onClick = { onIntent(QuestionsIntent.RequestCreateTag) },
            icon = { AppIconImage(AppIcon.ADD, size = 13.dp) },
        )
    }

    when (state.tagCatalog) {
        UiAsync.Idle, UiAsync.Loading -> Text(
            text = "Cargando el catálogo de etiquetas…",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        is UiAsync.Error -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = QUESTION_TAG_CATALOG_UNAVAILABLE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.dangerInk,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "Reintentar",
                size = ButtonSize.COMPACT,
                onClick = { onIntent(QuestionsIntent.ReloadQuestionTags) },
            )
        }
        UiAsync.Empty, is UiAsync.Success -> {
            val addable = state.addableTags()
            if (addable.isEmpty()) {
                Text(
                    text = if (state.tagSearch.isBlank()) QUESTION_TAGS_NOTHING_TO_ADD_NOTE else QUESTION_TAGS_NO_MATCH_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QuestionTagsSizing.chipGap),
                    verticalArrangement = Arrangement.spacedBy(QuestionTagsSizing.chipGap),
                ) {
                    addable.take(QUESTION_TAG_SUGGESTIONS_MAX).forEach { tag ->
                        QuestionSuggestedTagChip(name = tag.name, onAdd = { onIntent(QuestionsIntent.AddQuestionTag(tag.id)) })
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val (message, isError) = questionTagsSaveMessage(state)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) tokens.dangerInk else tokens.textSecondary,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = "Guardar etiquetas",
            size = ButtonSize.COMPACT,
            onClick = { onIntent(QuestionsIntent.SaveQuestionTags) },
            enabled = state.tagsDirty() && state.tagsSaveState !is UiAsync.Loading,
            loading = state.tagsSaveState is UiAsync.Loading,
        )
    }
}

/** Una etiqueta elegida: nombre y «×» para quitarla. Una desactivada se dice. */
@Composable
private fun QuestionSelectedTagChip(chip: QuestionTagChip, onRemove: () -> Unit) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .height(QuestionTagsSizing.chipHeight)
            .widthIn(max = QuestionTagsSizing.chipMaxWidth)
            .clip(Radius.pill)
            .background(tokens.surfaceSelected)
            .border(1.dp, tokens.accent.copy(alpha = 0.45f), Radius.pill)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (chip.active) chip.name else "${chip.name} (desactivada)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (chip.active) tokens.textPrimary else tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .size(QuestionTagsSizing.removeTarget)
                .clip(Radius.pill)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Quitar etiqueta ${chip.name}" },
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(AppIcon.CLOSE, size = 10.dp, tint = tokens.textSecondary)
        }
    }
}

/** Una etiqueta del catálogo que se puede añadir con un clic. */
@Composable
private fun QuestionSuggestedTagChip(name: String, onAdd: () -> Unit) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .height(QuestionTagsSizing.chipHeight)
            .widthIn(max = QuestionTagsSizing.chipMaxWidth)
            .clip(Radius.pill)
            .background(tokens.surfaceMuted)
            .border(1.dp, tokens.border, Radius.pill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onAdd)
            .semantics { contentDescription = "Añadir etiqueta $name" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIconImage(AppIcon.ADD, size = 10.dp, tint = tokens.textSecondary)
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private object QuestionTagsSizing {
    val chipHeight = 28.dp
    val chipMaxWidth = 240.dp
    val chipGap = 6.dp
    val removeTarget = 20.dp
    val searchWidth = 280.dp
}

/** Cuántas sugerencias del catálogo se enseñan a la vez; el buscador acota el resto. */
const val QUESTION_TAG_SUGGESTIONS_MAX: Int = 12

const val QUESTION_TAGS_NEED_SAVED_NOTE: String = "Guarda la pregunta para poder etiquetarla."
const val QUESTION_TAGS_EMPTY_NOTE: String = "Esta pregunta no tiene etiquetas."
const val QUESTION_TAGS_UNAVAILABLE_NOTE: String =
    "No se han podido consultar las etiquetas de esta pregunta. Vuelve a intentarlo."
const val QUESTION_TAG_CATALOG_UNAVAILABLE_NOTE: String =
    "No se ha podido cargar el catálogo de etiquetas."
const val QUESTION_TAGS_NOTHING_TO_ADD_NOTE: String = "No quedan etiquetas activas por añadir. Puedes crear una."
const val QUESTION_TAGS_NO_MATCH_NOTE: String = "Ninguna etiqueta coincide. Puedes crearla con «Crear etiqueta»."

/** El texto junto a «Guardar etiquetas» y si es un error. */
fun questionTagsSaveMessage(state: QuestionsState): Pair<String, Boolean> = when (val save = state.tagsSaveState) {
    is UiAsync.Error -> save.error.humanMessage() to true
    UiAsync.Loading -> "Guardando etiquetas…" to false
    else -> when {
        state.tagsDirty() -> "Cambios de etiquetas sin guardar." to false
        save is UiAsync.Success -> "Etiquetas guardadas." to false
        else -> "${state.draftTagIds.size} de $QUESTION_TAGS_MAX etiquetas." to false
    }
}
