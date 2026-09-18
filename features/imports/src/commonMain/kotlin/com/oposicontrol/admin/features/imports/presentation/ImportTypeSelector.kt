package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ContentExamType

/**
 * Etapa 1: qué se está importando.
 *
 * Tres tarjetas con el mismo sistema que el selector de tipo de Exámenes y
 * Preguntas. Cada una dice lo que admite y lo que no **en la importación real**
 * ([importTypeRule]), no lo que el tipo de pregunta admite en abstracto: por eso
 * Inmersiva aparece sin recursos, que es la verdad del contrato de hoy.
 *
 * La tarjeta no lleva dentro ni el selector de examen ni el botón de subir. Eso
 * era lo que hacía la pantalla anterior, y convertía la decisión «qué tipo» en un
 * formulario de tres columnas donde sólo una estaba viva.
 */
@Composable
internal fun ImportTypeSelector(
    state: ImportState,
    onIntent: (ImportIntent) -> Unit,
    modifier: Modifier = Modifier,
    // 230 y no 260: con el rail de 340dp, a 1366 la columna de flujo mide ~724dp,
    // y con 260 las tres tarjetas no entraban (pedían 780) — se apilaban justo en
    // el portátil más común, y sólo se veían dos. Comparar los tres contratos de
    // un vistazo es el propósito entero de esta sección.
    cardMinWidth: Dp = 230.dp,
    availableWidth: Dp = 900.dp,
) {
    val choices = importTypeChoices(state.selectedType)
    val threeUp = availableWidth >= cardMinWidth * 3

    FormSection(
        title = "Tipo de importación",
        description = "Cada tipo tiene su propio contrato: lo que admite y lo que no.",
        modifier = modifier,
        icon = AppIcon.LAYERS,
        iconRole = SemanticRole.INFO,
    ) {
        if (threeUp) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                choices.forEach { choice ->
                    ImportTypeCard(
                        choice = choice,
                        onClick = { onIntent(ImportIntent.SelectType(choice.type)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                choices.forEach { choice ->
                    ImportTypeCard(
                        choice = choice,
                        onClick = { onIntent(ImportIntent.SelectType(choice.type)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportTypeCard(
    choice: ImportTypeChoice,
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
                contentDescription = importTypeChoiceDescription(choice)
            }
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SemanticIconContainer(
                icon = importTypeIcon(choice.type),
                role = SemanticRole.INFO,
                size = Sizing.iconButtonSmall,
                iconSize = 16.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = choice.rule.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = choice.rule.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
            if (choice.selected) {
                AppIconImage(AppIcon.CHECK, size = 16.dp, tint = tokens.accentInk)
            }
        }

        // Admite / no admite, con marca Y palabra: la restricción no se lee por color.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            choice.rule.admits.forEach { item ->
                ImportRuleLine(text = item, allowed = true)
            }
            choice.rule.rejects.forEach { item ->
                ImportRuleLine(text = item, allowed = false)
            }
        }
    }
}

@Composable
private fun ImportRuleLine(text: String, allowed: Boolean) {
    val tokens = GlassTheme.tokens
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        AppIconImage(
            icon = if (allowed) AppIcon.CHECK else AppIcon.CLOSE,
            size = 11.dp,
            tint = if (allowed) tokens.success else tokens.textSecondary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (allowed) tokens.textPrimary else tokens.textSecondary,
        )
    }
}

internal fun importTypeIcon(type: ContentExamType): AppIcon = when (type) {
    ContentExamType.RAFAGA -> AppIcon.QUESTIONS
    ContentExamType.RAZONADO -> AppIcon.NEWS_TYPES
    ContentExamType.INMERSIVO -> AppIcon.RESOURCES
}
