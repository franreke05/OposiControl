package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Las tres plantillas de fondo de una noticia (`tipoFondo` A, B y C en el servidor; SKY, MINT y GOLD en el editor).
 *
 * Son CONTENIDO, no cromo: colores fijos, iguales con el panel en claro o en oscuro, y los mismos que pinta el editor
 * movil (`MobileLayeredNewsEditor`). Antes el lienzo de escritorio las sacaba de los tokens del tema, asi que la misma
 * noticia se veia distinta segun el tema de quien la editaba, y distinta en el movil.
 *
 * Lo que aqui se pinta es el contrato de pintado que tiene que reproducir la app (docs/integration/NEWS_RENDERING_CONTRACT.md).
 */
internal data class NewsTemplate(
    /** Degradado lineal de arriba-izquierda a abajo-derecha. */
    val background: List<Color>,
    val accent: Color,
)

private val TEMPLATE_BASE = listOf(Color(0xFF1C2535), Color(0xFF141B26), Color(0xFF0E141D))

internal fun newsTemplate(backgroundType: String): NewsTemplate = when (backgroundType.toNewsBackgroundStyle()) {
    "MINT" -> NewsTemplate(TEMPLATE_BASE, accent = Color(0xFF6EF3D5))
    "GOLD" -> NewsTemplate(TEMPLATE_BASE, accent = Color(0xFFE9C985))
    else -> NewsTemplate(TEMPLATE_BASE, accent = Color(0xFF89D5FF))
}

/** Tinta de una capa de texto sin color propio. Fija: el fondo de una noticia siempre es oscuro. */
internal val NEWS_DOCUMENT_INK: Color = Color.White

/** Velo sobre una imagen de fondo, para que el texto claro se lea encima de cualquier foto. */
internal val NEWS_IMAGE_SCRIM: Color = Color.Black.copy(alpha = 0.28f)

/** Borde del banner en OposiBot: 0,5 px de blanco al 40 % (`BannerAtomo`). */
internal val NEWS_BANNER_BORDER: Color = Color.White.copy(alpha = 0.40f)

/**
 * El dibujo de la plantilla cuando no hay imagen de fondo: un velo oscuro a la izquierda (donde va el texto) y dos ondas,
 * una del color de la plantilla y otra blanca. Todo en proporciones del lienzo, para que escale igual a cualquier ancho.
 */
@Composable
internal fun NewsTemplateArtwork(template: NewsTemplate, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val wave = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.78f)
            cubicTo(size.width * 0.34f, size.height * 0.18f, size.width * 0.68f, size.height * 0.86f, size.width * 1.02f, size.height * 0.22f)
        }
        val secondWave = Path().apply {
            moveTo(size.width * 0.04f, size.height * 0.62f)
            cubicTo(size.width * 0.30f, size.height * 0.06f, size.width * 0.62f, size.height * 0.72f, size.width * 0.92f, size.height * 0.12f)
        }
        drawRect(brush = Brush.horizontalGradient(listOf(Color(0xE6101722), Color(0x99121A26), Color.Transparent)))
        drawPath(
            path = wave,
            brush = Brush.linearGradient(listOf(template.accent.copy(alpha = 0.48f), Color.Transparent)),
            style = Stroke(width = size.minDimension * 0.025f, cap = StrokeCap.Round),
        )
        drawPath(
            path = secondWave,
            brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)),
            style = Stroke(width = size.minDimension * 0.007f, cap = StrokeCap.Round),
        )
    }
}
