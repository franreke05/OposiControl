package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import com.oposicontrol.admin.core.ui.theme.motionTween

/**
 * Significado de un estado, independiente de la palabra concreta.
 *
 * Antes el chip usaba coral para «activo» y `colorScheme.primary` para
 * «pendiente» — y `primary` ES el coral, así que los dos estados se pintaban
 * idénticos. Los tokens `success` y `warning` existían y este componente no los
 * usaba. Separar el tono del texto arregla eso y además permite que una pantalla
 * fije el tono a mano cuando la palabra no baste.
 */
enum class StatusTone {
    /** Todo correcto: activo, publicado, resuelto, operativo. */
    POSITIVE,

    /** En curso o requiere atención: borrador, pendiente, degradado. */
    ATTENTION,

    /** Fallo o bloqueo: error, caído, bloqueado. */
    NEGATIVE,

    /** Sin información. NO es un cero: es que no lo sabemos. */
    UNKNOWN,

    /** Informativo, sin juicio de valor. */
    NEUTRAL,
}

/**
 * Chip de estado.
 *
 * Lleva icono además de color a propósito: un chip que sólo se distingue por el
 * tono deja fuera a quien no separa rojo de verde, y en una tabla de cuarenta
 * filas el color por sí solo tampoco se lee de un vistazo.
 */
@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = statusToneFor(status),
) {
    val tokens = GlassTheme.tokens
    val icon = when (tone) {
        StatusTone.POSITIVE -> AppIcon.CHECK
        StatusTone.ATTENTION -> AppIcon.CLOCK
        StatusTone.NEGATIVE -> AppIcon.BLOCKED
        StatusTone.UNKNOWN -> AppIcon.HELP
        StatusTone.NEUTRAL -> AppIcon.INFO
    }
    val colors = statusChipColors(tokens, tone)

    val animatedBackground by animateColorAsState(
        targetValue = colors.fill,
        animationSpec = motionTween(OposiMotion.Normal),
    )
    val animatedForeground by animateColorAsState(
        targetValue = colors.content,
        animationSpec = motionTween(OposiMotion.Normal),
    )

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 24.dp)
            .background(color = animatedBackground, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "Estado: $status" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconImage(icon = icon, size = 12.dp, tint = animatedForeground)
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = animatedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Chip de estado en formato píldora, para ir SOBRE cristal.
 *
 * Se separa de [StatusChip] porque el sitio donde vive es distinto y las
 * restricciones también: [StatusChip] se dibuja sobre superficie opaca y puede
 * teñir su propio fondo con el color de estado al 12%; aquí el fondo es
 * translúcido y ese mismo tinte se sumaría al degradado que hay detrás, dando un
 * color distinto en cada punto de la pantalla. Por eso el relleno es blanco
 * translúcido —siempre el mismo— y el color del estado se concentra en el punto.
 *
 * El punto NO es el único portador del significado: la etiqueta dice «Ktor
 * operativo», así que quien no distingue verde de ámbar lee exactamente la misma
 * información. Es la misma garantía que en [StatusChip] da el icono.
 */
@Composable
fun StatusDotChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = statusToneFor(label),
) {
    val tokens = GlassTheme.tokens
    val colors = statusDotChipColors(tokens, tone)
    val animatedDot by animateColorAsState(targetValue = colors.dot, animationSpec = motionTween(OposiMotion.Normal))

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 26.dp)
            .clip(Radius.pill)
            .background(colors.fill)
            .border(1.dp, tokens.sectionGlassStroke, Radius.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Estado: $label" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(Radius.pill)
                .background(animatedDot),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Qué color pinta cada chip.
//
// Funciones y no un `when` dentro del composable para que `ContrastTest` mida lo
// que se pinta. El chip de estado se medía como ELEMENTO de interfaz —3:1, el tono
// contra la superficie, sin su propio tinte—, y es TEXTO de 13sp sobre ese tinte:
// en el tema oscuro «Archivado» daba 3,92:1 sobre superficie sólida y «Sin
// guardar» 3,42:1 sobre el cristal de la cabecera, con el test en verde.
// ---------------------------------------------------------------------------

/** El color de un tono de estado: el del texto y el icono de [StatusChip] y el del punto de [StatusDotChip]. */
internal fun statusToneColor(tokens: GlassTokens, tone: StatusTone): Color = when (tone) {
    StatusTone.POSITIVE -> tokens.success
    StatusTone.ATTENTION -> tokens.warning
    StatusTone.NEGATIVE -> tokens.danger
    StatusTone.UNKNOWN -> tokens.textSecondary
    StatusTone.NEUTRAL -> tokens.info
}

/**
 * El CONTENIDO de un chip —su texto y su icono— para cada tono: la TINTA de la
 * familia, no su tono.
 *
 * El tono es el que identifica la familia en un punto, un borde o el tinte del
 * propio chip, y ahí basta 3:1. El texto pide 4,5:1 sobre ese tinte, y en el
 * tema claro el tono no llegaba: verde 2,92:1, ámbar 3,79:1, rojo 4,47:1 y azul
 * 3,77:1 sobre superficie sólida. La tinta es la misma familia con la luz justa
 * para leerse. En oscuro tinta y tono son el mismo color.
 */
internal fun statusToneInk(tokens: GlassTokens, tone: StatusTone): Color = when (tone) {
    StatusTone.POSITIVE -> tokens.successInk
    StatusTone.ATTENTION -> tokens.warningInk
    StatusTone.NEGATIVE -> tokens.dangerInk
    StatusTone.UNKNOWN -> tokens.textSecondary
    StatusTone.NEUTRAL -> tokens.infoInk
}

/** Texto e icono, y relleno, de un [StatusChip]. */
internal data class StatusChipColors(val content: Color, val fill: Color)

/** La tinta en el texto y en el icono; el TONO de la familia al 12 % de relleno. */
internal fun statusChipColors(tokens: GlassTokens, tone: StatusTone): StatusChipColors = StatusChipColors(
    content = statusToneInk(tokens, tone),
    fill = statusToneColor(tokens, tone).copy(alpha = 0.12f),
)

/** Punto, etiqueta y relleno de un [StatusDotChip]. */
internal data class StatusDotChipColors(val dot: Color, val label: Color, val fill: Color)

/**
 * El color del estado sólo en el punto; la etiqueta en `textPrimary` sobre un
 * relleno translúcido que es siempre el mismo, sea cual sea el estado.
 */
internal fun statusDotChipColors(tokens: GlassTokens, tone: StatusTone): StatusDotChipColors = StatusDotChipColors(
    dot = statusToneColor(tokens, tone),
    label = tokens.textPrimary,
    fill = tokens.glassFillStrong,
)

/**
 * Deduce el tono a partir del texto del estado.
 *
 * El vocabulario está escrito en dos idiomas porque el backend devuelve claves en
 * inglés y la interfaz muestra español, y ambos llegan a este componente.
 *
 * Lo importante es el `else`: un estado desconocido cae en [StatusTone.UNKNOWN]
 * y se pinta como «no lo sabemos», no como correcto. Un enum nuevo del backend no
 * puede acabar mostrándose en verde por descarte.
 */
fun statusToneFor(status: String): StatusTone = when (status.trim().lowercase()) {
    "active", "activo", "activa",
    "published", "publicado", "publicada",
    "resolved", "resuelto", "resuelta",
    "success", "correcta", "correcto",
    "visible", "publicable", "guardable",
    "open", "abierto", "abierta",
    "operativo", "operational", "ok", "healthy",
    -> StatusTone.POSITIVE

    "draft", "borrador",
    "scheduled", "programado", "programada", "programable",
    "pending", "pendiente", "en revision", "en revisión",
    "idle", "editable", "incompleta", "incompleto",
    "degradado", "degraded", "warning", "aviso",
    -> StatusTone.ATTENTION

    "inactive", "inactivo", "inactiva",
    "archived", "archivado", "archivada",
    "closed", "cerrado", "cerrada",
    "error", "failed", "fallido",
    "ban", "muted", "bloqueada", "bloqueado",
    "oculta", "oculto", "hidden",
    "caido", "caído", "down", "offline",
    -> StatusTone.NEGATIVE

    // Estados honestos de ausencia de dato. No se convierten en 0 ni en «bien».
    "unknown", "desconocido", "desconocida",
    "not_connected", "no conectado", "sin conexion", "sin conexión",
    "no_data", "sin datos",
    "not_instrumented", "no instrumentado", "sin medicion", "sin medición",
    -> StatusTone.UNKNOWN

    else -> StatusTone.UNKNOWN
}
