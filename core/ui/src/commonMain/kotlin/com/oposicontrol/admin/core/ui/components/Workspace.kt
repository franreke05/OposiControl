package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import com.oposicontrol.admin.core.ui.theme.motionTween

/**
 * El workspace de tres columnas —catálogo, ficha, resumen— como piezas COMPARTIDAS.
 *
 * Nace en el bloque de Contenido (Exámenes y Especialidades), donde estas mismas piezas viven como componentes privados
 * de cada feature. Aquí están las partes genéricas, con las MISMAS medidas y los mismos tokens, para que Usuarios,
 * OposiTickets, Catálogo y OposiMarket se lean como la misma aplicación sin que cada pantalla reescriba su propio
 * contenedor. La regla que gobierna el sistema no cambia: **el marco es cristal (nivel 1) y el dato es opaco (nivel 2)**.
 *
 * Contenido no se ha tocado: sigue usando sus piezas. El día que adopte estas, serán un cambio de import, no de aspecto.
 */
object WorkspaceSizing {
    /** Catálogo a 1920: cabe un título de dos líneas sin estrechar el editor. */
    val catalogWide: Dp = 330.dp

    /** Catálogo entre 1100 y 1500. */
    val catalogStandard: Dp = 310.dp

    val railWide: Dp = 300.dp
    val columnGap: Dp = Spacing.lg

    /** Por debajo de este ancho el editor deja de caber con el rail al lado: se pliega el rail, nunca el editor. */
    val editorMinWidth: Dp = 700.dp

    /** Apilado: el catálogo no se come la pantalla entera. */
    val stackedCatalogMaxHeight: Dp = 480.dp

    val sectionPadding: PaddingValues = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)
    val sectionIconChip: Dp = Sizing.iconButton
    val scrollbarGutter: Dp = 10.dp

    /** Por debajo, las pestañas del editor van sin icono. */
    val tabIconsMinWidth: Dp = 520.dp
}

data class WorkspaceColumns(
    val catalogWidth: Dp,
    val editorWidth: Dp,
    val railWidth: Dp,
    val showRail: Boolean,
    val stacked: Boolean,
)

/** Reparto de columnas en dp exactos. Función pura: se prueba sin Compose. */
fun workspaceColumns(totalWidth: Dp, isWide: Boolean, supportsMasterDetail: Boolean, hasRail: Boolean = true): WorkspaceColumns {
    if (!supportsMasterDetail) {
        return WorkspaceColumns(totalWidth, totalWidth, 0.dp, showRail = false, stacked = true)
    }
    val gap = WorkspaceSizing.columnGap
    val catalogWidth = if (isWide) WorkspaceSizing.catalogWide else WorkspaceSizing.catalogStandard
    val railWidth = WorkspaceSizing.railWide
    val withRail = totalWidth - catalogWidth - railWidth - gap * 2
    val showRail = hasRail && isWide && withRail >= WorkspaceSizing.editorMinWidth
    return WorkspaceColumns(
        catalogWidth = catalogWidth,
        editorWidth = if (showRail) withRail else totalWidth - catalogWidth - gap,
        railWidth = railWidth,
        showRail = showRail,
        stacked = false,
    )
}

/**
 * Las tres columnas.
 *
 * Un solo `BoxWithConstraints`, en la raíz; el régimen de ventana lo decide `DesktopWindow`. En apilado desplaza la PÁGINA
 * y el rail baja debajo del editor con su mismo contenido (no se oculta: resume lo que se está a punto de guardar).
 *
 * [editor] recibe el ancho real de su columna y si debe ocupar el alto (columna) o crecer con su contenido (apilado).
 * [rail] recibe si lleva su propio scroll: en apilado NO, porque ya desplaza la página.
 */
@Composable
fun WorkspaceLayout(
    modifier: Modifier = Modifier,
    catalog: @Composable (Modifier) -> Unit,
    editor: @Composable (modifier: Modifier, contentWidth: Dp, fillsHeight: Boolean) -> Unit,
    rail: (@Composable (modifier: Modifier, scrolls: Boolean) -> Unit)? = null,
) {
    val breakpoint = DesktopWindow.breakpoint
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val layout = workspaceColumns(totalWidth, breakpoint.isWide, breakpoint.supportsMasterDetail, hasRail = rail != null)
        if (layout.stacked) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WorkspaceSizing.columnGap),
            ) {
                catalog(Modifier.fillMaxWidth().heightIn(max = WorkspaceSizing.stackedCatalogMaxHeight))
                editor(Modifier.fillMaxWidth(), totalWidth, false)
                rail?.invoke(Modifier.fillMaxWidth(), false)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(WorkspaceSizing.columnGap),
            ) {
                catalog(Modifier.width(layout.catalogWidth).fillMaxHeight())
                Box(modifier = Modifier.width(layout.editorWidth).fillMaxHeight()) {
                    editor(Modifier.fillMaxSize(), layout.editorWidth, true)
                }
                if (layout.showRail) rail?.invoke(Modifier.width(layout.railWidth), true)
            }
        }
    }
}

/** Contenedor de SECCIÓN (nivel 1, cristal): icono semántico, título, acción a la derecha. Lo de dentro es opaco. */
@Composable
fun WorkspaceSection(
    icon: AppIcon,
    role: SemanticRole,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    help: HelpTopic? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = WorkspaceSizing.sectionPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SemanticIconContainer(icon = icon, role = role, size = WorkspaceSizing.sectionIconChip, iconSize = Sizing.icon)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    help?.let { InfoHelpButton(it) }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            action?.invoke()
        }
        content()
    }
}

/** Superficie OPACA (nivel 2): todo lo que lleva un dato o un campo. */
@Composable
fun WorkspacePanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.md),
    borderColor: Color? = null,
    background: Color? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(background ?: tokens.surfaceSolid)
            .border(1.dp, borderColor ?: tokens.border, Radius.card)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Píldora de cristal: un recuento o un dato corto en una cabecera. */
@Composable
fun WorkspacePill(text: String, modifier: Modifier = Modifier, icon: AppIcon? = null) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier
            .clip(Radius.pill)
            .background(tokens.glassFillStrong)
            .border(1.dp, tokens.sectionGlassStroke, Radius.pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        icon?.let { AppIconImage(icon = it, size = 12.dp, tint = tokens.textSecondary) }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal data class WorkspacePillColors(val background: Color, val border: Color, val content: Color, val icon: Color)

internal fun workspaceFilterPillColors(active: Boolean, tokens: GlassTokens): WorkspacePillColors = WorkspacePillColors(
    background = if (active) tokens.surfaceSelected else tokens.surfaceSolid,
    border = if (active) tokens.accent.copy(alpha = 0.55f) else tokens.border,
    // El texto activo va en `textPrimary`: `accentInk` sobre el tinte de una píldora activa no llega al 4,5:1 en claro.
    content = if (active) tokens.textPrimary else tokens.textSecondary,
    icon = if (active) tokens.accentInk else tokens.textSecondary,
)

/** Una píldora de filtro (opaca: es un control de formulario dentro de cristal). Activa lleva marca además de color. */
@Composable
fun WorkspaceFilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = workspaceFilterPillColors(selected, GlassTheme.tokens)
    val background by animateColorAsState(targetValue = colors.background, animationSpec = motionTween(OposiMotion.Fast))
    val borderColor by animateColorAsState(targetValue = colors.border, animationSpec = motionTween(OposiMotion.Fast))
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = Sizing.buttonCompact)
            .clip(Radius.pill)
            .background(background)
            .border(1.dp, borderColor, Radius.pill)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { role = Role.Tab; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        if (selected) AppIconImage(icon = AppIcon.CHECK, size = 12.dp, tint = colors.icon)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
        )
    }
}

/**
 * Duración de las transiciones de color del workspace. La misma de las filas de Contenido: lo bastante corta para no
 * hacer esperar y lo bastante larga para que un cambio de selección se lea como cambio y no como parpadeo.
 */
const val WORKSPACE_COLOR_MILLIS: Int = OposiMotion.Fast

internal data class WorkspaceRowColors(val background: Color, val border: Color, val bar: Color)

internal fun workspaceCatalogRowColors(selected: Boolean, hovered: Boolean, tokens: GlassTokens): WorkspaceRowColors =
    WorkspaceRowColors(
        background = when {
            selected -> tokens.surfaceSelected
            hovered -> tokens.surfaceHover
            else -> tokens.surfaceSolid
        },
        border = if (selected) tokens.accent.copy(alpha = 0.45f) else tokens.border,
        bar = if (selected) tokens.accentInk else Color.Transparent,
    )

/**
 * Una fila de catálogo.
 *
 * Seleccionada se marca con una barra a la izquierda y un fondo muy tenue —no con un borde de color alrededor, que en una
 * lista de doce compite con el contenido— y su icono pasa al rol de marca. [eyebrow] es el código o identificador visible
 * (línea pequeña sobre el título); [trailing] suele ser el chip de estado; [meta] se une con « · ».
 *
 * [changeKey] es lo que identifica la VERSIÓN de la fila (su fecha de edición, su saldo): cuando cambia con la fila ya en
 * pantalla, la fila se resalta un instante. `null` = sin resalte.
 */
@Composable
fun WorkspaceCatalogRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: AppIcon,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    description: String? = null,
    meta: List<String> = emptyList(),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    changeKey: Any? = null,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = workspaceCatalogRowColors(selected, hovered, tokens)
    val background by animateColorAsState(targetValue = colors.background, animationSpec = motionTween(OposiMotion.Fast))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(Radius.card)
            .background(background)
            // Cuando [changeKey] cambia (la fila se acaba de guardar o de actualizar) un velo de acento se enciende y se apaga.
            .motionHighlight(trigger = changeKey, color = tokens.accent)
            .border(1.dp, colors.border, Radius.card)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { this.selected = selected }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(colors.bar))
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (leading != null) {
                leading()
            } else {
                SemanticIconContainer(
                    icon = icon,
                    role = if (selected) SemanticRole.BRAND else SemanticRole.INFO,
                    size = Sizing.iconButton,
                    iconSize = Sizing.icon,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (!eyebrow.isNullOrBlank()) {
                            Text(
                                text = eyebrow,
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    trailing?.invoke()
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

enum class WorkspaceNoticeTone { INFO, WARNING, DANGER }

/** Aviso dentro de un formulario: borde del tono, icono además de color, detalle opcional. */
@Composable
fun WorkspaceNotice(tone: WorkspaceNoticeTone, title: String, modifier: Modifier = Modifier, detail: String? = null) {
    val tokens = GlassTheme.tokens
    val toneColor = when (tone) {
        WorkspaceNoticeTone.INFO -> tokens.info
        WorkspaceNoticeTone.WARNING -> tokens.warning
        WorkspaceNoticeTone.DANGER -> tokens.danger
    }
    val icon = when (tone) {
        WorkspaceNoticeTone.INFO -> AppIcon.INFO
        WorkspaceNoticeTone.WARNING -> AppIcon.WARNING
        WorkspaceNoticeTone.DANGER -> AppIcon.BLOCKED
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(tokens.surfaceSolid)
            .border(1.dp, toneColor.copy(alpha = 0.45f), Radius.card)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AppIconImage(icon = icon, size = Sizing.icon, tint = toneColor, modifier = Modifier.padding(top = 1.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
    }
}

/** Un dato de la «Vista rápida»: etiqueta pequeña, valor debajo, detalle opcional. */
data class WorkspaceFact(val label: String, val value: String, val detail: String? = null, val emphasised: Boolean = false)

/** Rejilla de dos columnas de [WorkspaceFact] sobre superficie opaca: el bloque «Vista rápida» del rail. */
@Composable
fun WorkspaceFacts(facts: List<WorkspaceFact>, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        facts.chunked(2).forEach { pair ->
            WorkspaceEqualRow(columns = 2, gap = Spacing.sm) {
                pair.forEach { fact ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(text = fact.label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                        Text(
                            text = fact.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (fact.emphasised) FontWeight.SemiBold else FontWeight.Normal,
                            color = tokens.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        fact.detail?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** Celda de una fila repartida a partes iguales, en píxeles exactos. */
data class WorkspaceGridCell(val start: Int, val end: Int) {
    val width: Int get() = end - start
}

/**
 * Reparte [totalWidth] entre [count] celdas iguales separadas por [gap]; el resto de la división se reparte de izquierda a
 * derecha, así la última celda termina exactamente en el borde. Función pura.
 */
fun workspaceRowCells(totalWidth: Int, gap: Int, count: Int): List<WorkspaceGridCell> {
    require(count > 0) { "Una fila necesita al menos un elemento" }
    val available = (totalWidth - gap * (count - 1)).coerceAtLeast(0)
    val base = available / count
    val extra = available % count
    var x = 0
    return List(count) { index ->
        val width = base + if (index < extra) 1 else 0
        WorkspaceGridCell(x, x + width).also { x += width + gap }
    }
}

/**
 * Una fila de [columns] celdas iguales SIN `weight()`: los anchos salen de [workspaceRowCells]. Si hay menos hijos que
 * columnas, las celdas que sobran quedan vacías (la rejilla no se estira).
 */
@Composable
fun WorkspaceEqualRow(
    columns: Int,
    modifier: Modifier = Modifier,
    gap: Dp = Spacing.md,
    content: @Composable () -> Unit,
) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    Layout(modifier = modifier.fillMaxWidth(), content = content) { measurables, constraints ->
        val cells = workspaceRowCells(constraints.maxWidth, gapPx, columns)
        val used = measurables.take(columns)
        val target = used.mapIndexed { index, measurable -> measurable.maxIntrinsicHeight(cells[index].width) }.maxOrNull() ?: 0
        val placeables = used.mapIndexed { index, measurable ->
            val width = cells[index].width
            measurable.measure(Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = maxOf(constraints.maxHeight, target)))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(cells[index].start, 0) }
        }
    }
}

/** Texto de apoyo del bloque «Información» del rail: una línea por regla que conviene saber antes de guardar. */
@Composable
fun WorkspaceInfoLines(lines: List<String>, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        lines.forEach { Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary) }
    }
}

/** La columna derecha: bloques de resumen apilados, con su scroll cuando es columna. */
@Composable
fun WorkspaceRail(modifier: Modifier = Modifier, scrolls: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrolls) Modifier.verticalScroll(scroll) else Modifier)
                .padding(end = if (scrolls) WorkspaceSizing.scrollbarGutter else 0.dp),
            verticalArrangement = Arrangement.spacedBy(WorkspaceSizing.columnGap),
            content = content,
        )
        if (scrolls) {
            DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Cabecera de la ficha abierta: icono, título, subtítulo y chips a la derecha. */
@Composable
fun WorkspaceEditorHeader(
    icon: AppIcon,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    role: SemanticRole = SemanticRole.INFO,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (leading != null) {
            leading()
        } else {
            SemanticIconContainer(icon = icon, role = role, size = Sizing.iconButton, iconSize = Sizing.iconLarge)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/**
 * La columna central: cabecera, pestañas, cuerpo que desplaza y pie anclado (la barra de guardado).
 *
 * [fillsHeight] viene de [WorkspaceLayout]: en columna el cuerpo lleva su propio scroll y el pie queda siempre a la vista;
 * en apilado crece con su contenido porque ya desplaza la página.
 *
 * [contentKey] identifica lo que el cuerpo está enseñando (registro y pestaña). Cuando cambia, el cuerpo entra con un
 * fundido corto; mientras no cambie, editar o recargar no anima nada. `null` = cuerpo sin movimiento.
 */
@Composable
fun WorkspaceEditor(
    header: @Composable () -> Unit,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
    tabs: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    contentKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // El cuerpo entra cuando cambia [contentKey] (otro registro, otra pestaña). La cabecera y el pie no: son el marco.
    val bodyMotion = if (contentKey == null) Modifier else Modifier.motionEntrance(key = contentKey)
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = WorkspaceSizing.sectionPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        header()
        tabs?.invoke()
        if (fillsHeight) {
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.fillMaxWidth().then(bodyMotion).verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    content = content,
                )
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().then(bodyMotion), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
        }
        footer?.invoke()
    }
}

enum class WorkspaceSaveTone { INFO, WARNING, DANGER, SUCCESS }

/**
 * Barra de guardado, anclada al pie de la ficha.
 *
 * Sin cambios es una línea discreta con un punto; con cambios pendientes se vuelve prominente (fondo y borde de aviso) y
 * ofrece descartar. El estado se dice con texto e icono, no sólo con color.
 */
@Composable
fun WorkspaceSaveBar(
    message: String,
    primaryLabel: String,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    tone: WorkspaceSaveTone = WorkspaceSaveTone.INFO,
    detail: String? = null,
    prominent: Boolean = false,
    canSave: Boolean = true,
    saving: Boolean = false,
    onDiscard: (() -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    val toneColor = when (tone) {
        WorkspaceSaveTone.INFO -> tokens.info
        WorkspaceSaveTone.WARNING -> tokens.warning
        WorkspaceSaveTone.DANGER -> tokens.danger
        WorkspaceSaveTone.SUCCESS -> tokens.successInk
    }
    // La barra se enciende al aparecer cambios y se apaga al guardarlos: un fundido corto, no un salto.
    val barBackground by animateColorAsState(
        targetValue = if (prominent) tokens.warning.copy(alpha = 0.10f) else tokens.warning.copy(alpha = 0f),
        animationSpec = motionTween(OposiMotion.Fast),
    )
    val barBorder by animateColorAsState(
        targetValue = if (prominent) tokens.warning.copy(alpha = 0.45f) else tokens.warning.copy(alpha = 0f),
        animationSpec = motionTween(OposiMotion.Fast),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(barBackground)
            .border(1.dp, barBorder, Radius.card)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.Top,
        ) {
            if (prominent) {
                // El icono dice lo mismo que el tono: listo, aviso o bloqueo. Nunca sólo el color.
                val toneIcon = when (tone) {
                    WorkspaceSaveTone.SUCCESS -> AppIcon.CHECK
                    WorkspaceSaveTone.DANGER -> AppIcon.BLOCKED
                    WorkspaceSaveTone.INFO -> AppIcon.INFO
                    WorkspaceSaveTone.WARNING -> AppIcon.WARNING
                }
                AppIconImage(icon = toneIcon, size = Sizing.icon, tint = toneColor, modifier = Modifier.padding(top = 2.dp))
            } else {
                Box(modifier = Modifier.padding(top = 6.dp).size(7.dp).clip(Radius.pill).background(toneColor))
            }
            Column(modifier = Modifier.motionEntrance(key = message, rise = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = message,
                    style = if (prominent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (prominent) tokens.textPrimary else tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onDiscard != null) SecondaryButton(text = "Descartar", onClick = onDiscard)
        PrimaryButton(text = primaryLabel, onClick = onSave, enabled = canSave, loading = saving)
    }
}

/** Rango visible y total de una lista paginada por el servidor. Texto puro: se prueba sin Compose. */
fun workspaceRangeText(offset: Int, shown: Int, total: Int?, noun: String): String = when {
    shown == 0 -> "Sin $noun"
    total == null -> "${offset + 1}–${offset + shown} $noun"
    else -> "${offset + 1}–${offset + shown} de $total $noun"
}

/**
 * Barra de páginas: dice el rango y el total, no sólo el número de página. Las flechas sólo aparecen si hay más de una.
 */
@Composable
fun WorkspacePager(
    rangeText: String,
    pageIndex: Int,
    pageCount: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier.fillMaxWidth().height(Sizing.buttonCompact),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rangeText,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (pageCount > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                DesktopIconButton(
                    label = "Página anterior",
                    enabled = pageIndex > 0,
                    size = Sizing.iconButtonSmall,
                    onClick = { onPageChange(pageIndex - 1) },
                ) { AppIconImage(AppIcon.CHEVRON_LEFT, size = 14.dp) }
                Text(
                    text = "${pageIndex + 1} / $pageCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textPrimary,
                    modifier = Modifier.semantics { contentDescription = "Página ${pageIndex + 1} de $pageCount" },
                )
                DesktopIconButton(
                    label = "Página siguiente",
                    enabled = pageIndex < pageCount - 1,
                    size = Sizing.iconButtonSmall,
                    onClick = { onPageChange(pageIndex + 1) },
                ) { AppIconImage(AppIcon.CHEVRON_RIGHT, size = 14.dp) }
            }
        }
    }
}

/** Una línea «etiqueta: valor» de una ficha de sólo lectura. */
@Composable
fun WorkspaceMetaLine(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = tokens.textPrimary, modifier = Modifier.weight(1f))
    }
}
