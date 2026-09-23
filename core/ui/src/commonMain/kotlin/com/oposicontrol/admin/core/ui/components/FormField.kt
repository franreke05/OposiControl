package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Campo de formulario con etiqueta propia.
 *
 * Sube al Design System porque no tiene nada de una pantalla concreta: etiqueta
 * encima, asterisco cuando es obligatorio, icono de ayuda opcional al lado del
 * nombre y una variante de sólo lectura para los valores que el sistema calcula
 * y el usuario no escribe. Cada pantalla que lo necesitaba lo estaba
 * redibujando a mano, y por eso el asterisco, el hueco entre etiqueta y campo y
 * el tratamiento del valor calculado eran distintos en cada una.
 *
 * [readOnly] no es un input deshabilitado: es una superficie hundida
 * (`surfaceMuted`) sin cursor de texto. La diferencia importa —un campo gris que
 * parece editable y no lo es hace perder tiempo—, y el caso de uso real es el
 * número de preguntas de un examen, que se deriva de las preguntas vinculadas.
 *
 * [isError] marca el campo cuando algo lo señala a él y no al formulario entero.
 * Funciona en las DOS ramas, escribible e inerte: ver su propia documentación.
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    help: HelpTopic? = null,
    required: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    supportingText: String? = null,
    maxWidth: Dp = Sizing.inputTextMaxWidth,
    /**
     * Alto mínimo en líneas de un campo multilínea. Ver [GlassTextField].
     *
     * `1` por defecto: los campos que ya existían no cambian de alto ni de
     * alineación. Sólo cuenta con `singleLine = false`.
     */
    minLines: Int = 1,
    /**
     * Pinta el borde y la línea de apoyo en el color de peligro.
     *
     * Es el mismo parámetro que ya tenía [DropdownField], con el mismo
     * `fieldBorderColor` detrás, porque el estado de error de un campo no puede
     * depender de qué clase de campo sea. Existe para que un rechazo del servidor
     * pueda señalar EL campo culpable: una línea roja al pie de un formulario de
     * cuatro pestañas dice que algo está mal y no dice dónde.
     *
     * **También en la rama [readOnly].** El campo culpable puede ser precisamente
     * uno inerte —«Número de preguntas» de un examen—, y ahí no hay borde de
     * control que cambiar: lo pone [readOnlyFieldBorderColor] sobre la superficie
     * hundida, que se queda como está. Marcar sólo los campos escribibles dejaría
     * sin señal justo el caso en el que el administrador más la necesita, porque
     * es el que no puede corregir escribiendo.
     */
    isError: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier.fillMaxWidth().widthIn(max = maxWidth),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        FieldLabel(label = label, required = required, help = help)
        if (readOnly) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = Radius.md,
                tint = tokens.surfaceMuted,
                strokeColor = readOnlyFieldBorderColor(tokens, isError = isError),
            ) {
                Box(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 10.dp)) {
                    Text(
                        text = value.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.textSecondary,
                    )
                }
            }
        } else {
            GlassTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = placeholder,
                singleLine = singleLine,
                minLines = minLines,
                isError = isError,
            )
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = fieldSupportColor(tokens, isError = isError),
            )
        }
    }
}

/**
 * Selector cerrado con la misma forma y altura que [LabeledField].
 *
 * Existe para que en una misma fila no se note qué campo es texto libre y cuál
 * es una lista: cuando un selector se pinta como una fila de chips y el de al
 * lado como un input, deja de estar claro que los dos son campos del mismo
 * formulario.
 */
@Composable
fun DropdownField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    placeholder: String = "Selecciona",
    maxWidth: Dp = Sizing.inputTextMaxWidth,
    /**
     * Línea de apoyo bajo el control. [LabeledField] ya la tenía y este no, así
     * que un selector que necesitaba explicar algo —«si eliges otra, se perderá la
     * referencia»— sólo podía hacerlo con un `Text` suelto debajo, desalineado y
     * con su propio estilo.
     */
    supportingText: String? = null,
    /** Pinta el borde y la línea de apoyo en el color de peligro. */
    isError: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        FieldLabel(label = label, required = required, help = help)
        Box(modifier = Modifier.widthIn(max = maxWidth)) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Sizing.inputHeight)
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .clickable(enabled = enabled) { expanded = true },
                shape = Radius.md,
                tint = if (enabled) tokens.glassFill else tokens.surfaceMuted,
                strokeColor = fieldBorderColor(tokens, focused = expanded, isError = isError, enabled = enabled),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedLabel.ifBlank { placeholder },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selectedLabel.isBlank()) tokens.textSecondary else tokens.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppIconImage(AppIcon.CHEVRON_DOWN, size = 16.dp, tint = tokens.textSecondary)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                // La misma función que [LabeledField]: dos rojos distintos para el
                // mismo estado en dos campos de la misma fila es peor que ninguno.
                color = fieldSupportColor(tokens, isError = isError),
            )
        }
    }
}

/**
 * Etiqueta de campo, con su asterisco y su ayuda.
 *
 * Se expone porque un campo compuesto —un selector de fichero, un bloque de
 * chips— necesita exactamente la misma etiqueta que un input, y sin esto acaba
 * copiando el `labelMedium` + `textSecondary` a mano y desalineándose 2dp.
 */
@Composable
fun FieldLabel(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    help: HelpTopic? = null,
) {
    Row(
        // Alto fijo, tenga ayuda o no.
        //
        // Sin esto, la etiqueta de un campo CON icono de ayuda mide lo que mide
        // el botón del icono y la de un campo sin ayuda mide lo que mide su
        // texto: ~10dp de diferencia. En una fila de tres campos donde sólo uno
        // lleva ayuda —«Especialidad · Comunidad autónoma · Año»— eso baja el
        // campo de al lado y la fila deja de tener una línea base común.
        modifier = modifier.defaultMinSize(minHeight = Sizing.iconButtonSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (required) "$label *" else label,
            style = MaterialTheme.typography.labelMedium,
            color = GlassTheme.tokens.textSecondary,
        )
        help?.let { InfoHelpButton(it) }
    }
}
