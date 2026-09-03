package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val (foreground, icon) = when (tone) {
        StatusTone.POSITIVE -> tokens.success to AppIcon.CHECK
        StatusTone.ATTENTION -> tokens.warning to AppIcon.CLOCK
        StatusTone.NEGATIVE -> tokens.danger to AppIcon.BLOCKED
        StatusTone.UNKNOWN -> tokens.textSecondary to AppIcon.HELP
        StatusTone.NEUTRAL -> tokens.info to AppIcon.INFO
    }

    val animatedBackground by animateColorAsState(
        targetValue = foreground.copy(alpha = 0.12f),
        animationSpec = tween(180),
    )
    val animatedForeground by animateColorAsState(
        targetValue = foreground,
        animationSpec = tween(180),
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
