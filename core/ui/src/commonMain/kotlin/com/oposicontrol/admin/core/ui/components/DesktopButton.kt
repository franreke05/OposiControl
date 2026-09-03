package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

enum class ButtonVariant {
    /** Acción principal de la pantalla. Una por vista, dos como mucho. */
    PRIMARY,

    /** Acción secundaria con peso: exportar, cancelar un flujo, volver. */
    SECONDARY,

    /** Acción terciaria dentro de una card o fila. Sin fondo hasta el hover. */
    GHOST,

    /** Destructiva. Rojo, y sólo aquí. */
    DANGER,

    /** Confirmación positiva explícita (aprobar, publicar, resolver). */
    SUCCESS,
}

enum class ButtonSize(val height: Dp, val horizontalPadding: Dp) {
    COMPACT(Sizing.buttonCompact, Spacing.sm),
    NORMAL(Sizing.buttonNormal, Spacing.md),
    LARGE(Sizing.buttonLarge, Spacing.lg),
}

/**
 * Botón del panel de escritorio.
 *
 * Cubre los seis estados que un control de escritorio necesita —normal, hover,
 * pressed, focused, disabled y loading— porque con ratón y teclado la mitad de la
 * información sobre qué es pulsable llega ANTES del clic. El botón anterior
 * (`GlassActionButton`) pasaba `indication = null`, lo que además de no tener
 * hover borraba el anillo de foco y dejaba la app sin navegación por teclado
 * visible.
 *
 * `loading` no es sólo un spinner: bloquea el clic, y ése es su trabajo real —
 * evitar el doble envío en acciones que mutan (conceder OT, publicar, resolver).
 */
@Composable
fun DesktopButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    size: ButtonSize = ButtonSize.NORMAL,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    // `loading` desactiva la interacción pero NO se pinta como `disabled`: un
    // botón en curso sigue siendo el botón que acabas de pulsar, y agrisarlo
    // haría dudar de si el clic llegó a registrarse.
    val interactive = enabled && !loading
    val palette = buttonPalette(variant, tokens)
    val shape = RoundedCornerShape(10.dp)
    // Las alturas de [ButtonSize] están pensadas para puntero. Si este botón
    // acaba dibujándose en móvil —ConfirmDialog, por ejemplo, lo comparten las
    // dos plataformas— se eleva al mínimo táctil: 40dp es cómodo con ratón y
    // demasiado pequeño para un dedo.
    val height = if (GlassTheme.isDesktop) size.height else maxOf(size.height, 48.dp)

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> palette.containerDisabled
            pressed -> palette.containerPressed
            hovered -> palette.containerHover
            else -> palette.container
        },
        animationSpec = tween(120),
    )
    val contentColor = if (enabled) palette.content else palette.contentDisabled
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && interactive -> tokens.focusRing
            !enabled -> palette.borderDisabled
            hovered -> palette.borderHover
            else -> palette.border
        },
        animationSpec = tween(120),
    )

    Row(
        modifier = modifier
            .height(height)
            .defaultMinSize(minWidth = 72.dp)
            .clip(shape)
            .background(container)
            .border(if (focused && interactive) 2.dp else 1.dp, borderColor, shape)
            .hoverable(interactionSource, enabled = interactive)
            .pointerHoverIcon(if (interactive) PointerIcon.Hand else PointerIcon.Default)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = interactive,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = size.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else if (icon != null) {
            Box(modifier = Modifier.size(Sizing.icon), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides contentColor) { icon() }
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.NORMAL,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) = DesktopButton(text, onClick, modifier, ButtonVariant.PRIMARY, size, enabled, loading, icon)

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.NORMAL,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) = DesktopButton(text, onClick, modifier, ButtonVariant.SECONDARY, size, enabled, loading, icon)

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.NORMAL,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) = DesktopButton(text, onClick, modifier, ButtonVariant.GHOST, size, enabled, loading, icon)

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.NORMAL,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) = DesktopButton(text, onClick, modifier, ButtonVariant.DANGER, size, enabled, loading, icon)

@Composable
fun SuccessButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.NORMAL,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) = DesktopButton(text, onClick, modifier, ButtonVariant.SUCCESS, size, enabled, loading, icon)

/**
 * Botón de sólo icono para acciones de fila y barras de herramientas.
 *
 * `label` no es opcional: es el `contentDescription` y el texto del tooltip. Un
 * icono sin nombre es un jeroglífico tanto para un lector de pantalla como para
 * quien abre la pantalla por primera vez.
 */
@Composable
fun DesktopIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.GHOST,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = Sizing.iconButton,
    icon: @Composable () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val palette = buttonPalette(variant, tokens)
    val shape = RoundedCornerShape(9.dp)

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> palette.containerDisabled
            selected -> tokens.surfaceSelected
            pressed -> palette.containerPressed
            hovered -> palette.containerHover
            else -> palette.container
        },
        animationSpec = tween(120),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && enabled -> tokens.focusRing
            selected -> tokens.accent.copy(alpha = 0.45f)
            hovered && enabled -> palette.borderHover
            else -> palette.border
        },
        animationSpec = tween(120),
    )
    val contentColor = when {
        !enabled -> palette.contentDisabled
        selected -> tokens.accent
        else -> palette.content
    }

    Box(
        modifier = modifier
            // Mismo criterio que en [DesktopButton]: con dedo, nunca por debajo
            // del mínimo táctil.
            .size(if (GlassTheme.isDesktop) size else maxOf(size, 48.dp))
            .clip(shape)
            .background(container)
            .border(if (focused && enabled) 2.dp else 1.dp, borderColor, shape)
            .hoverable(interactionSource, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { icon() }
    }
}

private data class ButtonPalette(
    val container: Color,
    val containerHover: Color,
    val containerPressed: Color,
    val containerDisabled: Color,
    val content: Color,
    val contentDisabled: Color,
    val border: Color,
    val borderHover: Color,
    val borderDisabled: Color,
)

private fun buttonPalette(
    variant: ButtonVariant,
    tokens: GlassTokens,
): ButtonPalette = when (variant) {
    ButtonVariant.PRIMARY -> ButtonPalette(
        // accentInk, no accent: el coral de marca no sostiene texto blanco a un
        // contraste accesible (2,32:1 medido). Ver GlassTokens.accentInk.
        container = tokens.accentInk,
        containerHover = Color(0xFFAC4819),
        containerPressed = Color(0xFF963E15),
        // Deshabilitado opaco, no translúcido: el blanco al 53% del botón
        // anterior dejaba el texto gris flotando sobre lo que hubiera detrás.
        containerDisabled = Color(0xFFEDEFF2),
        content = Color.White,
        contentDisabled = Color(0xFF6B7684),
        border = Color.Transparent,
        borderHover = Color.Transparent,
        borderDisabled = Color(0xFFE2E6EB),
    )
    ButtonVariant.SECONDARY -> ButtonPalette(
        container = tokens.surfaceSolid,
        containerHover = tokens.surfaceHover,
        containerPressed = Color(0xFFDFE7F1),
        containerDisabled = Color(0xFFF4F6F9),
        content = tokens.textPrimary,
        contentDisabled = Color(0xFF6B7684),
        border = tokens.border,
        borderHover = tokens.borderStrong,
        borderDisabled = Color(0xFFE9EDF2),
    )
    ButtonVariant.GHOST -> ButtonPalette(
        container = Color.Transparent,
        containerHover = tokens.surfaceHover,
        containerPressed = Color(0xFFDFE7F1),
        containerDisabled = Color.Transparent,
        content = tokens.textSecondary,
        contentDisabled = Color(0xFF9AA5B1),
        border = Color.Transparent,
        borderHover = Color.Transparent,
        borderDisabled = Color.Transparent,
    )
    ButtonVariant.DANGER -> ButtonPalette(
        container = tokens.danger,
        containerHover = Color(0xFFA93226),
        containerPressed = Color(0xFF922B21),
        containerDisabled = Color(0xFFEDEFF2),
        content = Color.White,
        contentDisabled = Color(0xFF6B7684),
        border = Color.Transparent,
        borderHover = Color.Transparent,
        borderDisabled = Color(0xFFE2E6EB),
    )
    ButtonVariant.SUCCESS -> ButtonPalette(
        container = tokens.success,
        containerHover = Color(0xFF27885B),
        containerPressed = Color(0xFF20744D),
        containerDisabled = Color(0xFFEDEFF2),
        content = Color.White,
        contentDisabled = Color(0xFF6B7684),
        border = Color.Transparent,
        borderHover = Color.Transparent,
        borderDisabled = Color(0xFFE2E6EB),
    )
}
