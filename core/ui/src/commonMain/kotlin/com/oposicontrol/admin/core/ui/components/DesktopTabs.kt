package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Una pestaña.
 *
 * `badge` sirve para lo que de verdad importa en un backoffice: avisar de que
 * detrás de una pestaña cerrada hay algo que mirar, sin obligar a abrirlas todas
 * una por una.
 */
data class DesktopTab(
    val id: String,
    val label: String,
    val icon: AppIcon? = null,
    val badge: String? = null,
)

/**
 * Pestañas del panel.
 *
 * Existen por §32: una ficha de usuario con perfil, rendimiento, competición,
 * economía, comunidad y debilidades apiladas son setenta campos en una columna,
 * y encontrar uno concreto exige recorrerla entera. Repartidos en pestañas, cada
 * vista responde a una pregunta.
 *
 * Deliberadamente sobrias: subrayado coral y peso del texto, sin fondo de
 * pastilla. Una fila de pestañas rellenas compite con el contenido que hay
 * debajo, que es lo que en realidad se ha venido a leer.
 */
@Composable
fun DesktopTabs(
    tabs: List<DesktopTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Si no caben, se desplazan en horizontal en vez de comprimirse hasta
            // ser ilegibles. Es el caso que §51 permite explícitamente.
            .horizontalScroll(scrollState)
            .drawBottomHairline(tokens.border),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.Bottom,
    ) {
        tabs.forEach { tab ->
            TabItem(
                tab = tab,
                selected = tab.id == selectedId,
                onClick = { onSelect(tab.id) },
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: DesktopTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val contentColor by animateColorAsState(
        targetValue = when {
            selected -> tokens.textPrimary
            hovered -> tokens.textPrimary
            else -> tokens.textSecondary
        },
        animationSpec = tween(120),
    )
    val indicator by animateColorAsState(
        targetValue = when {
            selected -> tokens.accent
            hovered -> tokens.borderStrong
            else -> Color.Transparent
        },
        animationSpec = tween(120),
    )

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = tab.label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tab.icon?.let { AppIconImage(it, size = 15.dp, tint = contentColor) }
            Text(
                text = tab.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tab.badge?.let { badge ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(tokens.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.accent,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(indicator),
        )
    }
}

/** Línea de 1dp bajo la fila de pestañas, contra la que se alinea el subrayado activo. */
private fun Modifier.drawBottomHairline(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        color = color,
        topLeft = Offset(0f, size.height - 1f),
        size = Size(size.width, 1f),
    )
}
