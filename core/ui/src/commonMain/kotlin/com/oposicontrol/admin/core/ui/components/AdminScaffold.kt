package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun AdminScaffold(
    sections: List<SidebarNavItem>,
    currentSectionId: String,
    onSectionSelected: (String) -> Unit,
    environmentLabel: String,
    title: String,
    description: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    showHeader: Boolean = true,
    globalSearch: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassTheme.tokens.backgroundBottom.copy(alpha = 0.15f)),
    ) {
        val compact = maxWidth < 1040.dp
        if (compact) {
            Column(modifier = Modifier.fillMaxSize()) {
                SidebarNav(
                    items = sections,
                    currentSectionId = currentSectionId,
                    onSectionSelected = onSectionSelected,
                    compact = true,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (showHeader) {
                        ScreenHeader(
                            title = title,
                            description = description,
                            environmentLabel = environmentLabel,
                            primaryActionLabel = primaryActionLabel,
                            onPrimaryAction = onPrimaryAction,
                            compact = true,
                        )
                    }
                    globalSearch()
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                SidebarNav(
                    items = sections,
                    currentSectionId = currentSectionId,
                    onSectionSelected = onSectionSelected,
                    compact = false,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (showHeader) {
                        ScreenHeader(
                            title = title,
                            description = description,
                            environmentLabel = environmentLabel,
                            primaryActionLabel = primaryActionLabel,
                            onPrimaryAction = onPrimaryAction,
                            compact = false,
                        )
                    }
                    globalSearch()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
