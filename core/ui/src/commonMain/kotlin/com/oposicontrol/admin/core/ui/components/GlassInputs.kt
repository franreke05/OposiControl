package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Sizing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
    /**
     * Alto mínimo, en líneas, de un campo multilínea.
     *
     * Existe para que un texto largo —una descripción— se lea como un área de
     * texto y no como un input de una línea que crece según se escribe: un hueco
     * de una línea invita a escribir una línea. Con `minLines > 1` el texto y el
     * placeholder se anclan ARRIBA, como en cualquier área de texto; con el valor
     * por defecto (`1`) el campo se compone exactamente igual que antes, así que
     * los campos que ya existían no cambian. Sólo cuenta con `singleLine = false`.
     */
    minLines: Int = 1,
    /**
     * Pinta el borde en el color de peligro.
     *
     * El foco manda sobre el error ([fieldBorderColor]): mientras se está
     * escribiendo en el campo, el borde dice «aquí estás», y lo que dice qué falla
     * es la línea de apoyo de abajo, que no se mueve.
     */
    isError: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    val multiline = !singleLine && minLines > 1
    var focused by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            textFieldValue = TextFieldValue(value)
        }
    }
    val animatedStroke by animateColorAsState(
        targetValue = fieldBorderColor(tokens, focused = focused, isError = isError),
        animationSpec = tween(durationMillis = 160),
    )
    // El foco NO cambia el relleno en escritorio, y esto es una regla del sistema,
    // no un ajuste de esta pantalla: **un input es siempre más opaco que el
    // cristal que lo contiene**, en los dos temas.
    //
    // `glassFillStrong` resuelve a `surfaceMuted` en escritorio, que es la
    // superficie HUNDIDA. Al enfocar, el campo se acercaba al valor compuesto de
    // su propio contenedor de cristal —en claro quedaban 7 puntos de luminancia
    // entre los dos, y en la paleta oscura habrían quedado 3—, así que justo al
    // ponerse a escribir el campo empezaba a desaparecer. El foco lo dice el
    // anillo coral del borde, que ya está y no depende de la superficie.
    //
    // En móvil se conserva el refuerzo translúcido: allí el campo se dibuja sobre
    // una tarjeta de cristal de verdad y el cambio de relleno sí aporta.
    val animatedTint by animateColorAsState(
        targetValue = when {
            !focused -> tokens.glassFill
            GlassTheme.isDesktop -> tokens.glassFill
            else -> tokens.glassFillStrong
        },
        animationSpec = tween(durationMillis = 160),
    )
    BasicTextField(
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
            onValueChange(it.text)
        },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines.coerceAtLeast(1),
        textStyle = textStyle.copy(color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.accent),
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = if (GlassTheme.isDesktop) Sizing.inputHeight else 54.dp),
                shape = RoundedCornerShape(if (GlassTheme.isDesktop) 10.dp else 16.dp),
                tint = animatedTint,
                strokeColor = animatedStroke,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 12.dp,
                            vertical = when {
                                !GlassTheme.isDesktop -> 12.dp
                                // Un área de texto ancla arriba: 10dp dejan su primera
                                // línea a la altura a la que queda la de un campo de una
                                // línea centrado en 40dp, así las dos se alinean en fila.
                                multiline -> 10.dp
                                else -> 6.dp
                            },
                        ),
                    verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
                    ) {
                        if (textFieldValue.text.isBlank()) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = tokens.textSecondary,
                            )
                        }
                        innerTextField()
                    }
                    trailingContent?.invoke()
                }
            }
        },
    )
}

/**
 * El borde de un campo de formulario —texto, buscador, selector— en cada estado.
 *
 * Es la ÚNICA frontera del campo: en escritorio el campo tiene el mismo relleno
 * que la sección que lo contiene, así que WCAG 1.4.11 le pide 3:1 al borde contra
 * lo que tiene a los dos lados, en todos sus estados. Antes:
 *
 *  - en reposo, `glassStroke` → `border`: 1,25:1 en claro y 1,41:1 en oscuro;
 *  - enfocado, `accent` al 48 %: 1,49:1 en claro. Ponerse a escribir hacía el
 *    borde MÁS débil que en reposo;
 *  - con error, `danger` al 55 %: 2,44:1 en claro.
 *
 * Ahora el reposo es [GlassTokens.fieldBorder]; el foco, `accentInk` opaco (el
 * coral sigue diciendo «foco», 4,56:1 en claro y 6,04:1 en oscuro); el error,
 * `danger` opaco. Un campo DESHABILITADO conserva el trazo de cristal: un control
 * inactivo está fuera de 1.4.11 y no debe parecer tan disponible como uno activo.
 *
 * Es una función para que `ContrastTest` mida exactamente lo que se pinta.
 */
internal fun fieldBorderColor(
    tokens: GlassTokens,
    focused: Boolean,
    isError: Boolean = false,
    enabled: Boolean = true,
): Color = when {
    focused -> tokens.accentInk
    isError -> tokens.danger
    !enabled -> tokens.glassStroke
    else -> tokens.fieldBorder
}

/**
 * La línea de apoyo bajo un campo: gris cuando explica, roja cuando acusa.
 *
 * Es una función y no un `if` dentro del composable por la misma razón que
 * [fieldBorderColor]: `ContrastTest` mide funciones puras. El apoyo de un campo es
 * TEXTO de 13sp, así que pide 4,5:1 sobre las dos superficies en las que puede
 * caer —la sólida de una sección y la hundida de un campo de sólo lectura— en los
 * dos temas.
 */
internal fun fieldSupportColor(tokens: GlassTokens, isError: Boolean): Color =
    if (isError) tokens.danger else tokens.textSecondary

/**
 * El borde de un campo de SÓLO LECTURA.
 *
 * Un campo inerte no tiene borde propio: se distingue por su superficie hundida
 * (`surfaceMuted`), y por eso el trazo por defecto es el de cristal —el que
 * [GlassSurface] resuelve a `border` en escritorio—, que es el filete de una caja,
 * no la frontera de un control.
 *
 * Con error sí lo tiene, y hace falta: el campo culpable de un rechazo del
 * servidor puede ser justo uno de sólo lectura («Número de preguntas» de un
 * examen), y ahí la superficie hundida no cambia de color ni puede hacerlo: es lo
 * que dice «esto no se escribe». Sin este borde, un campo inerte marcado en rojo
 * se vería exactamente igual que uno correcto.
 */
internal fun readOnlyFieldBorderColor(tokens: GlassTokens, isError: Boolean): Color =
    if (isError) tokens.danger else tokens.glassStroke

@Composable
fun GlassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Buscar",
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val affordanceStyle = MaterialTheme.typography.labelMedium
    var focused by remember { mutableStateOf(false) }
    val animatedStroke by animateColorAsState(
        targetValue = fieldBorderColor(tokens, focused = focused),
        animationSpec = tween(durationMillis = 160),
    )
    GlassSurface(
        modifier = modifier.defaultMinSize(minHeight = if (GlassTheme.isDesktop) Sizing.inputHeight else 52.dp),
        shape = RoundedCornerShape(if (GlassTheme.isDesktop) 10.dp else 16.dp),
        tint = tokens.glassFill,
        strokeColor = animatedStroke,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Un icono, no la palabra «Buscar». La etiqueta de texto se leía
            // pegada al placeholder y salía «Buscar  Buscar por texto o
            // etiquetas»: la misma palabra dos veces en el mismo control.
            AppIconImage(
                icon = AppIcon.SEARCH,
                size = 16.dp,
                tint = tokens.textSecondary,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.accent),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = tokens.textSecondary,
                        )
                    }
                    innerTextField()
                },
            )
            AnimatedVisibility(visible = value.isNotBlank()) {
                DesktopIconButton(
                    label = "Limpiar búsqueda",
                    onClick = { onValueChange("") },
                    size = 24.dp,
                ) {
                    AppIconImage(AppIcon.CLOSE, size = 13.dp)
                }
            }
        }
    }
}
