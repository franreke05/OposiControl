package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam

/**
 * La acción del formulario completo, no de la pestaña abierta.
 *
 * Vive fuera del scroll de las pestañas porque guarda el examen entero: quien
 * cambia la duración en Configuración y el título en Información básica guarda
 * las dos cosas con este botón, y tenerlo siempre a la vista es lo que evita
 * salir de la pantalla creyendo que se había guardado.
 *
 * Qué dice y qué ofrece lo decide [examSaveBarStatus], fuera del composable, y
 * son tres cosas distintas:
 *
 *  - **Cambios sin guardar**: la barra se vuelve PROMINENTE —icono de aviso,
 *    frase en peso, «Descartar» y el primario—. Un botón pequeño en la esquina no
 *    avisaba de nada: el estado «esto no está guardado» sólo se leía en el
 *    resumen lateral, que en ventana estrecha ni siquiera es una columna.
 *  - **Sin cambios**: ni aviso ni «Descartar», y el primario deshabilitado. La
 *    barra NO se oculta: mide y justifica el hueco en su sitio, porque ocultarla
 *    cambiaría el alto del área que desplaza el formulario cada vez que se escribe
 *    la primera letra. Por la misma razón el relleno y el borde de la forma
 *    prominente existen también en reposo, transparentes: pasar a «sucio» cambia
 *    el color, nunca el alto.
 *  - **Examen nuevo**: una línea informativa, y el primario se llama «Crear
 *    examen» porque es el que lo crea. La acción de la página se llama «Nuevo
 *    examen» y sólo abre un borrador: dos botones coral con la misma etiqueta y
 *    efectos distintos no pueden convivir.
 */
@Composable
internal fun ExamSaveBar(
    state: ExamsState,
    exam: Exam?,
    onIntent: (ExamsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val status = examSaveBarStatus(state, exam)
    val prominent = status.prominent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(if (prominent) examSaveBarProminentFill(tokens) else Color.Transparent)
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            status.message?.let { message ->
                if (prominent) {
                    AppIconImage(
                        icon = AppIcon.WARNING,
                        size = Sizing.icon,
                        tint = examSaveBarToneColor(message.tone, tokens),
                    )
                } else {
                    StatusDot(color = examSaveBarToneColor(message.tone, tokens))
                }
                Text(
                    text = message.text,
                    style = if (prominent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal,
                    color = examSaveBarTextColor(message.tone, prominent, tokens),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (status.canDiscard) {
            val examId = state.selectedExamId
            SecondaryButton(
                text = "Descartar",
                // `SelectExam` y no `SwitchExam`: descartar ES volver a abrir el
                // examen guardado, así que preguntar aquí sería preguntar dos veces
                // por lo mismo.
                onClick = { if (examId != null) onIntent(ExamsIntent.SelectExam(examId)) },
            )
        }
        PrimaryButton(
            text = status.primaryLabel,
            onClick = { onIntent(ExamsIntent.SaveExam) },
            enabled = status.canSave,
            loading = status.saving,
        )
    }
}

/** Punto de color de 7dp. El mismo que usan el resumen y la franja de estado. */
@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(Radius.pill)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Colores de la barra.
//
// Funciones y no un `when` dentro del composable para que el test de contraste
// mida exactamente lo que se pinta, con la paleta viva de los dos temas.
// ---------------------------------------------------------------------------

/** El color que PORTA el tono: el del icono o el del punto. Elemento de interfaz, 3:1. */
internal fun examSaveBarToneColor(tone: ExamSaveBarTone, tokens: GlassTokens): Color = when (tone) {
    ExamSaveBarTone.INFO -> tokens.info
    ExamSaveBarTone.WARNING -> tokens.warning
    ExamSaveBarTone.DANGER -> tokens.danger
    ExamSaveBarTone.SUCCESS -> tokens.success
}

/**
 * El color del TEXTO de la barra.
 *
 * El tono lo lleva el icono o el punto; el texto se lee. Sobre el relleno ámbar
 * de la forma prominente va siempre en `textPrimary`: el rojo de peligro encima
 * de ese relleno se queda en ~4,3:1 en el tema claro, por debajo del 4,5:1 de un
 * texto normal. Fuera de la forma prominente, un error sí va en `danger` —sobre
 * el cristal del editor llega— y el resto en `textSecondary`: el verde de éxito
 * no sostiene texto en claro.
 */
internal fun examSaveBarTextColor(tone: ExamSaveBarTone, prominent: Boolean, tokens: GlassTokens): Color = when {
    prominent -> tokens.textPrimary
    tone == ExamSaveBarTone.DANGER -> tokens.danger
    else -> tokens.textSecondary
}

/** Relleno de la barra con cambios sin guardar: el ámbar del aviso, muy bajo. */
internal fun examSaveBarProminentFill(tokens: GlassTokens): Color = tokens.warning.copy(alpha = 0.10f)
