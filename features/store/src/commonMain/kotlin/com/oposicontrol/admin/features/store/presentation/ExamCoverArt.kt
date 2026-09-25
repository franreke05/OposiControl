package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.Exam

/**
 * Medidas de la portada. Viven en un solo sitio a propósito: la proporción de
 * la portada NO estaba fijada en el contrato (el editor de portada retirado
 * usaba 220×124 «porque se lee como una imagen»), y cuando se decida la
 * definitiva, cambiar estos dos números es lo único que hace falta.
 *
 * 264×148 es 16:9, la forma que ya usa la tarjeta de catálogo de la app móvil.
 */
object ExamCoverSpec {
    val width: Dp = 264.dp
    val height: Dp = 148.dp
    val padding: Dp = 14.dp
    val radius: Dp = 12.dp
}

/**
 * Paleta de una portada. Colores FIJOS, no de `GlassTheme`: una portada es una
 * imagen que el usuario final ve igual en claro y en oscuro, y no debe cambiar
 * de color porque el administrador cambie el tema del panel. Todos dan contraste
 * de al menos 4,5:1 con el texto blanco de encima.
 */
internal data class ExamCoverPalette(
    val top: Color,
    val bottom: Color,
    val artwork: AppIcon,
)

internal fun coverPalette(type: ContentExamType): ExamCoverPalette = when (type) {
    ContentExamType.RAFAGA -> ExamCoverPalette(Color(0xFFC2410C), Color(0xFF7C2D12), AppIcon.CLOCK)
    ContentExamType.RAZONADO -> ExamCoverPalette(Color(0xFF1D4ED8), Color(0xFF1E3A8A), AppIcon.QUESTIONS)
    ContentExamType.INMERSIVO -> ExamCoverPalette(Color(0xFF6D28D9), Color(0xFF3B0764), AppIcon.LAYERS)
}

/**
 * Portada COMPUESTA de un examen: la genera la app a partir de sus datos, no se
 * sube una imagen (decisión de producto del 2026-09-23: la portada se compone
 * con los componentes de la app).
 *
 * De momento la ilustración es un icono translúcido por tipo. Es un marcador de
 * sitio: cuando lleguen los vectores propios de cada tipo de examen, sustituyen
 * a `palette.artwork` sin tocar la composición del texto ni las medidas.
 */
@Composable
fun ExamCoverArt(exam: Exam, modifier: Modifier = Modifier) {
    val type = exam.contentType()
    val palette = coverPalette(type)
    Box(
        modifier = modifier
            .size(ExamCoverSpec.width, ExamCoverSpec.height)
            .clip(RoundedCornerShape(ExamCoverSpec.radius))
            .background(Brush.linearGradient(listOf(palette.top, palette.bottom)))
            .semantics { contentDescription = "Portada de ${exam.title}" },
    ) {
        // Dos círculos y una franja, todos blancos y casi transparentes: dan
        // profundidad sin competir con el texto.
        Canvas(modifier = Modifier.size(ExamCoverSpec.width, ExamCoverSpec.height)) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = 96.dp.toPx(), center = Offset(size.width, 0f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = 64.dp.toPx(), center = Offset(0f, size.height))
        }
        AppIconImage(
            icon = palette.artwork,
            size = 84.dp,
            tint = Color.White.copy(alpha = 0.16f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 8.dp),
        )
        // Titular de la portada: la especialidad, no el título del examen. El
        // título completo va en el cuerpo de la tarjeta, donde no se trunca; si
        // también estuviera aquí se leería dos veces una debajo de la otra.
        Column(modifier = Modifier.padding(ExamCoverSpec.padding)) {
            Text(
                text = exam.specialtyName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(ExamCoverSpec.width - ExamCoverSpec.padding * 2 - 24.dp),
            )
            val detail = coverDetailLine(exam)
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(ExamCoverSpec.width - ExamCoverSpec.padding * 2),
                )
            }
        }
        Text(
            text = type.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = ExamCoverSpec.padding, bottom = ExamCoverSpec.padding)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.22f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Segunda línea de la portada: convocatoria y carácter oficial. Vacía si no se sabe nada. */
internal fun coverDetailLine(exam: Exam): String = buildList {
    exam.year?.let { add("Convocatoria $it") }
    if (exam.isOfficial) add("Oficial")
}.joinToString(" · ")
