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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun ImportCard(
    modifier: Modifier = Modifier,
    strokeColor: Color = GlassTheme.tokens.border,
    content: @Composable () -> Unit,
) {
    val tokens = GlassTheme.tokens
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tint = tokens.surfaceSolid,
        strokeColor = strokeColor,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun ImportSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = GlassTheme.tokens.textPrimary)
}

@Composable
fun ImportTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 40.dp,
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
fun <T> ImportDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceMuted)
                    .border(1.dp, tokens.border, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) tokens.textPrimary else tokens.textSecondary,
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

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, tint: Color = GlassTheme.tokens.textPrimary) {
    val tokens = GlassTheme.tokens
    ImportCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tint)
            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        }
    }
}

@Composable
fun FilterChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit, tone: Color) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) tone.copy(alpha = 0.14f) else tokens.surfaceMuted)
            .border(1.dp, if (selected) tone.copy(alpha = 0.5f) else tokens.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) tone else tokens.textSecondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (count != null) {
            Text("$count", style = MaterialTheme.typography.labelSmall, color = if (selected) tone else tokens.textSecondary)
        }
    }
}

@Composable
fun WizardStep(number: Int, title: String, subtitle: String, state: WizardStepState, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val (bg, fg) = when (state) {
        WizardStepState.COMPLETED -> tokens.success.copy(alpha = 0.15f) to tokens.success
        WizardStepState.ACTIVE -> tokens.accent.copy(alpha = 0.15f) to tokens.accentInk
        WizardStepState.ERROR -> tokens.danger.copy(alpha = 0.15f) to tokens.danger
        WizardStepState.PENDING -> tokens.surfaceMuted to tokens.textSecondary
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                .clip(RoundedCornerShape(50))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (state == WizardStepState.COMPLETED) {
                AppIconImage(AppIcon.CHECK, size = 13.dp, tint = fg)
            } else {
                Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
        }
    }
}

enum class WizardStepState { PENDING, ACTIVE, COMPLETED, ERROR }
