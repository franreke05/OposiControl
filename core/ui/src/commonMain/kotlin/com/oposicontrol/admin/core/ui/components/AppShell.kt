package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppBreakpoint
import com.oposicontrol.admin.core.ui.layout.appBreakpointFor
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_forum
import oposicontrol.core.ui.generated.resources.ic_home
import oposicontrol.core.ui.generated.resources.ic_reports
import oposicontrol.core.ui.generated.resources.ic_settings
import oposicontrol.core.ui.generated.resources.ic_tester
import org.jetbrains.compose.resources.painterResource

data class ShellNavigationItem(
    val id: String,
    val label: String,
    val icon: String,
    val badge: String? = null,
)

data class ShellNavigationGroup(
    val id: String,
    val label: String,
    val items: List<ShellNavigationItem>,
)

@Composable
fun AppShell(
    currentRouteId: String,
    groups: List<ShellNavigationGroup>,
    mobileDestinations: List<ShellNavigationItem>,
    onRouteSelected: (String) -> Unit,
    environmentLabel: String,
    title: String,
    description: String,
    breadcrumbs: List<String> = emptyList(),
    showBreadcrumbs: Boolean = true,
    showScreenHeader: Boolean = true,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    canGoBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    // Solo afecta al layout compacto (el de escritorio usa un sidebar, no una
    // barra inferior). Por defecto true para no cambiar ninguna pantalla ya
    // existente — Screen 05 (detalle de hilo) es la primera en pasar false:
    // una pantalla de detalle interna gana espacio y evita cambios de sección
    // accidentales sin bottom nav visible.
    showBottomBar: Boolean = true,
    globalSearch: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(com.oposicontrol.admin.core.ui.theme.GlassTheme.tokens.backgroundBottom.copy(alpha = 0.15f)),
    ) {
        val breakpoint = appBreakpointFor(maxWidth)
        if (breakpoint == AppBreakpoint.EXPANDED) {
            Row(modifier = Modifier.fillMaxSize()) {
                ShellDesktopSidebar(
                    groups = groups,
                    currentRouteId = currentRouteId,
                    onRouteSelected = onRouteSelected,
                )
                ShellContent(
                    title = title,
                    description = description,
                    environmentLabel = environmentLabel,
                    breadcrumbs = breadcrumbs,
                    showBreadcrumbs = showBreadcrumbs,
                    showScreenHeader = showScreenHeader,
                    compactLayout = false,
                    primaryActionLabel = primaryActionLabel,
                    onPrimaryAction = onPrimaryAction,
                    canGoBack = canGoBack,
                    onBack = onBack,
                    globalSearch = globalSearch,
                    content = content,
                )
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (showBottomBar) {
                        ShellMobileNavigation(
                            items = mobileDestinations,
                            currentRouteId = currentRouteId,
                            onRouteSelected = onRouteSelected,
                        )
                    } else {
                        // Sin bottom nav no hay ninguna vista que consuma el inset de
                        // la barra de navegación del sistema (ShellMobileNavigation lo
                        // hace con su propio .navigationBarsPadding()); este Spacer NO
                        // es un valor mágico — reserva exactamente ese inset real, no
                        // un dp inventado, para que el último elemento del contenido
                        // no quede tapado por la barra/gesto del sistema.
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                },
            ) { scaffoldPadding ->
                ShellContent(
                    title = title,
                    description = description,
                    environmentLabel = environmentLabel,
                    breadcrumbs = breadcrumbs,
                    showBreadcrumbs = showBreadcrumbs,
                    showScreenHeader = showScreenHeader,
                    compactLayout = true,
                    primaryActionLabel = primaryActionLabel,
                    onPrimaryAction = onPrimaryAction,
                    canGoBack = canGoBack,
                    onBack = onBack,
                    globalSearch = globalSearch,
                    bottomBarPadding = scaffoldPadding.calculateBottomPadding(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun ShellContent(
    title: String,
    description: String,
    environmentLabel: String,
    breadcrumbs: List<String>,
    showBreadcrumbs: Boolean,
    showScreenHeader: Boolean,
    compactLayout: Boolean,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    canGoBack: Boolean,
    onBack: (() -> Unit)?,
    globalSearch: @Composable () -> Unit,
    bottomBarPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (compactLayout) Modifier.statusBarsPadding() else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp + bottomBarPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (canGoBack && onBack != null && compactLayout) {
            BackHeader(onBack = onBack, title = title)
        } else if (canGoBack && onBack != null) {
            BackHeader(onBack = onBack)
        }
        if (showScreenHeader && (!compactLayout || !canGoBack)) {
            ScreenHeader(
                title = title,
                description = description,
                environmentLabel = environmentLabel,
                primaryActionLabel = primaryActionLabel,
                onPrimaryAction = onPrimaryAction,
                compact = compactLayout,
            )
        }
        if (showBreadcrumbs && breadcrumbs.isNotEmpty()) {
            Breadcrumbs(items = breadcrumbs)
        }
        globalSearch()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun ShellDesktopSidebar(
    groups: List<ShellNavigationGroup>,
    currentRouteId: String,
    onRouteSelected: (String) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        tint = com.oposicontrol.admin.core.ui.theme.GlassTheme.tokens.glassFillStrong,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("OposiControl Admin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Panel operativo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            group.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        group.items.forEach { item ->
                            ShellNavigationRow(item, item.id == currentRouteId, onRouteSelected)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellNavigationRow(
    item: ShellNavigationItem,
    selected: Boolean,
    onRouteSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 46.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
                MaterialTheme.shapes.medium,
            )
            .clickable { onRouteSelected(item.id) }
            .semantics {
                this.selected = selected
                contentDescription = item.label
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShellNavigationIcon(item.icon, selected)
        Text(
            item.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.badge?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun ShellMobileNavigation(
    items: List<ShellNavigationItem>,
    currentRouteId: String,
    onRouteSelected: (String) -> Unit,
) {
    val strokeColor = com.oposicontrol.admin.core.ui.theme.GlassTheme.tokens.glassStroke
    // A true edge-anchored bar, not a floating pill: full width, square corners,
    // a single hairline top border instead of GlassSurface's all-side border/shadow.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.oposicontrol.admin.core.ui.theme.GlassTheme.tokens.glassFillStrong)
            .drawBehind {
                drawLine(
                    color = strokeColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.id == currentRouteId
                // Icon-only destination: label is still exposed via contentDescription
                // for accessibility, just not rendered visually. A fixed-size dot slot
                // (transparent when inactive) keeps every icon vertically centered the
                // same way regardless of selection state.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable { onRouteSelected(item.id) }
                        .semantics(mergeDescendants = true) {
                            this.selected = selected
                            contentDescription = item.label
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    ShellNavigationIcon(item.icon, selected, iconSize = 24.dp)
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
fun Breadcrumbs(items: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BackHeader(onBack: () -> Unit, title: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onBack)
            .semantics { contentDescription = "Volver" }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("←", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(title ?: "Volver", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

// Real, licensed icon set (Lucide — https://lucide.dev, ISC license), bundled as
// Android vector drawables under composeResources/drawable/ic_*.xml. Replaces the
// earlier hand-drawn Canvas glyphs.
private val ShellIconDrawables = mapOf(
    "home" to Res.drawable.ic_home,
    "reports" to Res.drawable.ic_reports,
    "forum" to Res.drawable.ic_forum,
    "tester" to Res.drawable.ic_tester,
    "settings" to Res.drawable.ic_settings,
)

@Composable
private fun ShellNavigationIcon(icon: String, selected: Boolean, iconSize: Dp = 22.dp) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val drawable = ShellIconDrawables[icon]
    if (drawable == null) {
        Text(icon, color = color)
        return
    }
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(iconSize),
    )
}
