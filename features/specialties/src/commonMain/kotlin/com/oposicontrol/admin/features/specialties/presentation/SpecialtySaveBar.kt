package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/** Etiqueta de prueba de la barra de guardado. Sólo semántica: no se pinta. */
internal const val SPECIALTY_SAVE_BAR_TAG: String = "specialty-save-bar"

/**
 * La acción del formulario completo, no de la pestaña abierta.
 *
 * Vive fuera del scroll de las pestañas porque guarda la especialidad entera:
 * quien cambia el nombre en Identidad y apaga un tipo en Tipos de examen guarda
 * las dos cosas con este botón, y tenerlo siempre a la vista es lo que evita
 * salir de la pantalla creyendo que se había guardado.
 *
 * **Lo que esta barra dice y otras no: son DOS escrituras.** Identidad va a
 * `especialidades` por `PATCH` (o `POST` en un alta) y los tipos van a
 * `capacidades_especialidad` por `PUT`. Son dos endpoints, dos tablas y dos
 * resultados posibles, y la línea de detalle los enumera con su ruta. No es un
 * adorno técnico: si la segunda falla, la primera ya está escrita, y sin
 * decirlo nadie sabría qué quedó guardado.
 *
 * Qué dice y qué ofrece lo decide [specialtySaveBarStatus], fuera del
 * composable. La barra **no se oculta** cuando no hay cambios: mide y ocupa su
 * hueco en reposo con relleno y borde transparentes, porque ocultarla cambiaría
 * el alto del formulario en la primera tecla.
 */
@Composable
internal fun SpecialtySaveBar(
    state: SpecialtiesState,
    onIntent: (SpecialtiesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val status = specialtySaveBarStatus(state)
    val prominent = status.prominent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(if (prominent) specialtySaveBarProminentFill(tokens) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (prominent) tokens.warning.copy(alpha = 0.45f) else Color.Transparent,
                shape = Radius.card,
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.Top,
        ) {
            status.message?.let { message ->
                if (prominent) {
                    AppIconImage(
                        icon = AppIcon.WARNING,
                        size = Sizing.icon,
                        tint = specialtySaveBarToneColor(message.tone, tokens),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(7.dp)
                            .clip(Radius.pill)
                            .background(specialtySaveBarToneColor(message.tone, tokens)),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = message.text,
                        style = if (prominent) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal,
                        color = specialtySaveBarTextColor(message.tone, prominent, tokens),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // La línea que nombra las escrituras. Aquí, y no en una ayuda
                    // contextual, porque es la información que hace falta ANTES de
                    // pulsar.
                    status.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (status.canDiscard) {
            SecondaryButton(
                text = "Descartar",
                onClick = { onIntent(SpecialtiesIntent.DiscardChanges) },
            )
        }
        PrimaryButton(
            text = status.primaryLabel,
            onClick = { onIntent(SpecialtiesIntent.Save) },
            enabled = status.canSave,
            loading = status.saving,
        )
    }
}
