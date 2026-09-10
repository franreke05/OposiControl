package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius

/**
 * De qué FAMILIA es una acción o un dato, con independencia de su estado.
 *
 * No sustituye a [StatusTone] y no se solapa con él: [StatusTone] contesta «¿va
 * bien esto?» —y por eso lo desconocido cae en `UNKNOWN` y nunca en verde—,
 * mientras que [SemanticRole] contesta «¿de qué es esto?». Un atajo a «Crear
 * examen» no está *bien* ni *mal*; es de la familia de crear, y por eso se pinta
 * en verde aunque no represente ningún estado positivo.
 *
 * Existe porque sin él la única forma de dar color distinto a seis atajos era
 * escribir seis colores a mano dentro de la pantalla, que es exactamente cómo un
 * sistema de color deja de ser un sistema.
 */
enum class SemanticRole {
    /** Sin familia. Gris de texto secundario: ni llama ni molesta. */
    NEUTRAL,

    /** Crear, publicar, aprobar. También «todo al día». */
    SUCCESS,

    /** Espera, revisión, algo a medias. */
    WARNING,

    /** Moderación, reportes, destructivo. */
    DANGER,

    /** Catálogo, personas, importaciones: navegación fría, sin juicio de valor. */
    INFO,

    /** Analítica y OposiTester: las herramientas de lectura del producto. */
    INSIGHT,

    /** Marca. El coral de relleno; la acción primaria y poco más. */
    BRAND,

    /**
     * Suscripciones, planes, productos, tickets: la familia comercial.
     *
     * Antes esto era [BRAND], y el problema no era conceptual sino óptico. Al 12%
     * de alpha —el tinte de un contenedor de icono— el coral da (237,208,196) y el
     * rojo de [DANGER] da (237,202,199): cinco puntos de diferencia en un canal y
     * tres en otro. En la franja de atención eso dejaba «Reportes pendientes» y
     * «Suscripciones que vencen» pintados prácticamente igual pese a ser cosas
     * distintas, y el color dejaba de informar.
     */
    COMMERCE,
}

/** El color pleno del rol. Es el que va en el icono, en el punto o en el borde. */
@Composable
fun SemanticRole.color(): Color {
    val tokens = GlassTheme.tokens
    return when (this) {
        SemanticRole.NEUTRAL -> tokens.textSecondary
        SemanticRole.SUCCESS -> tokens.success
        SemanticRole.WARNING -> tokens.warning
        SemanticRole.DANGER -> tokens.danger
        SemanticRole.INFO -> tokens.info
        SemanticRole.INSIGHT -> tokens.insight
        SemanticRole.BRAND -> tokens.accentInk
        SemanticRole.COMMERCE -> tokens.commerce
    }
}

/**
 * El mismo color a baja opacidad, para rellenos.
 *
 * El 12% no es un número suelto: es el que ya usaban [StatusChip] y
 * `ErrorStateCard` para el fondo de su chip, y repetirlo aquí en vez de elegir
 * otro es lo que hace que un chip de estado y un contenedor de icono se lean
 * como piezas del mismo sistema.
 */
@Composable
fun SemanticRole.tint(alpha: Float = 0.12f): Color = color().copy(alpha = alpha)

/**
 * Icono grande dentro de un contenedor squircle con el tinte de su familia.
 *
 * Es la pieza que da a un panel su lenguaje de color sin teñir tarjetas enteras:
 * el color vive en un cuadrado de 40-44dp y el resto de la tarjeta se queda
 * blanco, así que seis tarjetas de seis colores distintos siguen leyéndose como
 * una rejilla y no como un semáforo.
 *
 * [contentDescription] se deja en null por defecto porque el uso normal es
 * decorativo —el icono acompaña a una etiqueta que ya dice lo mismo— y anunciar
 * los dos duplicaría cada tarjeta en un lector de pantalla.
 */
@Composable
fun SemanticIconContainer(
    icon: AppIcon,
    role: SemanticRole,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    shape: Shape = Radius.md,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(role.tint()),
        contentAlignment = Alignment.Center,
    ) {
        AppIconImage(
            icon = icon,
            size = iconSize,
            tint = role.color(),
            contentDescription = contentDescription,
        )
    }
}
