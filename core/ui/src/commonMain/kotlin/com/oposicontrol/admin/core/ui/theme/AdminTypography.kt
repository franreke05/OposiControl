package com.oposicontrol.admin.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.ui.layout.AppLayoutMetrics

/**
 * Escala tipográfica FIJA de escritorio.
 *
 * Por qué fija: la escala móvil ([mobileTypography]) deriva el tamaño de letra de
 * `min(ancho/390, alto/844)`. En una ventana de escritorio el ancho siempre gana,
 * así que el divisor real acaba siendo la ALTURA — y el cuerpo de texto pasa a
 * depender de cuán alta esté la ventana:
 *
 *     1366×768   → factor 0,91 → H1 29sp, caption 11sp
 *     1920×1080  → factor 1,28 → H1 41sp, botón 20sp
 *     2560×1440  → factor 1,71 → H1 55sp, botón 27sp
 *
 * Además de producir títulos enormes en monitores grandes, hacía que arrastrar el
 * borde inferior de la ventana redimensionase todo el texto en vivo. Un panel
 * administrativo necesita lo contrario: el mismo tamaño de letra siempre, y que
 * lo que cambie con la ventana sea cuánta información cabe, no cómo de grande se
 * ve cada dato.
 *
 * Los tamaños salen de la jerarquía acordada: H1 28–32 · H2 22–26 · H3 18–20 ·
 * cuerpo 14–16 · tabla 13–14 · botones 14–15 · caption 12–13.
 */
fun desktopTypography(manrope: FontFamily): Typography {
    fun style(
        size: Int,
        weight: FontWeight,
        lineHeight: Float = 1.45f,
        letterSpacing: Float = 0f,
    ) = TextStyle(
        fontFamily = manrope,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
        letterSpacing = letterSpacing.sp,
    )

    return Typography(
        // H1 — título de pantalla.
        displayLarge = style(30, FontWeight.SemiBold, lineHeight = 1.25f, letterSpacing = -0.4f),
        /*
         * CIFRA de una tarjeta de dato — KPI, tarjeta de atención.
         *
         * Es el peldaño que faltaba, y faltaba de una forma concreta: la escala
         * saltaba de 23sp (`headlineSmall`) a 30sp (`displayLarge`), así que una
         * cifra sólo podía elegir entre quedarse a 23 —el mismo cuerpo que un
         * título de sección, con lo que no dominaba su propia tarjeta— o subir a
         * 30 y pesar tanto como el título de la pantalla entera.
         *
         * 27sp resuelve las dos cosas: manda con claridad dentro de la tarjeta y
         * sigue por debajo del H1. `lineHeight` 1,15 porque una cifra es una sola
         * línea y el interlineado de párrafo sólo le añadiría hueco muerto.
         */
        headlineMedium = style(27, FontWeight.SemiBold, lineHeight = 1.15f, letterSpacing = -0.4f),
        // H2 — título de sección mayor / valor destacado.
        headlineSmall = style(23, FontWeight.SemiBold, lineHeight = 1.3f, letterSpacing = -0.2f),
        // H3 — cabecera de card o bloque.
        titleLarge = style(19, FontWeight.SemiBold, lineHeight = 1.35f),
        // Subtítulo / etiqueta fuerte dentro de una sección.
        titleMedium = style(15, FontWeight.SemiBold, lineHeight = 1.4f),
        /*
         * Nombre de una tarjeta de dato y cifra de una fila de lista.
         *
         * Estaba en uso —el panel lo pide en dos sitios, y otras nueve pantallas
         * más— pero NO estaba definido, así que Material lo resolvía con su
         * default: `FontFamily.Default` a 14sp Medium. En pantalla eso es la
         * fuente del sistema, no Manrope, con otra métrica y otro peso justo al
         * lado de texto que sí es Manrope. Definirlo no es un cambio de escala,
         * es cerrar un agujero por el que se colaba otra tipografía.
         */
        titleSmall = style(14, FontWeight.SemiBold, lineHeight = 1.35f),
        // Cuerpo principal.
        bodyLarge = style(15, FontWeight.Normal),
        // Cuerpo secundario y celdas de tabla.
        bodyMedium = style(14, FontWeight.Normal, lineHeight = 1.4f),
        // Caption y texto de apoyo.
        bodySmall = style(13, FontWeight.Normal, lineHeight = 1.4f),
        // Texto de botón.
        labelLarge = style(14, FontWeight.SemiBold, lineHeight = 1.2f),
        // Label de campo de formulario y cabecera de columna.
        labelMedium = style(13, FontWeight.SemiBold, lineHeight = 1.3f),
        // Cabecera de grupo en la sidebar (va en mayúsculas).
        labelSmall = style(12, FontWeight.SemiBold, lineHeight = 1.3f, letterSpacing = 0.5f),
    )
}

/**
 * Escala móvil — se conserva EXACTAMENTE como estaba.
 *
 * El móvil sí quiere escalar con el dispositivo: entre un teléfono de 320dp y uno
 * de 430dp la diferencia de tamaño físico es real y el factor 0,82–1,10 la
 * compensa bien. El problema nunca fue esta fórmula, sino aplicarla a ventanas de
 * escritorio para las que nunca se diseñó.
 */
fun mobileTypography(manrope: FontFamily, layout: AppLayoutMetrics): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = layout.text(32.sp, 28.sp),
        letterSpacing = (-0.5f * layout.contentScale).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = layout.text(24.sp, 21.sp),
    ),
    titleLarge = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = layout.text(20.sp, 18.sp),
    ),
    titleMedium = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = layout.text(16.sp, 15.sp),
    ),
    bodyLarge = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.Normal,
        fontSize = layout.text(15.sp, 14.sp),
    ),
    bodyMedium = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.Normal,
        fontSize = layout.text(13.sp, 12.sp),
    ),
    bodySmall = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.Normal,
        fontSize = layout.text(12.sp, 11.sp),
    ),
    labelLarge = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = layout.text(12.sp, 11.sp),
    ),
    labelMedium = TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = layout.text(11.sp, 10.sp),
    ),
)
