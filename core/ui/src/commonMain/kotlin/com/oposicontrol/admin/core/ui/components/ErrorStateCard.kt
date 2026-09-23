package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Estado de error.
 *
 * Antes un fallo se pintaba con la MISMA tarjeta que «no hay datos», con el
 * mensaje técnico crudo como cuerpo y sin forma de reintentar. Dos situaciones
 * que exigen cosas distintas —«no hay nada» invita a crear algo, «ha fallado»
 * invita a reintentar— se veían idénticas.
 *
 * El texto técnico no desaparece: se pliega detrás de «Ver detalle técnico»,
 * porque cuando algo se rompe de verdad ese texto es lo único que sirve para
 * contarlo, pero no es lo que Arturo necesita leer primero.
 */
@Composable
fun ErrorStateCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "No hemos podido cargar los datos",
    technicalDetail: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    var detailVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.danger.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .animateContentSize(animationSpec = tween(180))
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tokens.danger.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(AppIcon.WARNING, size = 18.dp, tint = tokens.danger)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Row(
            modifier = Modifier.padding(top = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onRetry != null) {
                SecondaryButton(
                    text = "Reintentar",
                    onClick = onRetry,
                    size = ButtonSize.COMPACT,
                    icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
                )
            }
            if (technicalDetail != null) {
                GhostButton(
                    text = if (detailVisible) "Ocultar detalle" else "Ver detalle técnico",
                    onClick = { detailVisible = !detailVisible },
                    size = ButtonSize.COMPACT,
                )
            }
        }
        AnimatedVisibility(visible = detailVisible && technicalDetail != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.surfaceMuted)
                    .padding(Spacing.sm),
            ) {
                Text(
                    text = technicalDetail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * Traduce un [AppError] a algo que se pueda leer sin saber qué es un 401.
 *
 * `Validation` es la excepción deliberada: ese mensaje ya viene redactado para
 * quien usa la pantalla («El precio no puede ser negativo») y sustituirlo por un
 * texto genérico perdería la única información útil.
 */
fun AppError.humanMessage(): String = when (this) {
    is AppError.Network -> when (code) {
        408, 504 -> "El servidor ha tardado demasiado en responder."
        in 500..599 -> "El servidor no está respondiendo bien ahora mismo."
        else -> "No hemos podido conectar con el servidor. Comprueba tu conexión."
    }
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "Tu usuario no tiene permiso para ver esto."
    is AppError.NotFound -> "No hemos encontrado lo que buscabas. Puede que se haya eliminado."
    is AppError.Validation -> message
    is AppError.Unknown -> "Ha ocurrido un error inesperado."
}

/**
 * Si tiene sentido ofrecer «Reintentar».
 *
 * En un fallo de permisos o de validación el botón sería una mentira: volver a
 * pulsar dará exactamente el mismo resultado.
 */
fun AppError.isRetryable(): Boolean = when (this) {
    is AppError.Network -> true
    is AppError.Unknown -> true
    is AppError.Authentication -> false
    is AppError.Authorization -> false
    is AppError.NotFound -> false
    is AppError.Validation -> false
}
