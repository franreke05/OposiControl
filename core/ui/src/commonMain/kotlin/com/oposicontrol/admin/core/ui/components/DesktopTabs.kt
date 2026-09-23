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
import com.oposicontrol.admin.core.ui.theme.GlassTokens
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
/**
 * Cuánto peso tiene la fila de pestañas.
 *
 * No es un tema alternativo: son dos papeles distintos para el mismo componente.
 */
enum class DesktopTabsVariant {
    /**
     * Sobrio. Subrayado coral y peso del texto, sin fondo.
     *
     * El de los hubs, donde las pestañas son un índice sobre una pantalla que ya
     * tiene su propio contenido.
     */
    PLAIN,

    /**
     * Integrada en la cabecera de un workspace.
     *
     * La pestaña activa se tiñe muy suavemente y su ICONO pasa a coral, además
     * del subrayado. Existe porque en un workspace las pestañas SON la
     * estructura de la pantalla, no un índice al margen: con el tratamiento
     * sobrio se leían como navegación secundaria suelta encima del formulario.
     *
     * El texto no pasa a coral: sobre el tinte de la pestaña activa, `accentInk`
     * no llega en el tema claro al 4,5:1 de un texto de 14sp. Ver [tabLabelColor].
     */
    WORKSPACE,
}

@Composable
fun DesktopTabs(
    tabs: List<DesktopTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    variant: DesktopTabsVariant = DesktopTabsVariant.PLAIN,
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
                variant = variant,
                onClick = { onSelect(tab.id) },
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: DesktopTab,
    selected: Boolean,
    variant: DesktopTabsVariant,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    // Etiqueta e icono se animan por separado porque ya no comparten color: en
    // la pestaña activa de un workspace el icono es coral y el texto no. Los
    // colores salen de funciones y no de un `when` aquí dentro para que
    // `ContrastTest` mida exactamente lo que se pinta.
    val labelColor by animateColorAsState(
        targetValue = tabLabelColor(tokens, selected, hovered, variant),
        animationSpec = tween(120),
    )
    val iconTint by animateColorAsState(
        targetValue = tabIconTint(tokens, selected, hovered, variant),
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
    val background by animateColorAsState(
        targetValue = tabBackground(tokens, selected, variant),
        animationSpec = tween(120),
    )

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
            .background(background)
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
            tab.icon?.let { AppIconImage(it, size = 15.dp, tint = iconTint) }
            Text(
                text = tab.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tab.badge?.let { badge ->
                val badgeColors = tabBadgeColors(tokens)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(badgeColors.fill)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColors.content,
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

// ---------------------------------------------------------------------------
// Qué color elige cada parte de la pestaña.
//
// Son funciones y no un `when` dentro del composable por una razón medida:
// `ContrastTest` compara PARES DE TOKENS, y un par de tokens no sabe qué color
// ha elegido un componente. Si alguien vuelve a poner el número del contador en
// `accent`, ningún par cambia y un test de tokens sigue en verde. Con la
// decisión aquí, el composable y el test llaman a la MISMA función con la paleta
// viva de los dos temas, y esa mutación pone el test en rojo.
// ---------------------------------------------------------------------------

/** Relleno y número de la píldora del contador de una pestaña. */
internal data class TabBadgeColors(val fill: Color, val content: Color)

/**
 * La píldora del contador: relleno coral suave y número en tinta de texto.
 *
 * El relleno coral se queda: es lo que dice que eso es un contador y no texto
 * suelto. El NÚMERO va en `textPrimary`. En `accent` sobre su propio relleno
 * medía entre 1,8 y 2,0:1 en el tema claro —ni el 3:1 que se pide a un elemento
 * de interfaz, y es un texto—; en `textPrimary` da entre 12,7 y 14,0:1 en claro
 * y entre 8,3 y 9,5:1 en oscuro, según sobre qué quede la pestaña.
 */
internal fun tabBadgeColors(tokens: GlassTokens): TabBadgeColors = TabBadgeColors(
    fill = tokens.accent.copy(alpha = 0.15f),
    content = tokens.textPrimary,
)

/**
 * El texto de la pestaña.
 *
 * En NINGUNA variante se tiñe de coral. En la pestaña activa de un workspace
 * se pintaba en `accentInk` sobre `surfaceSelected`, y eso da 4,21:1 en el tema
 * claro: por debajo del 4,5:1 de un texto de 14sp —el SemiBold no lo convierte
 * en texto grande—. No tiene arreglo por el lado del fondo: oscurecer el relleno
 * lo empeora, y aclararlo topa en 4,65:1 con blanco puro, con la pestaña ya
 * indistinguible del resto de la fila. Y `accentInk` es la marca: no se mueve.
 * En `textPrimary` da 14,62:1 en claro y 12,28:1 en oscuro.
 *
 * El `when` sobre la variante es exhaustivo a propósito: quien añada una
 * variante tiene que decidir aquí el color de su etiqueta, y el test la medirá
 * sin que nadie se acuerde de añadirla.
 */
internal fun tabLabelColor(
    tokens: GlassTokens,
    selected: Boolean,
    hovered: Boolean,
    variant: DesktopTabsVariant,
): Color = when {
    selected -> when (variant) {
        DesktopTabsVariant.PLAIN,
        DesktopTabsVariant.WORKSPACE,
        -> tokens.textPrimary
    }
    hovered -> tokens.textPrimary
    else -> tokens.textSecondary
}

/**
 * El icono de la pestaña.
 *
 * Aquí sí se queda el coral en la pestaña activa de un workspace: un icono es un
 * elemento de interfaz y pide 3:1, y `accentInk` sobre `surfaceSelected` da
 * 4,21:1 en claro y 7,03:1 en oscuro. Junto con el subrayado y el relleno, es lo
 * que hace que la fila se lea como parte del workspace. En el resto de casos el
 * icono acompaña al texto.
 */
internal fun tabIconTint(
    tokens: GlassTokens,
    selected: Boolean,
    hovered: Boolean,
    variant: DesktopTabsVariant,
): Color = when {
    selected && variant == DesktopTabsVariant.WORKSPACE -> tokens.accentInk
    else -> tabLabelColor(tokens, selected, hovered, variant)
}

/**
 * El fondo de la pestaña.
 *
 * Tinte muy bajo y sólo en la activa de un workspace: confirma cuál está abierta
 * sin convertir la fila en una hilera de pastillas rellenas, que competiría con
 * el formulario de debajo. Transparente en todos los demás casos: la pestaña
 * queda sobre lo que tenga detrás.
 */
internal fun tabBackground(
    tokens: GlassTokens,
    selected: Boolean,
    variant: DesktopTabsVariant,
): Color = when {
    selected && variant == DesktopTabsVariant.WORKSPACE -> tokens.surfaceSelected
    else -> Color.Transparent
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
