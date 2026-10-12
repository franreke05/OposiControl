package com.oposicontrol.admin.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.inter_medium
import oposicontrol.core.ui.generated.resources.inter_regular
import oposicontrol.core.ui.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * La tipografia de OposiBot: Inter, con los MISMOS tres ficheros (Regular, Medium, SemiBold) que empaqueta la app
 * (`shared/src/commonMain/composeResources/font`, SIL Open Font License 1.1).
 *
 * El panel se pinta con Manrope. Esto existe para pintar contenido que la app ensenara —el banner de una noticia— con
 * la letra con la que se vera alli: con otra familia, una linea que cabe en el editor puede partirse en la app.
 *
 * No hay Bold a proposito: la app tampoco lo tiene. Un peso `bold` se resuelve al mas cercano (SemiBold) en los dos
 * sitios, y como SemiBold ya es 600 ninguno de los dos sintetiza una negrita falsa.
 */
@Composable
fun oposiBotFontFamily(): FontFamily {
    val regular = Font(Res.font.inter_regular, FontWeight.Normal)
    val medium = Font(Res.font.inter_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.inter_semibold, FontWeight.SemiBold)
    return remember(regular, medium, semiBold) { FontFamily(regular, medium, semiBold) }
}
