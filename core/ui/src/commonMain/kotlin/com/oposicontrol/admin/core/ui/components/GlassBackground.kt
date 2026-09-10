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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.theme.GlassTheme
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
    val tokens = GlassTheme.tokens
    // blanco/azul hielo → azul muy pálido → azul hielo algo más profundo.
    //
    // El 0,45 está medido con la captura delante: a 0,78 la parada superior salía
    // prácticamente blanca, y como el cristal de sección es blanco al 58%, panel y
    // fondo daban el mismo valor. El resultado era un panel invisible — cristal
    // que no se lee como cristal porque no hay nada detrás que dejar ver.
    val iceWhite = lerp(tokens.backgroundTop, Color.White, 0.45f)
    val paleBlue = tokens.backgroundTop
    val deepIce = tokens.backgroundDeep
    val haloLight = Color.White
    val haloCool = tokens.info
    val haloWarm = tokens.accent

    Box(
        modifier = modifier
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to iceWhite,
                            0.30f to paleBlue,
                            1f to deepIce,
                        ),
                    ),
                )
                // 1 · Claro alto a la izquierda, bajo la cabecera de página.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(haloLight.copy(alpha = 0.50f), Color.Transparent),
                        center = Offset(size.width * 0.16f, size.height * 0.06f),
                        radius = size.width * 0.46f,
                    ),
                )
                // 2 · Azul frío a la derecha, a la altura de «Necesita tu atención».
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(haloCool.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.86f, size.height * 0.30f),
                        radius = size.width * 0.44f,
                    ),
                )
                // 3 · Coral muy tenue abajo, bajo la rejilla inferior. Es el único
                // sitio del fondo donde aparece el color de marca, y aparece tan
                // bajo que sólo se percibe como temperatura, no como color.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(haloWarm.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.58f, size.height * 1.02f),
                        radius = size.width * 0.50f,
                    ),
                )
            },
    ) {
        Grain(modifier = Modifier.fillMaxSize())
    }
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
