package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Ayuda contextual del panel.
 *
 * El panel lo usa una persona que no escribió el backend y que no tiene por qué
 * recordar qué es un percentil, qué mueve el saldo de OposiTickets o por qué una
 * importación puede quedarse a medias. La regla de tono es una sola: explicar el
 * SIGNIFICADO para quien administra, no la IMPLEMENTACIÓN.
 *
 *     mal → "Percentil estadístico en la distribución observada."
 *     bien → "Qué porcentaje de usuarios está por debajo de este."
 *
 * Tres niveles, deliberadamente distintos:
 *
 *  - [InfoTooltip]      una o dos frases, aparece al pasar el ratón.
 *  - [InfoHelpButton]   icono `i`; hover enseña el resumen, clic abre el detalle.
 *  - [HelpPanel]        sólo para procesos de varios pasos (importación).
 *
 * No poner un icono junto a cada palabra: la ayuda que está en todas partes deja
 * de leerse. Va donde el dato es ambiguo o donde equivocarse cuesta dinero.
 */

private const val TooltipDelayMillis = 350L

/** Texto de una ayuda de nivel 2. `example` es opcional y va en cursiva conceptual. */
data class HelpTopic(
    val title: String,
    val body: String,
    val example: String? = null,
)

/**
 * Envuelve cualquier contenido y le añade un tooltip al pasar el ratón.
 *
 * El retraso de 350ms no es decorativo: sin él, mover el ratón por una tabla
 * dispara una cascada de tooltips al cruzar cada celda.
 */
@Composable
fun InfoTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(hovered) {
        if (hovered) {
            delay(TooltipDelayMillis)
            visible = true
        } else {
            visible = false
        }
    }

    Box(modifier = modifier.hoverable(interactionSource)) {
        content()
        if (visible) {
            Popup(
                popupPositionProvider = BelowAnchorPositionProvider,
                properties = PopupProperties(focusable = false),
            ) {
                TooltipSurface(text)
            }
        }
    }
}

/**
 * Superficie INVERTIDA respecto a la pantalla, en los dos temas.
 *
 * Un tooltip flota sobre contenido cualquiera, así que lo único que garantiza
 * que se lea es que no comparta valor con nada de lo que tiene debajo. Antes eran
 * dos literales —navy oscuro y casi blanco— que acertaban en el tema claro por
 * pura coincidencia de que la pantalla es clara; en oscuro habrían pintado un
 * navy sobre navy y el globo de ayuda habría desaparecido.
 *
 * `textPrimary` como fondo y `surfaceSolid` como texto invierten la pantalla sin
 * necesidad de un token nuevo: en claro dan el mismo navy sobre blanco de antes,
 * y en oscuro un globo claro sobre la interfaz oscura. El contraste está
 * garantizado por construcción, porque los dos roles ya se miden el uno contra el
 * otro en `ContrastTest`.
 */
@Composable
private fun TooltipSurface(text: String) {
    val tokens = GlassTheme.tokens
    Box(
        modifier = Modifier
            .widthIn(max = Sizing.tooltipMaxWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.textPrimary)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.surfaceSolid,
        )
    }
}

/**
 * Botón `i` de ayuda contextual.
 *
 * Accesible por teclado: recibe foco con Tab, se abre con Intro o Espacio y se
 * cierra con Esc, porque una explicación que sólo existe bajo el ratón no existe
 * para quien navega con teclado.
 */
@Composable
fun InfoHelpButton(
    topic: HelpTopic,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var tooltipVisible by remember { mutableStateOf(false) }

    LaunchedEffect(hovered, open) {
        if (hovered && !open) {
            delay(TooltipDelayMillis)
            tooltipVisible = true
        } else {
            tooltipVisible = false
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(Sizing.iconButtonSmall)
                .clip(RoundedCornerShape(7.dp))
                .background(if (hovered || open) tokens.surfaceHover else Color.Transparent)
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) tokens.focusRing else Color.Transparent,
                    shape = RoundedCornerShape(7.dp),
                )
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.Spacebar -> {
                            open = !open
                            true
                        }
                        Key.Escape -> {
                            val wasOpen = open
                            open = false
                            wasOpen
                        }
                        else -> false
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { open = !open }
                .semantics { contentDescription = "Ayuda: ${topic.title}" },
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(
                icon = AppIcon.INFO,
                size = 15.dp,
                tint = if (hovered || open) tokens.accent else tokens.textSecondary,
            )
        }

        if (tooltipVisible) {
            Popup(
                popupPositionProvider = BelowAnchorPositionProvider,
                properties = PopupProperties(focusable = false),
            ) {
                TooltipSurface(topic.body.take(140).let { if (topic.body.length > 140) "$it…" else it })
            }
        }

        if (open) {
            Popup(
                popupPositionProvider = BelowAnchorPositionProvider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                InfoPopoverSurface(topic = topic, onClose = { open = false })
            }
        }
    }
}

@Composable
private fun InfoPopoverSurface(
    topic: HelpTopic,
    onClose: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier
            .widthIn(max = Sizing.popoverWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, RoundedCornerShape(12.dp))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.padding(bottom = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            DesktopIconButton(
                label = "Cerrar ayuda",
                onClick = onClose,
                size = 26.dp,
            ) {
                AppIconImage(AppIcon.CLOSE, size = 14.dp)
            }
        }
        Text(
            text = topic.body,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
        )
        topic.example?.let { example ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceMuted)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                Text(
                    text = example,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * Patrón etiqueta + ayuda.
 *
 *     Nota media  (i)
 *     8,42
 */
@Composable
fun LabelWithHelp(
    label: String,
    topic: HelpTopic? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlassTheme.tokens.textSecondary,
        )
        topic?.let { InfoHelpButton(it) }
    }
}

/**
 * Panel de ayuda lateral para procesos de varios pasos.
 *
 * No es un manual: si un tema necesita más de un párrafo y una lista corta, el
 * problema está en la pantalla, no en la documentación.
 */
@Composable
fun HelpPanel(
    title: String,
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = GlassTheme.tokens
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Sizing.helpPanelWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.surfaceSolid)
                .border(1.dp, tokens.border, RoundedCornerShape(14.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                DesktopIconButton(label = "Cerrar ayuda", onClick = onClose, size = 28.dp) {
                    AppIconImage(AppIcon.CLOSE, size = 15.dp)
                }
            }
            content()
        }
    }
}

/** Un paso numerado dentro de un [HelpPanel]. */
@Composable
fun HelpStep(
    number: Int,
    text: String,
) {
    val tokens = GlassTheme.tokens
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.surfaceSelected),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.accent,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Coloca el popup justo debajo del ancla, y encima si abajo no cabe.
 */
private object BelowAnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 6
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(8)
        }
        return IntOffset(x, y)
    }
}
