package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.UiRadii
import com.oposicontrol.admin.core.ui.layout.UiSizes
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun GlassField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportingText: String? = null,
    errorText: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(UiSpacing.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.xxs)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.textPrimary,
            )
            if (required) {
                Text(
                    text = "*",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.error,
                    modifier = Modifier.semantics {
                        contentDescription = "Campo obligatorio"
                    },
                )
            }
        }
        GlassFieldCore(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            textStyle = textStyle,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            accessibilityLabel = label,
            errorText = errorText,
        )
        AnimatedVisibility(visible = errorText != null || supportingText != null) {
            Text(
                text = errorText ?: supportingText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (errorText != null) tokens.error else tokens.textSecondary,
                modifier = Modifier.semantics {
                    if (errorText != null) {
                        liveRegion = LiveRegionMode.Polite
                        error(errorText)
                    }
                },
            )
        }
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    GlassFieldCore(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = true,
        readOnly = false,
        singleLine = singleLine,
        textStyle = textStyle,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions.Default,
        keyboardActions = KeyboardActions.Default,
        accessibilityLabel = placeholder,
        errorText = null,
    )
}

@Composable
private fun GlassFieldCore(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    singleLine: Boolean,
    textStyle: TextStyle,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    accessibilityLabel: String,
    errorText: String?,
) {
    val tokens = GlassTheme.tokens
    var focused by remember { mutableStateOf(false) }
    val animatedStroke by animateColorAsState(
        targetValue = when {
            errorText != null -> tokens.error.copy(alpha = 0.72f)
            focused -> tokens.accent.copy(alpha = 0.72f)
            else -> tokens.glassStroke
        },
        animationSpec = tween(durationMillis = 160),
    )
    val animatedTint by animateColorAsState(
        targetValue = if (focused) tokens.glassFillStrong else tokens.glassFill,
        animationSpec = tween(durationMillis = 160),
    )
    BasicTextField(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = accessibilityLabel
                if (errorText != null) error(errorText)
            },
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        textStyle = textStyle.copy(
            color = if (enabled) tokens.textPrimary else tokens.textSecondary,
        ),
        cursorBrush = SolidColor(tokens.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { innerTextField ->
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = UiSizes.inputHeight),
                shape = RoundedCornerShape(UiRadii.medium),
                tint = animatedTint,
                strokeColor = animatedStroke,
                shadowElevation = 8.dp,
            ) {
                Box(
                    modifier = Modifier.padding(
                        horizontal = UiSpacing.normal,
                        vertical = UiSpacing.compact,
                    ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = tokens.textSecondary,
                        )
                    }
                    innerTextField()
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
        targetValue = if (focused) tokens.accent.copy(alpha = 0.72f) else tokens.glassStroke,
        animationSpec = tween(durationMillis = 160),
    )
    GlassSurface(
        modifier = modifier
            .defaultMinSize(minHeight = UiSizes.inputHeight)
            .heightIn(min = UiSizes.inputHeight),
        shape = RoundedCornerShape(UiRadii.medium),
        tint = tokens.glassFill,
        strokeColor = animatedStroke,
        shadowElevation = 8.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Buscar",
                style = affordanceStyle,
                color = tokens.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = UiSpacing.normal),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = UiSizes.inputHeight)
                    .padding(
                        start = 72.dp,
                        end = if (value.isBlank()) UiSpacing.normal else 96.dp,
                        top = UiSpacing.normal,
                        bottom = UiSpacing.normal,
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = placeholder },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = tokens.textSecondary,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            AnimatedVisibility(
                visible = value.isNotBlank(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(
                            minWidth = UiSizes.touchTarget,
                            minHeight = UiSizes.touchTarget,
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = "Limpiar búsqueda"
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = { onValueChange("") },
                        )
                        .padding(horizontal = UiSpacing.compact),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Limpiar",
                        style = affordanceStyle,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
    }
}
