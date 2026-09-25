package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.domain.model.Exam
import oposicontrol.features.store.generated.resources.Res
import oposicontrol.features.store.generated.resources.cover_andalucia
import oposicontrol.features.store.generated.resources.cover_aragon
import oposicontrol.features.store.generated.resources.cover_asturias
import oposicontrol.features.store.generated.resources.cover_baleares
import oposicontrol.features.store.generated.resources.cover_barcelona
import oposicontrol.features.store.generated.resources.cover_canarias
import oposicontrol.features.store.generated.resources.cover_cantabria
import oposicontrol.features.store.generated.resources.cover_castilla_la_mancha
import oposicontrol.features.store.generated.resources.cover_castilla_y_leon
import oposicontrol.features.store.generated.resources.cover_ceuta_melilla
import oposicontrol.features.store.generated.resources.cover_extremadura
import oposicontrol.features.store.generated.resources.cover_galicia
import oposicontrol.features.store.generated.resources.cover_la_rioja
import oposicontrol.features.store.generated.resources.cover_madrid
import oposicontrol.features.store.generated.resources.cover_murcia
import oposicontrol.features.store.generated.resources.cover_navarra
import oposicontrol.features.store.generated.resources.cover_pais_vasco
import oposicontrol.features.store.generated.resources.cover_rafagas_xl
import oposicontrol.features.store.generated.resources.cover_razonados
import oposicontrol.features.store.generated.resources.cover_valencia
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Medidas de la portada. Viven en un solo sitio a propósito: la proporción de
 * la portada no estaba fijada en el contrato, y cuando se decida la definitiva,
 * cambiar estos números es lo único que hace falta.
 *
 * 264×148 es 16:9, la forma que ya usa la tarjeta de catálogo de la app móvil.
 */
object ExamCoverSpec {
    val width: Dp = 264.dp
    val height: Dp = 148.dp
    val padding: Dp = 14.dp
    val radius: Dp = 12.dp

    /** Lado del cuadrado donde se coloca la ilustración (el diseño las entrega en 110×110). */
    val artwork: Dp = 112.dp
}

/**
 * Colores de la portada, FIJOS y no de `GlassTheme`: una portada es una imagen
 * que el usuario final ve igual en claro y en oscuro, y no cambia de color
 * porque el administrador cambie el tema del panel.
 *
 * El fondo es el `#131313` del lienzo de Figma donde están dibujadas las
 * ilustraciones (dorado sobre negro); el degradado sube apenas a un marrón muy
 * oscuro para que la ilustración no flote sobre un plano muerto.
 */
private val CoverBackgroundTop = Color(0xFF1B1915)
private val CoverBackgroundBottom = Color(0xFF131313)
private val CoverGold = Color(0xFFCC9D60)

/** El dibujo concreto de cada ilustración: los vectores del diseño de Figma. */
private fun ExamCoverArtwork.drawable(): DrawableResource = when (this) {
    ExamCoverArtwork.ANDALUCIA -> Res.drawable.cover_andalucia
    ExamCoverArtwork.ARAGON -> Res.drawable.cover_aragon
    ExamCoverArtwork.ASTURIAS -> Res.drawable.cover_asturias
    ExamCoverArtwork.BALEARES -> Res.drawable.cover_baleares
    ExamCoverArtwork.BARCELONA -> Res.drawable.cover_barcelona
    ExamCoverArtwork.CANARIAS -> Res.drawable.cover_canarias
    ExamCoverArtwork.CANTABRIA -> Res.drawable.cover_cantabria
    ExamCoverArtwork.CASTILLA_LA_MANCHA -> Res.drawable.cover_castilla_la_mancha
    ExamCoverArtwork.CASTILLA_Y_LEON -> Res.drawable.cover_castilla_y_leon
    ExamCoverArtwork.CEUTA_MELILLA -> Res.drawable.cover_ceuta_melilla
    ExamCoverArtwork.EXTREMADURA -> Res.drawable.cover_extremadura
    ExamCoverArtwork.GALICIA -> Res.drawable.cover_galicia
    ExamCoverArtwork.LA_RIOJA -> Res.drawable.cover_la_rioja
    ExamCoverArtwork.MADRID -> Res.drawable.cover_madrid
    ExamCoverArtwork.MURCIA -> Res.drawable.cover_murcia
    ExamCoverArtwork.NAVARRA -> Res.drawable.cover_navarra
    ExamCoverArtwork.PAIS_VASCO -> Res.drawable.cover_pais_vasco
    ExamCoverArtwork.VALENCIA -> Res.drawable.cover_valencia
    // El diseño trae dos tamaños de ráfagas (xs y xl); la portada grande usa xl.
    // `cover_rafagas_xs` queda en los recursos para un formato compacto.
    ExamCoverArtwork.RAFAGAS -> Res.drawable.cover_rafagas_xl
    ExamCoverArtwork.RAZONADOS -> Res.drawable.cover_razonados
}

/**
 * Portada COMPUESTA de un examen: la genera la app a partir de sus datos y de la
 * ilustración que le toca ([coverArtworkFor]), sin subir imagen (decisión de
 * producto: la portada se compone con los componentes de la app).
 *
 * Sin ilustración —un inmersivo sin comunidad, o con una que el diseño no
 * cubre— se dibuja el icono genérico de capas en dorado: se ve que falta la
 * ilustración en vez de inventar la de otra comunidad.
 */
@Composable
fun ExamCoverArt(exam: Exam, artwork: ExamCoverArtwork?, modifier: Modifier = Modifier) {
    val type = exam.contentType()
    Box(
        modifier = modifier
            .size(ExamCoverSpec.width, ExamCoverSpec.height)
            .clip(RoundedCornerShape(ExamCoverSpec.radius))
            .background(Brush.verticalGradient(listOf(CoverBackgroundTop, CoverBackgroundBottom)))
            .semantics { contentDescription = "Portada de ${exam.title}" },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 6.dp)
                .size(ExamCoverSpec.artwork),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    painter = painterResource(artwork.drawable()),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(ExamCoverSpec.artwork),
                )
            } else {
                AppIconImage(icon = AppIcon.LAYERS, size = 72.dp, tint = CoverGold.copy(alpha = 0.55f))
            }
        }
        // Titular: la especialidad, no el título del examen. El título completo va
        // en el cuerpo de la tarjeta, donde no se trunca; aquí se leería dos veces.
        Column(modifier = Modifier.padding(ExamCoverSpec.padding)) {
            Text(
                text = exam.specialtyName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(COVER_TEXT_WIDTH),
            )
            val detail = coverDetailLine(exam)
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = CoverGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(COVER_TEXT_WIDTH),
                )
            }
        }
        Text(
            text = type.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = CoverGold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = ExamCoverSpec.padding, bottom = ExamCoverSpec.padding)
                .clip(RoundedCornerShape(50))
                .background(CoverGold.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Ancho del texto: deja libre a la derecha el hueco de la ilustración. */
private val COVER_TEXT_WIDTH: Dp = 132.dp

/** Segunda línea de la portada: año de la convocatoria y carácter oficial. Vacía si no se sabe nada. */
internal fun coverDetailLine(exam: Exam): String = buildList {
    exam.year?.let { add(it.toString()) }
    if (exam.isOfficial) add("Oficial")
}.joinToString(" · ")
