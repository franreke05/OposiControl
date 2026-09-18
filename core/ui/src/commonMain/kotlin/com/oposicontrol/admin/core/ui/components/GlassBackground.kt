package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import kotlin.random.Random

/**
 * Fondo atmosférico de la aplicación.
 *
 * Se bifurca por plataforma porque los dos fondos tienen trabajos distintos. En
 * móvil hay una tarjeta en pantalla y el fondo es casi todo lo que se ve: por eso
 * lleva círculos desenfocados de verdad, que son caros pero se pagan una vez. En
 * escritorio hay ocho o nueve paneles a la vez y el fondo se ve *a través* de
 * ellos, así que lo que hace falta es profundidad —no adorno— y a coste cero de
 * blur: todo se resuelve con degradados en una sola pasada de dibujo.
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
) {
    if (GlassTheme.isDesktop) {
        DesktopAtmosphere(modifier)
    } else {
        MobileAtmosphere(modifier)
    }
}

/**
 * Escritorio: degradado de tres paradas y tres halos radiales.
 *
 * Los colores NO son hexadecimales nuevos: las tres paradas salen de
 * `backgroundTop` y `backgroundBottom` —la de arriba es `backgroundTop` llevado
 * hacia el blanco, que es lo que da el «hielo» de la parte superior— y los tres
 * halos son `accent`, `info` y blanco, todos a alpha muy bajo. Un fondo con
 * colores propios sería un cuarto sistema de color conviviendo con los tres que
 * ya hay.
 *
 * Los halos existen para que el cristal tenga algo que dejar ver. Sobre un azul
 * uniforme, un panel translúcido y uno opaco se ven idénticos y el nivel 1 de
 * cristal no se nota; con variación detrás, el panel deja pasar un gradiente
 * distinto en cada zona de la pantalla y ahí es donde se lee como cristal.
 *
 * Sin `blur()`: un degradado radial ya es suave por definición y el desenfoque
 * sólo añadiría una capa de composición a pantalla completa que hay que repintar.
 */
@Composable
private fun DesktopAtmosphere(modifier: Modifier) {
    val atmosphere = desktopAtmosphere(GlassTheme.tokens)

    Box(
        modifier = modifier
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to atmosphere.top,
                            0.30f to atmosphere.middle,
                            1f to atmosphere.deep,
                        ),
                    ),
                )
                // 1 · Claro alto a la izquierda, bajo la cabecera de página.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(atmosphere.haloLight, Color.Transparent),
                        center = Offset(size.width * 0.16f, size.height * 0.06f),
                        radius = size.width * 0.46f,
                    ),
                )
                // 2 · Azul frío a la derecha, a la altura de «Necesita tu atención».
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(atmosphere.haloCool, Color.Transparent),
                        center = Offset(size.width * 0.86f, size.height * 0.30f),
                        radius = size.width * 0.44f,
                    ),
                )
                // 3 · Coral muy tenue abajo, bajo la rejilla inferior. Es el único
                // sitio del fondo donde aparece el color de marca, y aparece tan
                // bajo que sólo se percibe como temperatura, no como color.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(atmosphere.haloWarm, Color.Transparent),
                        center = Offset(size.width * 0.58f, size.height * 1.02f),
                        radius = size.width * 0.50f,
                    ),
                )
            },
    ) {
        Grain(modifier = Modifier.fillMaxSize())
    }
}

/**
 * Los colores del fondo atmosférico de escritorio, ya resueltos para una paleta.
 *
 * Se sacan del composable por una razón medida: el test de contraste componía el
 * cristal sobre la parada MÁS OSCURA del degradado, que es el peor caso para un
 * texto oscuro y el mejor para uno claro. En el tema oscuro el texto es claro y
 * el peor caso es el contrario —el pico del halo—, y ahí la etiqueta de una
 * pestaña inactiva medía 2,94:1 en la captura con el test en verde. Con esta
 * función, el composable y el test leen EXACTAMENTE las mismas paradas y el mismo
 * halo, y no una copia.
 */
internal data class DesktopAtmosphere(
    /** Parada superior (0 %): `backgroundTop` llevado hacia el blanco. La más clara del degradado. */
    val top: Color,
    /** Parada intermedia (30 %). */
    val middle: Color,
    /** Parada inferior (100 %): la más profunda. */
    val deep: Color,
    /** Halo claro, arriba a la izquierda, con su alpha de PICO (el del centro del degradado radial). */
    val haloLight: Color,
    /** Halo frío, a la derecha. */
    val haloCool: Color,
    /** Halo cálido, abajo. */
    val haloWarm: Color,
) {
    /**
     * El punto más claro que puede tener la ventana: el halo claro en su pico sobre
     * la parada superior.
     *
     * Es una cota, y por arriba: el centro del halo cae al 6 % del alto, donde el
     * degradado ya ha empezado a bajar hacia la parada intermedia, así que el
     * render real queda un punto por debajo. Los otros dos halos son más tenues y
     * caen sobre paradas más oscuras.
     */
    val lightest: Color get() = haloLight.compositeOver(top)

    /**
     * El punto más oscuro: la parada inferior sin halo encima. Existe en la
     * esquina inferior izquierda, fuera del radio de los tres.
     */
    val deepest: Color get() = deep
}

internal fun desktopAtmosphere(tokens: GlassTokens): DesktopAtmosphere {
    // Los tres halos existen para que el cristal tenga algo que dejar ver, y en
    // oscuro esa variación se consigue con MUCHA menos luz: el mismo blanco al
    // 50% sobre navy no es atmósfera, es un foco. Se reducen a un tercio, lo
    // justo para que el degradado detrás de un panel no sea plano. Es la
    // diferencia entre profundidad y glow, y el objetivo dice que sin glow.
    val haloScale = if (tokens.isDark) 0.34f else 1f
    return DesktopAtmosphere(
        // blanco/azul hielo → azul muy pálido → azul hielo algo más profundo.
        //
        // El 0,45 está medido con la captura delante: a 0,78 la parada superior
        // salía prácticamente blanca, y como el cristal de sección es blanco al
        // 58%, panel y fondo daban el mismo valor. El resultado era un panel
        // invisible — cristal que no se lee como cristal porque no hay nada
        // detrás que dejar ver.
        //
        // En el tema OSCURO la mezcla se reduce, no se invierte: la parada
        // superior sigue siendo la más clara del degradado —que es lo que hace
        // que la pantalla se lea igual en los dos temas— pero se acerca al blanco
        // un 6% en vez de un 45%. Con el 45% original, un navy de 0,02 de
        // luminancia saltaba a un gris medio y el fondo pasaba a ser más claro
        // que los propios paneles: el tema oscuro dejaba de serlo en el tercio
        // superior de la ventana.
        top = lerp(tokens.backgroundTop, Color.White, if (tokens.isDark) 0.06f else 0.45f),
        middle = tokens.backgroundTop,
        deep = tokens.backgroundDeep,
        haloLight = Color.White.copy(alpha = 0.50f * haloScale),
        haloCool = tokens.info.copy(alpha = 0.14f * haloScale),
        haloWarm = tokens.accent.copy(alpha = 0.07f * haloScale),
    )
}

/** Móvil: se conserva EXACTAMENTE como estaba. */
@Composable
private fun MobileAtmosphere(modifier: Modifier) {
    val layout = AppLayout.metrics
    val tokens = GlassTheme.tokens
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(tokens.backgroundTop, tokens.backgroundBottom),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .offset(
                    x = -layout.width(80.dp, 60.dp),
                    y = -layout.height(120.dp, 90.dp),
                )
                .size(layout.size(280.dp, 220.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x66FFFFFF), Color.Transparent),
                    ),
                    shape = CircleShape,
                )
                .blur(layout.size(90.dp, 70.dp)),
        )
        Box(
            modifier = Modifier
                .offset(
                    x = layout.width(220.dp, 180.dp),
                    y = layout.height(80.dp, 60.dp),
                )
                .size(layout.size(220.dp, 180.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x33FFFFFF), Color.Transparent),
                    ),
                    shape = CircleShape,
                )
                .blur(layout.size(80.dp, 64.dp)),
        )
        Box(
            modifier = Modifier
                .offset(
                    x = layout.width(40.dp, 30.dp),
                    y = layout.height(420.dp, 300.dp),
                )
                .size(layout.size(320.dp, 240.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x55C7E6FF), Color.Transparent),
                    ),
                    shape = CircleShape,
                )
                .blur(layout.size(120.dp, 90.dp)),
        )
        Grain(modifier = Modifier.fillMaxSize())
    }
}

/**
 * Grano finísimo, idéntico en las dos plataformas.
 *
 * `seed` fijo: si el ruido se regenerase en cada recomposición, el fondo
 * «hormiguearía» al desplazar la pantalla.
 */
@Composable
private fun Grain(modifier: Modifier) {
    Box(
        modifier = modifier
            .drawWithCache {
                val seed = 42
                val random = Random(seed)
                val count = ((size.width * size.height) / 6000f).coerceIn(140f, 360f).toInt()
                val points = List(count) {
                    Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                }
                val radii = List(count) { 0.4f + random.nextFloat() * 1.2f }
                val white = Color.White.copy(alpha = 0.05f)
                val black = Color.Black.copy(alpha = 0.02f)

                onDrawBehind {
                    points.forEachIndexed { index, offset ->
                        val color = if (index % 3 == 0) black else white
                        drawCircle(color = color, radius = radii[index], center = offset)
                    }
                }
            },
    )
}
