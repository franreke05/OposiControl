package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * «Ir a algún sitio» desde la cabecera de una sección.
 *
 * Texto en `textPrimary` con un chevron coral: es el idioma que el panel de
 * escritorio ya usa para «Ver Analytics ›» o «Ver estado ›», y por eso sube al
 * Design System en vez de reescribirse en cada pantalla. La diferencia con un
 * botón no es de gusto: un botón con borde promete que ALGO OCURRE aquí, y estos
 * enlaces se limitan a llevarte a otro sitio. Poner el mismo peso visual a las
 * dos cosas hace que en una pantalla llena de secciones no se distinga cuál
 * ejecuta y cuál navega.
 *
 * Conserva lo que un botón sí aporta y un texto suelto pierde: cursor de mano,
 * subrayado al pasar por encima, anillo de foco de teclado y una etiqueta para
 * el lector de pantalla.
 */
@Composable
fun SectionLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = GlassTheme.tokens.textPrimary,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            // Alto mínimo de control: sin él, un enlace en la cabecera de una
            // sección mide menos que el título que tiene al lado y la fila se
            // desalinea.
            .defaultMinSize(minHeight = Sizing.buttonCompact)
            .clip(Radius.sm)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) tokens.focusRing else Color.Transparent,
                shape = Radius.sm,
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            textDecoration = if (hovered) TextDecoration.Underline else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppIconImage(icon = AppIcon.CHEVRON_RIGHT, size = 14.dp, tint = sectionLinkChevronTint(tokens))
    }
}

/**
 * El chevron del enlace: coral. Es un elemento de interfaz (3:1) y el coral sí
 * llega sobre cristal; el texto no, en el tema claro, y por eso va en
 * `textPrimary`. `ContrastTest` mide los dos contra los dos extremos del cristal.
 */
internal fun sectionLinkChevronTint(tokens: GlassTokens): Color = tokens.accentInk
