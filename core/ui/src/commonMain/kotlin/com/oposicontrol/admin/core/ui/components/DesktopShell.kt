package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.DesktopBreakpoint
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.screenPadding
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Shell de escritorio.
 *
 *     ┌──────────┬──────────────────────────────────────┐
 *     │          │ Cabecera fija: contexto · buscar · ? │
 *     │ Sidebar  ├──────────────────────────────────────┤
 *     │          │ Ruta · Título · Descripción · Acciones│
 *     │          ├──────────────────────────────────────┤
 *     │          │ Contenido (scroll propio)            │
 *     └──────────┴──────────────────────────────────────┘
 *
 * Lo que cambia respecto al shell anterior no es el aspecto sino la estructura:
 * antes no había cabecera. El título, la descripción y la acción principal
 * vivían en una tarjeta DENTRO del contenido, así que se iban hacia arriba al
 * hacer scroll y el botón principal de la pantalla dejaba de estar accesible en
 * cuanto bajabas por una tabla. Aquí cabecera y encabezado de página están
 * anclados, y sólo se desplaza lo que hay debajo.
 *
 * El móvil no pasa por aquí: [AppShell] sigue resolviendo su propio layout.
 */
@Composable
fun DesktopShell(
    currentRouteId: String,
    groups: List<ShellNavigationGroup>,
    onRouteSelected: (String) -> Unit,
    environmentLabel: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    breadcrumbs: List<String> = emptyList(),
    showBreadcrumbs: Boolean = true,
    showPageHeader: Boolean = true,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    canGoBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    globalSearch: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val breakpoint = DesktopWindow.breakpoint
    // En ventanas estrechas la sidebar arranca plegada: por debajo de 1100dp los
    // 244dp del menú son casi una cuarta parte del ancho util.
    var collapsed by rememberSaveable(breakpoint.isCompact) { mutableStateOf(breakpoint.isCompact) }
    var helpOpen by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxSize()) {
        DesktopSidebar(
            groups = groups,
            currentRouteId = currentRouteId,
            onRouteSelected = onRouteSelected,
            collapsed = collapsed,
            onToggleCollapsed = { collapsed = !collapsed },
        )
        Column(modifier = Modifier.fillMaxSize()) {
            DesktopTopBar(
                environmentLabel = environmentLabel,
                globalSearch = globalSearch,
                helpOpen = helpOpen,
                onToggleHelp = { helpOpen = !helpOpen },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showPageHeader) {
                        DesktopPageHeader(
                            title = title,
                            description = description,
                            breadcrumbs = if (showBreadcrumbs) breadcrumbs else emptyList(),
                            primaryActionLabel = primaryActionLabel,
                            onPrimaryAction = onPrimaryAction,
                            canGoBack = canGoBack,
                            onBack = onBack,
                            breakpoint = breakpoint,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = breakpoint.screenPadding,
                                end = breakpoint.screenPadding,
                                bottom = breakpoint.screenPadding,
                            ),
                    ) {
                        content()
                    }
                }
                if (helpOpen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(breakpoint.screenPadding),
                    ) {
                        GlobalHelpPanel(onClose = { helpOpen = false })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------

@Composable
private fun DesktopSidebar(
    groups: List<ShellNavigationGroup>,
    currentRouteId: String,
    onRouteSelected: (String) -> Unit,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val width by animateDpAsState(
        targetValue = if (collapsed) Sizing.sidebarCollapsed else Sizing.sidebarExpanded,
        animationSpec = tween(180),
    )
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(tokens.surfaceSolid)
            .drawRightBorder(tokens.border),
    ) {
        // Marca + plegado
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.headerHeight)
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (!collapsed) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OposiControl",
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Centro de control",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            InfoTooltip(text = if (collapsed) "Desplegar menú" else "Plegar menú") {
                DesktopIconButton(
                    label = if (collapsed) "Desplegar menú" else "Plegar menú",
                    onClick = onToggleCollapsed,
                    size = 30.dp,
                ) {
                    AppIconImage(AppIcon.COLLAPSE, size = 16.dp)
                }
            }
        }
        HairlineDivider()

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                groups.forEach { group ->
                    if (collapsed) {
                        HairlineDivider(modifier = Modifier.padding(vertical = Spacing.xxs))
                    } else {
                        Text(
                            text = group.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.textSecondary,
                            modifier = Modifier.padding(
                                start = Spacing.md,
                                end = Spacing.sm,
                                top = Spacing.sm,
                                bottom = Spacing.xxs,
                            ),
                        )
                    }
                    group.items.forEach { item ->
                        SidebarRow(
                            item = item,
                            selected = item.id == currentRouteId,
                            collapsed = collapsed,
                            onClick = { onRouteSelected(item.id) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.sm))
            }
            DesktopVerticalScrollbar(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SidebarRow(
    item: ShellNavigationItem,
    selected: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            selected -> tokens.surfaceSelected
            hovered -> tokens.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = tween(120),
    )
    val contentColor = when {
        selected -> tokens.accent
        hovered -> tokens.textPrimary
        else -> tokens.textSecondary
    }

    val row = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs)
                .height(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(background)
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .semantics {
                    this.selected = selected
                    contentDescription = item.label
                }
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) {
                Arrangement.Center
            } else {
                Arrangement.spacedBy(Spacing.sm)
            },
        ) {
            // Marca de selección: una barra coral de 3dp. Un fondo plano solo no
            // basta cuando hay 28 filas — el ojo necesita un ancla en el margen.
            if (!collapsed) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) tokens.accent else Color.Transparent),
                )
            }
            AppIconImage(
                icon = AppIcon.fromKey(item.icon) ?: AppIcon.CHEVRON_RIGHT,
                size = 17.dp,
                tint = contentColor,
            )
            if (!collapsed) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) tokens.textPrimary else contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.badge?.let { badge ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.accent,
                        )
                    }
                }
            }
        }
    }

    // Plegada, la sidebar es sólo iconos: sin tooltip sería adivinanza.
    if (collapsed) InfoTooltip(text = item.label) { row() } else row()
}

// ---------------------------------------------------------------------------
// Cabecera
// ---------------------------------------------------------------------------

@Composable
private fun DesktopTopBar(
    environmentLabel: String,
    globalSearch: @Composable () -> Unit,
    helpOpen: Boolean,
    onToggleHelp: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizing.headerHeight)
            .background(tokens.surfaceSolid)
            .drawBottomBorder(tokens.border)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(modifier = Modifier.weight(1f).widthIn(max = 520.dp)) {
            globalSearch()
        }
        EnvironmentChip(environmentLabel)
        InfoTooltip(text = "Ayuda del panel") {
            DesktopIconButton(
                label = "Ayuda del panel",
                onClick = onToggleHelp,
                selected = helpOpen,
                size = 32.dp,
            ) {
                AppIconImage(AppIcon.HELP, size = 17.dp)
            }
        }
    }
}

@Composable
private fun EnvironmentChip(label: String) {
    val tokens = GlassTheme.tokens
    InfoTooltip(text = "Instalación contra la que está trabajando el panel.") {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(tokens.surfaceMuted)
                .padding(horizontal = Spacing.xs, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tokens.info),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Encabezado de página
// ---------------------------------------------------------------------------

/**
 * Encabezado de página: dónde estoy, qué es esto y qué puedo hacer aquí.
 *
 * Se puede usar suelto en pantallas que no monten el shell entero.
 */
@Composable
fun DesktopPageHeader(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    breadcrumbs: List<String> = emptyList(),
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    canGoBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    breakpoint: DesktopBreakpoint = DesktopWindow.breakpoint,
    actions: @Composable () -> Unit = {},
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = breakpoint.screenPadding,
                end = breakpoint.screenPadding,
                top = Spacing.lg,
                bottom = Spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        if (breadcrumbs.isNotEmpty() || (canGoBack && onBack != null)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (canGoBack && onBack != null) {
                    DesktopIconButton(label = "Volver", onClick = onBack, size = 26.dp) {
                        AppIconImage(AppIcon.BACK, size = 15.dp)
                    }
                }
                breadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        AppIconImage(AppIcon.CHEVRON_RIGHT, size = 13.dp, tint = tokens.textSecondary)
                    }
                    Text(
                        text = crumb,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (index == breadcrumbs.lastIndex) tokens.textPrimary else tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayLarge,
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    PrimaryButton(text = primaryActionLabel, onClick = onPrimaryAction)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ayuda global
// ---------------------------------------------------------------------------

@Composable
private fun GlobalHelpPanel(onClose: () -> Unit) {
    val tokens = GlassTheme.tokens
    val scrollState = rememberScrollState()
    HelpPanel(title = "Ayuda", visible = true, onClose = onClose) {
        Column(
            modifier = Modifier.verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            globalHelpSections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.textPrimary,
                    )
                    Text(
                        text = section.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utilidades
// ---------------------------------------------------------------------------

@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GlassTheme.tokens.border),
    )
}

/**
 * Bordes de 1dp reales.
 *
 * `HorizontalDivider` de Material toma su color del esquema, que aquí es
 * translúcido por el tema de cristal: sobre una superficie opaca se ve más
 * flojo en unas pantallas que en otras según lo que haya detrás.
 */
private fun Modifier.drawRightBorder(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        color = color,
        topLeft = Offset(size.width - 1f, 0f),
        size = Size(1f, size.height),
    )
}

private fun Modifier.drawBottomBorder(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        color = color,
        topLeft = Offset(0f, size.height - 1f),
        size = Size(size.width, 1f),
    )
}
