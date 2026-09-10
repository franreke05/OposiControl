package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/**
 * Contenedor de SECCIÓN: el nivel 1 de cristal.
 *
 * El sistema tiene dos superficies y no una, y la diferencia es funcional:
 *
 *  - **Nivel 1 — esta pieza.** Es el marco de una franja: título, subtítulo,
 *    enlace y la rejilla que va dentro. Deja ver el fondo atmosférico, y por eso
 *    los halos del degradado se perciben: se ven *a través* del panel.
 *  - **Nivel 2 — [GlassSurface] y `surfaceSolid`.** Es toda superficie que lleva
 *    una CIFRA. Ahí el cristal está desactivado en escritorio a propósito, y el
 *    KDoc de [GlassSurface] explica por qué: una tabla de cuarenta filas sobre un
 *    degradado que se transparenta se lee peor.
 *
 * Esta pieza **no revoca** aquella decisión, la completa. Lo que [GlassSurface]
 * protege es el dato; lo que aquí se recupera es el marco, que no lleva ninguno.
 * Si algún día alguien mete una tabla directamente dentro de este contenedor sin
 * envolverla en una superficie opaca, el que estará roto es ese sitio, no esta
 * regla.
 *
 * Sin `Modifier.shadow()`, y por el mismo motivo medido que [GlassSurface]: sobre
 * un fondo translúcido, la sombra por elevación de este renderer compone un
 * rectángulo opaco en vez de un degradado suave.
 *
 * **Y sin sombra de contacto dibujada dentro**, que es lo que este panel tenía y
 * ha dejado de tener. Medido sobre el render: el panel entra a +10/+18 de
 * luminancia sobre la página y ese degradado interior lo hundía **8 puntos por
 * DEBAJO** de ella en su borde inferior. El canto dejaba de leerse como cristal y
 * pasaba a leerse como sombra, con los últimos 25px sostenidos sólo por el filete
 * de 1dp.
 *
 * No es que estuviera mal calibrado: es que no cabía. Como el relleno es blanco a
 * alpha constante y la página se oscurece hacia abajo, el panel YA gana contraste
 * solo —de +17 arriba a +26 abajo—. Cualquier oscurecimiento adicional en el
 * borde se come esa ganancia y luego la invierte. La separación la dan el rim y
 * ese contraste creciente; el brillo superior se queda porque va en la dirección
 * contraria y no resta nada.
 */
@Composable
fun GlassSectionSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.xl,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.md),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    val highlight = tokens.glassHighlight
    // El rim va de blanco arriba a azul pálido abajo. Un borde de un solo color
    // no cierra la forma sobre este fondo: arriba el panel es más claro que lo
    // que tiene detrás y necesita un filo blanco; abajo es más oscuro y necesita
    // uno azulado. Con un único tono, la mitad del contorno desaparece.
    val rim = Brush.verticalGradient(
        colors = listOf(highlight, tokens.sectionGlassStroke),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.sectionGlass)
            .drawWithContent {
                // Brillo superior, confinado al primer tercio: en un panel alto un
                // degradado a toda altura deja de leerse como brillo y se lee como
                // fondo sucio.
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(
                            highlight.copy(alpha = 0.30f),
                            highlight.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = size.height * 0.34f,
                    ),
                )
                drawContent()
            }
            .border(1.dp, rim, shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}
