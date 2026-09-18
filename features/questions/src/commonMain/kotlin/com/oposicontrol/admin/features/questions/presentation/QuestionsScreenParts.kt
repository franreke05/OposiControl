package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.FieldLabel
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.QuestionType

/**
 * Controles propios de Preguntas.
 *
 * Todos leen `GlassTheme.tokens`: no queda ni un color escrito a mano. La pastilla
 * de tipo anterior traía seis colores hexadecimales fijos —`0xFFE3EAFC`,
 * `0xFF2E4FA3`…— que en el tema oscuro pintaban texto azul marino sobre un fondo
 * azul claro, porque no dependían del tema. Y `TemaBadge` decía «Sin tema»
 * siempre, para un campo que el contrato no tiene: las dos se han retirado.
 */

/** El icono de cada tipo canónico. La palabra va siempre al lado: el tipo no se lee por color. */
internal fun questionTypeIcon(type: QuestionType?): AppIcon = when (type) {
    QuestionType.RAFAGA -> AppIcon.QUESTIONS
    QuestionType.RAZONADA -> AppIcon.NEWS_TYPES
    QuestionType.INMERSIVA -> AppIcon.RESOURCES
    null -> AppIcon.HELP
}

/**
 * Pastilla de tipo: icono y palabra, sobre superficie neutra.
 *
 * Sin paleta por tipo a propósito. Inventar tres colores nuevos para Ráfaga,
 * Razonada e Inmersiva obliga a defender su contraste en los dos temas y no
 * añade nada que la palabra no diga ya.
 */
@Composable
internal fun QuestionTypePill(type: QuestionType?, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier
            .clip(Radius.pill)
            .background(tokens.surfaceMuted)
            .border(1.dp, tokens.border, Radius.pill)
            .padding(horizontal = Spacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        AppIconImage(questionTypeIcon(type), size = 12.dp, tint = tokens.textSecondary)
        Text(
            text = questionTypeLabel(type),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )
    }
}

/** Buscador del banco. Es el único de esta pantalla. */
@Composable
internal fun QuestionSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = QUESTION_SEARCH_PLACEHOLDER,
) {
    val tokens = GlassTheme.tokens
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = Sizing.inputHeight),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.accent),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radius.md)
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.fieldBorder, Radius.md)
                    .padding(horizontal = Spacing.sm, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AppIconImage(AppIcon.SEARCH, size = 15.dp, tint = tokens.textSecondary)
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isBlank()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
                    }
                    inner()
                }
            }
        },
    )
}

/** Campo de texto opaco. Con [minHeight] alto sirve de área multilínea. */
@Composable
internal fun QuestionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = Sizing.inputHeight,
    enabled: Boolean = true,
    label: String? = null,
    supportingText: String? = null,
) {
    val tokens = GlassTheme.tokens
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (label != null) FieldLabel(label = label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight),
            singleLine = singleLine,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
            cursorBrush = SolidColor(tokens.accent),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radius.md)
                        .background(if (enabled) tokens.surfaceMuted else tokens.surfaceDisabled)
                        .border(1.dp, if (enabled) tokens.fieldBorder else tokens.borderDisabled, Radius.md)
                        .padding(horizontal = Spacing.sm, vertical = if (singleLine) 9.dp else 10.dp),
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) tokens.textSecondary else tokens.textDisabled,
                        )
                    }
                    inner()
                }
            },
        )
        if (supportingText != null) {
            Text(text = supportingText, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
    }
}

/**
 * Selector compacto: la etiqueta va DENTRO, no encima.
 *
 * Los filtros del banco eran cuatro columnas con su rótulo encima, y entre los
 * cuatro se llevaban media columna de alto. Con la etiqueta dentro ocupan una
 * fila y siguen diciendo qué son.
 */
@Composable
internal fun <T> QuestionSelect(
    selectedLabel: String,
    options: List<QuestionFilterOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String,
    enabled: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Sizing.buttonCompact)
                .clip(Radius.md)
                .background(if (enabled) tokens.surfaceMuted else tokens.surfaceDisabled)
                .border(1.dp, if (enabled) tokens.border else tokens.borderDisabled, Radius.md)
                .then(
                    if (enabled) {
                        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { expanded = true }
                    } else {
                        Modifier
                    },
                )
                .semantics { contentDescription = "$accessibilityLabel: $selectedLabel" }
                .padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) tokens.textPrimary else tokens.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AppIconImage(AppIcon.CHEVRON_DOWN, size = 12.dp, tint = tokens.textSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}
