package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.app.navigation.AdminSection
import com.oposicontrol.admin.core.ui.components.AdminScaffold
import com.oposicontrol.admin.core.ui.components.SidebarNavItem

@Composable
fun AdminShell(
    currentSection: AdminSection,
    sections: List<AdminSection> = AdminSection.entries,
    environmentLabel: String,
    onSectionSelected: (AdminSection) -> Unit,
    onPrimaryAction: (() -> Unit)? = null,
    showHeader: Boolean = true,
    globalSearch: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    AdminScaffold(
        sections = sections.map { section ->
            SidebarNavItem(id = section.name, label = section.label)
        },
        currentSectionId = currentSection.name,
        onSectionSelected = { selectedId ->
            sections.firstOrNull { it.name == selectedId }?.let(onSectionSelected)
        },
        environmentLabel = environmentLabel,
        title = currentSection.label,
        description = currentSection.description,
        primaryActionLabel = currentSection.primaryActionLabel,
        onPrimaryAction = onPrimaryAction,
        showHeader = showHeader,
        globalSearch = globalSearch,
        content = content,
    )
}
