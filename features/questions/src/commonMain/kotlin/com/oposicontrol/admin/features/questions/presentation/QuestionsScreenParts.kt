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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionType

/** Card opaca y plana — deliberadamente NO usa el tinte glass por defecto de [GlassSurface]. */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = GlassTheme.tokens
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tint = tokens.surfaceSolid,
        strokeColor = tokens.border,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun QuestionSectionCard(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    FlatCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$number. $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassTheme.tokens.textPrimary,
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 40.dp,
    enabled: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        singleLine = singleLine,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.accent),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (enabled) tokens.surfaceMuted else tokens.surfaceMuted.copy(alpha = 0.5f))
                    .border(1.dp, tokens.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = if (singleLine) 9.dp else 10.dp),
            ) {
                if (value.isBlank()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
                }
                inner()
            }
        },
    )
}

@Composable
fun FlatSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Buscar",
) {
    val tokens = GlassTheme.tokens
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.accent),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIconImage(icon = AppIcon.SEARCH, size = 15.dp, tint = tokens.textSecondary)
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

/** "Todos ▼" style select — opción simple, sin ExposedDropdownMenuBox. */
@Composable
fun <T> FlatDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.border, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AppIconImage(icon = AppIcon.CHEVRON_DOWN, size = 12.dp, tint = tokens.textSecondary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(text = { Text(optionLabel) }, onClick = { expanded = false; onSelect(value) })
                }
            }
        }
    }
}

/** Pastilla de tipo — color distinto por tipo canónico, honesto cuando el tipo no llegó (`null`). */
@Composable
fun QuestionTypeBadge(type: QuestionType?, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val (bg, fg, label) = when (type) {
        QuestionType.RAZONADA -> Triple(Color(0xFFE3EAFC), Color(0xFF2E4FA3), "Razonada")
        QuestionType.INMERSIVA -> Triple(Color(0xFFDDF3F1), Color(0xFF1E7A70), "Inmersiva")
        QuestionType.RAFAGA -> Triple(Color(0xFFEFE6FB), Color(0xFF6B3FA0), "Ráfaga")
        null -> Triple(tokens.surfaceMuted, tokens.textSecondary, "Tipo desconocido")
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
fun TemaBadge(modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    Box(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(tokens.surfaceMuted).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("Sin tema", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
    }
}

@Composable
fun OptionEditorCard(
    option: QuestionOption,
    onContentChange: (String) -> Unit,
    onSelectCorrect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (option.isCorrect) tokens.success.copy(alpha = 0.08f) else tokens.surfaceMuted)
            .border(1.dp, if (option.isCorrect) tokens.success.copy(alpha = 0.5f) else tokens.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioButton(
                selected = option.isCorrect,
                onClick = onSelectCorrect,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = tokens.success),
            )
            Text(
                text = "${option.label}. Respuesta ${option.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
            )
        }
        FlatTextField(
            value = option.content,
            onValueChange = onContentChange,
            placeholder = "Contenido de la opción ${option.label}",
            singleLine = false,
            minHeight = 44.dp,
            enabled = enabled,
        )
    }
}
