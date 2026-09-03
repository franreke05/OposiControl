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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
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
) {
    val tokens = GlassTheme.tokens
    var focused by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            textFieldValue = TextFieldValue(value)
        }
    }
    val animatedStroke by animateColorAsState(
        targetValue = if (focused) tokens.accent.copy(alpha = 0.48f) else tokens.glassStroke,
        animationSpec = tween(durationMillis = 160),
    )
    val animatedTint by animateColorAsState(
        targetValue = if (focused) tokens.glassFillStrong else tokens.glassFill,
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
                        .padding(horizontal = 12.dp, vertical = if (GlassTheme.isDesktop) 6.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
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
        targetValue = if (focused) tokens.accent.copy(alpha = 0.48f) else tokens.glassStroke,
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
