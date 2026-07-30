package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.layout.AppWindowClass
import com.oposicontrol.admin.core.ui.layout.UiSizes
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme

data class SidebarNavItem(
    val id: String,
    val label: String,
    val shortLabel: String = label.take(2).uppercase(),
)

@Composable
fun SidebarNav(
    items: List<SidebarNavItem>,
    currentSectionId: String,
    onSectionSelected: (String) -> Unit,
    compact: Boolean,
) {
    val scrollState = rememberScrollState()
    val compactRail = compact && AppLayout.metrics.windowClass == AppWindowClass.DesktopCompact
    GlassSurface(
        modifier = if (compact && !compactRail) {
            Modifier.fillMaxWidth()
        } else if (compactRail) {
            Modifier
                .width(UiSizes.navigationRailWidth)
                .fillMaxHeight()
        } else {
            Modifier
                .width(UiSizes.navigationDrawerWidth)
                .fillMaxHeight()
        },
        tint = GlassTheme.tokens.glassFillStrong,
        strokeColor = GlassTheme.tokens.glassStroke,
    ) {
        if (compactRail) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(scrollState)
                    .padding(horizontal = UiSpacing.compact, vertical = UiSpacing.blockLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
            ) {
                Text(
                    text = "OC",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassTheme.tokens.textPrimary,
                    modifier = Modifier
                        .height(UiSizes.touchTarget)
                        .padding(vertical = UiSpacing.normal)
                        .semantics { contentDescription = "OposiControl Admin" },
                )
                HorizontalDivider(modifier = Modifier.width(UiSizes.touchTarget))
                items.forEach { item ->
                    SidebarNavRailItem(
                        item = item,
                        selected = item.id == currentSectionId,
                        onClick = { onSectionSelected(item.id) },
                    )
                }
            }
        } else if (compact) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(
                            start = UiSpacing.screenMobile,
                            top = UiSpacing.normal,
                            end = UiSpacing.screenMobile,
                            bottom = UiSpacing.compact,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact),
                ) {
                    items.forEach { item ->
                        SidebarNavPill(
                            item = item,
                            selected = item.id == currentSectionId,
                            onClick = { onSectionSelected(item.id) },
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = UiSpacing.screenMobile, vertical = UiSpacing.blockLarge),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "OposiControl Admin",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Panel operativo desktop-first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.forEach { item ->
                        SidebarNavRow(
                            item = item,
                            selected = item.id == currentSectionId,
                            onClick = { onSectionSelected(item.id) },
                        )
                    }
                }
            }
        }
    }
    if (!compact) {
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun SidebarNavRow(
    item: SidebarNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = UiSizes.touchTarget)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.medium,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
                stateDescription = if (selected) "Sección actual" else "Sección disponible"
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SidebarNavPill(
    item: SidebarNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(UiSizes.touchTarget)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraLarge,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
                stateDescription = if (selected) "Sección actual" else "Sección disponible"
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SidebarNavRailItem(
    item: SidebarNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(UiSizes.touchTarget)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
                },
                shape = MaterialTheme.shapes.medium,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = item.label
                this.selected = selected
                stateDescription = if (selected) "Sección actual" else "Sección disponible"
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}
