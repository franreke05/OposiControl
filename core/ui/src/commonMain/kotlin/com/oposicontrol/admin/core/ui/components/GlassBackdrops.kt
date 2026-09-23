package com.oposicontrol.admin.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.oposicontrol.admin.core.ui.theme.GlassTokens

/**
 * Sobre qué se lee un texto de escritorio que no tiene superficie opaca propia.
 *
 * **Existe para MEDIR, no para pintar.** Un texto que va directamente sobre el
 * fondo —la cabecera de página— o sobre el cristal de un contenedor de sección
 * —la cabecera de un examen, el nombre de una columna, una pestaña— no tiene un
 * color de fondo: tiene un rango, porque lo que hay detrás cambia de un punto de
 * la ventana a otro. Estos son los dos extremos de ese rango, compuestos con las
 * MISMAS funciones que usa el renderer ([desktopAtmosphere] y [sectionSheen]).
 *
 * **Por qué dos y no uno.** El test de contraste componía el cristal sólo sobre la
 * parada más profunda del degradado. Es el peor caso para un texto OSCURO, que es
 * el del tema claro, y por eso el claro estaba bien medido. En el tema oscuro el
 * texto es claro y el peor caso es el opuesto: el pico del halo, con el brillo
 * superior del contenedor encima. Ahí el render daba 2,94:1 en una pestaña
 * inactiva y 3,31:1 en el contador del catálogo mientras el test decía que todo
 * pasaba. Un texto sobre cristal se mide contra los dos.
 */
data class GlassBackdrops(
    /** El fondo de la ventana en su punto más oscuro: la parada inferior, sin halos. */
    val pageDeepest: Color,
    /** El fondo en su punto más claro: el halo claro en su pico sobre la parada superior. */
    val pageLightest: Color,
    /** El cristal de sección sobre el punto más oscuro, sin brillo: el peor caso para un texto oscuro. */
    val glassOverDeepest: Color,
    /**
     * El cristal de sección sobre el punto más claro y con el brillo superior en su
     * pico: el peor caso para un texto claro.
     */
    val glassOverLightest: Color,
) {
    /** Los dos extremos del fondo desnudo, con su nombre para el mensaje de un test. */
    val page: List<Pair<String, Color>>
        get() = listOf(
            "fondo en su parada más oscura" to pageDeepest,
            "fondo en su punto más claro (pico del halo)" to pageLightest,
        )

    /** Los dos extremos del cristal de sección, con su nombre para el mensaje de un test. */
    val glass: List<Pair<String, Color>>
        get() = listOf(
            "cristal sobre la parada más oscura" to glassOverDeepest,
            "cristal sobre el pico del halo, con brillo" to glassOverLightest,
        )

    /** Los cuatro, para lo que puede quedar sobre cualquiera de ellos (una pestaña, un botón fantasma). */
    val all: List<Pair<String, Color>> get() = page + glass
}

/** Los extremos de fondo y cristal de una paleta, compuestos como los compone el renderer. */
fun glassBackdrops(tokens: GlassTokens): GlassBackdrops {
    val atmosphere = desktopAtmosphere(tokens)
    val sheen = sectionSheen(tokens)
    return GlassBackdrops(
        pageDeepest = atmosphere.deepest,
        pageLightest = atmosphere.lightest,
        glassOverDeepest = tokens.sectionGlass.compositeOver(atmosphere.deepest),
        glassOverLightest = sheen.top.compositeOver(tokens.sectionGlass.compositeOver(atmosphere.lightest)),
    )
}
