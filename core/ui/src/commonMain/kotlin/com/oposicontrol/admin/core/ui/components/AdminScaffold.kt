package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.layout.AppWindowClass
import com.oposicontrol.admin.core.ui.layout.UiSpacing
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
    val layout = AppLayout.metrics
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassTheme.tokens.backgroundBottom.copy(alpha = 0.15f)),
    ) {
        if (layout.windowClass == AppWindowClass.Mobile || layout.windowClass == AppWindowClass.Tablet) {
            Column(modifier = Modifier.fillMaxSize()) {
                SidebarNav(
                    items = sections,
                    currentSectionId = currentSectionId,
                    onSectionSelected = onSectionSelected,
                    compact = true,
                )
                Column(
                    modifier = Modifier
                        .widthIn(max = layout.contentMaxWidth)
                        .fillMaxSize()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = layout.screenPadding, vertical = UiSpacing.normal),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
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
                    compact = layout.windowClass == AppWindowClass.DesktopCompact,
                )
                Column(
                    modifier = Modifier
                        .widthIn(max = layout.contentMaxWidth)
                        .fillMaxSize()
                        .padding(
                            horizontal = layout.screenPadding,
                            vertical = UiSpacing.blockLarge,
                        ),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.blockLarge),
                ) {
                    if (showHeader) {
                        ScreenHeader(
                            title = title,
                            description = description,
                            environmentLabel = environmentLabel,
                            primaryActionLabel = primaryActionLabel,
                            onPrimaryAction = onPrimaryAction,
                            compact = layout.windowClass == AppWindowClass.DesktopCompact,
                        )
                    }
                    globalSearch()
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
