package com.oposicontrol.admin.features.imports.presentation

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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

/**
 * Controles propios de Importación.
 *
 * Aquí quedaron sólo los que usa el workspace. `ImportCard`, `MetricTile`,
 * `ImportSectionTitle`, `WizardStep` y `WizardStepState` se fueron con la pantalla
 * anterior: las secciones ahora son `FormSection` y `GlassSectionSurface` —los
 * mismos que Exámenes y Preguntas— y las etapas las pinta [ImportStageBar].
 *
 * Todos leen `GlassTheme.tokens`: no hay ni un color escrito a mano.
 */

@Composable
internal fun ImportTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = Sizing.inputHeight,
    enabled: Boolean = true,
    label: String? = null,
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
    }
}

@Composable
internal fun <T> ImportDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (label.isNotBlank()) FieldLabel(label = label)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Sizing.inputHeight)
                    .clip(Radius.md)
                    .background(if (enabled) tokens.surfaceMuted else tokens.surfaceDisabled)
                    .border(1.dp, if (enabled) tokens.fieldBorder else tokens.borderDisabled, Radius.md)
                    .then(
                        if (enabled) {
                            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { expanded = true }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = "$label: $selectedLabel" }
                    .padding(horizontal = Spacing.sm, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) tokens.textPrimary else tokens.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AppIconImage(AppIcon.CHEVRON_DOWN, size = 12.dp, tint = tokens.textSecondary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/** Píldora de filtro con su recuento. El estado va en texto y en marca, no sólo en color. */
@Composable
internal fun FilterChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    tone: Color,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .clip(Radius.pill)
            .background(if (selected) tokens.surfaceSelected else tokens.surfaceMuted)
            .border(
                width = 1.dp,
                color = if (selected) tone.copy(alpha = 0.55f) else tokens.border,
                shape = Radius.pill,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            AppIconImage(AppIcon.CHECK, size = 11.dp, tint = tone)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tokens.textPrimary,
        )
        if (count != null) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
        }
    }
}
