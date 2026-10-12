package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import com.oposicontrol.admin.core.ui.theme.motionTween

data class AdminTableColumn(
    val label: String,
    val weight: Float = 1f,
    /**
     * Ancho por debajo del cual la columna deja de ser legible. Sólo entra en
     * juego cuando la ventana es demasiado estrecha para repartir por peso.
     */
    val minWidth: Dp = 104.dp,
    /**
     * Clave de ordenación. Si es null la columna no se puede ordenar y su
     * cabecera no responde al clic.
     */
    val sortKey: String? = null,
    val numeric: Boolean = false,
    /**
     * Explicación de la columna, mostrada con un icono `i` en la cabecera.
     *
     * Va en la cabecera y no en cada celda por lo evidente: una tabla de cuarenta
     * filas con un icono de ayuda por fila sería ruido. El concepto se explica
     * una vez, arriba, donde está su nombre.
     */
    val help: HelpTopic? = null,
)

data class AdminTableRow(
    val key: String,
    val cells: List<String>,
    val selected: Boolean = false,
    val supporting: String? = null,
)

enum class SortDirection { ASC, DESC }

data class TableSort(val key: String, val direction: SortDirection)

/**
 * Tabla del panel.
 *
 * El cambio de fondo respecto a la versión anterior es cómo se reparte el ancho.
 * Antes cada columna medía `peso * 172dp` con un mínimo de 118dp y la tabla vivía
 * dentro de un `horizontalScroll` permanente: en 1920 una tabla de cuatro
 * columnas ocupaba unos 730dp y dejaba media pantalla vacía, y en una ventana
 * estrecha había que arrastrar en horizontal aunque sobrara sitio.
 *
 * Ahora el reparto es por peso y la tabla ocupa el ancho disponible. El scroll
 * horizontal sigue existiendo, pero sólo aparece cuando de verdad no caben los
 * mínimos —que es el único caso en que §51 lo permite.
 *
 * `rowActions` es el otro cambio importante: las celdas siguen siendo texto (32
 * pantallas dependen de eso), pero las acciones de fila ya no tienen que ser
 * botones enormes dentro de una celda.
 */
@Composable
fun AdminTable(
    columns: List<AdminTableColumn>,
    rows: List<AdminTableRow>,
    modifier: Modifier = Modifier,
    onRowClick: ((String) -> Unit)? = null,
    sort: TableSort? = null,
    onSortChange: ((String) -> Unit)? = null,
    emptyTitle: String = "Sin resultados",
    emptyMessage: String = "No hay nada que mostrar con los filtros actuales.",
    emptyAction: (@Composable () -> Unit)? = null,
    rowActions: (@Composable RowScope.(AdminTableRow) -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val actionsWidth = if (rowActions != null) 96.dp else 0.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, RoundedCornerShape(12.dp)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val available = maxWidth - Spacing.md * 2 - actionsWidth
            val requiredMin = columns.fold(0.dp) { total, column -> total + column.minWidth } +
                Spacing.sm * (columns.size - 1).coerceAtLeast(0)
            // Reparto por peso mientras quepa; scroll sólo cuando físicamente no cabe.
            val needsScroll = requiredMin > available
            val scrollModifier = if (needsScroll) Modifier.horizontalScroll(horizontalScroll) else Modifier
            val fixedTableWidth = requiredMin + Spacing.md * 2 + actionsWidth

            Column(modifier = Modifier.fillMaxWidth().then(scrollModifier)) {
                val rowWidthModifier = if (needsScroll) Modifier.width(fixedTableWidth) else Modifier.fillMaxWidth()

                TableHeader(
                    columns = columns,
                    sort = sort,
                    onSortChange = onSortChange,
                    needsScroll = needsScroll,
                    actionsWidth = actionsWidth,
                    modifier = rowWidthModifier,
                )
                HairlineDivider()

                if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyStateCard(
                            title = emptyTitle,
                            message = emptyMessage,
                            action = emptyAction,
                            modifier = Modifier.widthIn(max = 440.dp),
                        )
                    }
                } else {
                    Box {
                        LazyColumn(state = listState) {
                            items(rows, key = { it.key }) { row ->
                                TableRow(
                                    row = row,
                                    columns = columns,
                                    onRowClick = onRowClick,
                                    needsScroll = needsScroll,
                                    actionsWidth = actionsWidth,
                                    rowActions = rowActions,
                                    modifier = rowWidthModifier,
                                )
                            }
                        }
                        DesktopVerticalScrollbar(
                            listState = listState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeader(
    columns: List<AdminTableColumn>,
    sort: TableSort?,
    onSortChange: ((String) -> Unit)?,
    needsScroll: Boolean,
    actionsWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier
            .background(tokens.surfaceMuted)
            .heightIn(min = Sizing.tableHeaderHeight)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            val sortKey = column.sortKey
            val isSorted = sortKey != null && sort?.key == sortKey
            Row(
                modifier = columnWidth(column, needsScroll)
                    .then(
                        if (sortKey != null && onSortChange != null) {
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onSortChange(sortKey) }
                                .padding(vertical = 4.dp)
                        } else {
                            Modifier
                        },
                    ),
                horizontalArrangement = if (column.numeric) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = column.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSorted) tokens.textPrimary else tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sortKey != null && onSortChange != null) {
                    AppIconImage(
                        icon = if (isSorted && sort?.direction == SortDirection.DESC) {
                            AppIcon.CHEVRON_DOWN
                        } else {
                            AppIcon.SORT
                        },
                        size = 12.dp,
                        tint = if (isSorted) tokens.accent else tokens.textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                column.help?.let { InfoHelpButton(it) }
            }
        }
        if (actionsWidth > 0.dp) {
            Box(modifier = Modifier.width(actionsWidth))
        }
    }
}

@Composable
private fun TableRow(
    row: AdminTableRow,
    columns: List<AdminTableColumn>,
    onRowClick: ((String) -> Unit)?,
    needsScroll: Boolean,
    actionsWidth: Dp,
    rowActions: (@Composable RowScope.(AdminTableRow) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            row.selected -> tokens.surfaceSelected
            hovered && onRowClick != null -> tokens.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = motionTween(OposiMotion.Fast),
    )

    Column(
        modifier = modifier
            .heightIn(min = Sizing.tableRowHeight)
            .background(background)
            .hoverable(interactionSource)
            .then(
                if (onRowClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interactionSource, indication = null) {
                            onRowClick(row.key)
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { index, column ->
                Text(
                    text = row.cells.getOrElse(index) { "" },
                    modifier = columnWidth(column, needsScroll),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (rowActions != null) {
                Row(
                    modifier = Modifier.width(actionsWidth),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowActions(row)
                }
            }
        }
        if (row.supporting != null) {
            Text(
                text = row.supporting,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HairlineDivider()
}

/**
 * Reparto por peso cuando cabe; ancho mínimo fijo cuando hay que hacer scroll.
 */
private fun RowScope.columnWidth(column: AdminTableColumn, needsScroll: Boolean): Modifier =
    if (needsScroll) Modifier.width(column.minWidth) else Modifier.weight(column.weight)
