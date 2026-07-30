package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.UiSpacing

@Composable
fun ScreenHeader(
    title: String,
    description: String,
    environmentLabel: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpacing.blockLarge, vertical = UiSpacing.block),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                ScreenHeaderText(
                    title = title,
                    description = description,
                    environmentLabel = environmentLabel,
                )
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    GlassActionButton(
                        text = primaryActionLabel,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onPrimaryAction,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpacing.blockLarge, vertical = UiSpacing.block),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenHeaderText(
                    title = title,
                    description = description,
                    environmentLabel = environmentLabel,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    GlassActionButton(
                        text = primaryActionLabel,
                        modifier = Modifier.width(188.dp),
                        onClick = onPrimaryAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenHeaderText(
    title: String,
    description: String,
    environmentLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = environmentLabel,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.large,
                )
                .padding(horizontal = UiSpacing.normal, vertical = UiSpacing.xs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
