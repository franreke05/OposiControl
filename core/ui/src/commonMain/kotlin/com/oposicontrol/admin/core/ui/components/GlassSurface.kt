package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import com.oposicontrol.admin.core.ui.theme.Sizing

/**
 * Superficie de tarjeta.
 *
 * En escritorio deja de ser cristal. El fondo translúcido es bonito en una
 * captura y agotador en una jornada de trabajo: una tabla de cuarenta filas
 * sobre un degradado que se transparenta se lee peor, y el brillo superior
 * compite con el propio contenido. Con muchas tarjetas a la vez —y un backoffice
 * son muchas— el efecto se acumula hasta que nada destaca.
 *
 * La identidad no se pierde: el fondo azul atmosférico sigue detrás, y el cristal
 * se conserva íntegro en móvil, donde hay una tarjeta en pantalla y el efecto sí
 * aporta.
 *
 * La sustitución sólo afecta a los tintes de cristal. Si quien llama pasa un
 * color con intención —coral, rojo de peligro— se respeta tal cual.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    // 24dp de radio es lenguaje de tarjeta de movil; en una rejilla de paneles
    // administrativos las esquinas muy redondeadas restan sensacion de densidad.
    shape: Shape = RoundedCornerShape(if (GlassTheme.isDesktop) 12.dp else 24.dp),
    tint: Color = GlassTheme.tokens.glassFill,
    strokeColor: Color = GlassTheme.tokens.glassStroke,
    shadowElevation: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    val solid = GlassTheme.isDesktop
    val resolvedTint = when {
        !solid -> tint
        tint == tokens.glassFill -> tokens.surfaceSolid
        tint == tokens.glassFillStrong -> tokens.surfaceMuted
        else -> tint
    }
    val resolvedStroke = if (solid && strokeColor == tokens.glassStroke) tokens.border else strokeColor

    val animatedTint by animateColorAsState(
        targetValue = resolvedTint,
        animationSpec = tween(durationMillis = 180),
    )
    val animatedStroke by animateColorAsState(
        targetValue = resolvedStroke,
        animationSpec = tween(durationMillis = 180),
    )
    val animatedElevation by animateDpAsState(
        targetValue = shadowElevation,
        animationSpec = tween(durationMillis = 180),
    )
    val highlightColor = GlassTheme.tokens.glassHighlight
    val shadowColor = GlassTheme.tokens.shadowSpot
    // No Modifier.shadow()/elevation: on this renderer, an elevation-based RenderNode
    // shadow behind a translucent glass background composites into a visible opaque
    // rectangle rather than a soft drop shadow (isolated empirically — the artifact
    // tracks the shadow modifier itself, not the tint, border or highlight). "Soft
    // shadow" is instead a faint inner gradient drawn in the same pass as the
    // highlight sheen below, which is proven clean on this renderer.
    Box(
        modifier = modifier
            // Sin animateContentSize(): medido con dumpsys gfxinfo durante el gesto de
            // deslizar entre pestañas (GlassSurface se usa en cada card de cada
            // pantalla, así que este modificador corría decenas de veces por frame) —
            // pasaba de ~47% de frames "janky" (mediana 48ms/frame) a ~14% (mediana
            // 28ms/frame) al quitarlo. El caso de uso real de tamaño animado
            // (secciones que se expanden/colapsan en Home, EmptyStateCard con acción)
            // ya lo resuelve cada sitio con su propio AnimatedVisibility/
            // animateContentSize específico, así que este no aportaba nada que no
            // estuviera ya cubierto — solo coste.
            .clip(shape)
            .background(animatedTint)
            .border(1.dp, animatedStroke, shape)
            .drawWithContent {
                // El brillo y el degradado de sombra son la parte decorativa del
                // cristal. En escritorio se omiten: sobre una superficie opaca no
                // simulan profundidad, sólo ensucian el fondo de la tarjeta y
                // rebajan el contraste del texto que va encima.
                if (!solid) {
                    // Soft sheen confined to a fraction of the actual node height so it
                    // never reads as an opaque rectangle on short cards (tiles, chips).
                    val highlightBrush = Brush.verticalGradient(
                        colors = listOf(
                            highlightColor.copy(alpha = 0.22f),
                            highlightColor.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = size.height * 0.6f,
                    )
                    drawRect(highlightBrush)
                    if (animatedElevation > 0.dp) {
                        val shadowBrush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, shadowColor.copy(alpha = 0.10f)),
                            startY = size.height * 0.6f,
                            endY = size.height,
                        )
                        drawRect(shadowBrush)
                    }
                }
                drawContent()
            },
        content = content,
    )
}

/**
 * Botón heredado, presente en 68 llamadas.
 *
 * Se mantiene la firma —cambiarla obligaría a tocar esas 68 pantallas de golpe—
 * pero se le añade lo que le faltaba para funcionar con ratón y teclado:
 *
 *  - hover, que no tenía ninguno;
 *  - anillo de foco, que `indication = null` estaba eliminando junto con el
 *    resaltado, dejando la navegación por Tab invisible;
 *  - `loading`, que bloquea el clic y evita el doble envío;
 *  - un deshabilitado opaco y legible: el blanco al 53% anterior dejaba texto
 *    gris flotando sobre lo que hubiera detrás;
 *  - 40dp de alto en escritorio y 50dp en móvil, porque el dedo y el puntero no
 *    piden lo mismo.
 *
 * Para superficie nueva usa [DesktopButton], que además tiene variantes.
 */
@Composable
fun GlassActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tint: Color = GlassTheme.tokens.accent,
    textColor: Color = Color.White,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val isDesktop = GlassTheme.isDesktop
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    val interactive = enabled && !loading
    val radius = 10.dp
    val shape = RoundedCornerShape(radius)

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFFEDEFF2)
            pressed -> tint.darken(0.14f)
            hovered -> tint.darken(0.06f)
            else -> tint
        },
        animationSpec = tween(120),
    )
    val content = if (enabled) textColor else Color(0xFF8A96A3)
    val stroke by animateColorAsState(
        targetValue = when {
            focused && interactive -> tokens.focusRing
            !enabled -> Color(0xFFE2E6EB)
            else -> container
        },
        animationSpec = tween(120),
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (pressed && interactive) 0.985f else 1f,
        animationSpec = tween(durationMillis = 120),
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .defaultMinSize(
                minWidth = 88.dp,
                minHeight = if (isDesktop) Sizing.buttonNormal else 50.dp,
            )
            .clip(shape)
            .background(container)
            .border(if (focused && interactive) 2.dp else 1.dp, stroke, shape)
            .hoverable(interactionSource, enabled = interactive)
            .pointerHoverIcon(if (interactive) PointerIcon.Hand else PointerIcon.Default)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = interactive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = content,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                style = if (isDesktop) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Oscurece un color mezclándolo con negro; para hover y pressed. */
private fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
