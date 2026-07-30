package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.layout.UiRadii
import com.oposicontrol.admin.core.ui.layout.UiSizes
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme

enum class GlassFeedbackKind {
    Idle,
    Loading,
    Empty,
    Error,
    Success,
}

@Composable
fun GlassChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        tokens.glassFillStrong
    }
    val foreground = if (enabled) tokens.textPrimary else tokens.textSecondary
    Row(
        modifier = modifier
            .heightIn(min = UiSizes.chipMinHeight)
            .background(background, RoundedCornerShape(UiRadii.extraLarge))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) "Seleccionado" else "No seleccionado"
            }
            .padding(horizontal = UiSpacing.normal, vertical = UiSpacing.compact),
        horizontalArrangement = Arrangement.spacedBy(UiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (selected) tokens.accent else tokens.slate.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(UiRadii.small),
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GlassFeedbackPane(
    kind: GlassFeedbackKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    val liveRegionMode = when (kind) {
        GlassFeedbackKind.Error -> LiveRegionMode.Assertive
        GlassFeedbackKind.Success -> LiveRegionMode.Polite
        else -> null
    }
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (liveRegionMode != null) liveRegion = liveRegionMode
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpacing.blockLarge, vertical = UiSpacing.screenDesktop),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            if (kind == GlassFeedbackKind.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = tokens.accent,
                    strokeWidth = 3.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .heightIn(min = 6.dp)
                        .background(
                            color = feedbackColor(kind).copy(alpha = 0.28f),
                            shape = RoundedCornerShape(UiRadii.small),
                        ),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                GlassButton(
                    text = actionLabel,
                    onClick = onAction,
                    variant = GlassButtonVariant.Secondary,
                )
            }
        }
    }
}

@Composable
fun GlassConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true,
        ),
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { paneTitle = title },
            tint = GlassTheme.tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UiSpacing.blockLarge),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = GlassTheme.tokens.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = GlassTheme.tokens.textSecondary,
                )
                if (AppLayout.metrics.isMobile) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
                    ) {
                        GlassButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth(),
                            variant = if (destructive) {
                                GlassButtonVariant.Destructive
                            } else {
                                GlassButtonVariant.Primary
                            },
                        )
                        GlassButton(
                            text = dismissLabel,
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            variant = GlassButtonVariant.Secondary,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            space = UiSpacing.compact,
                            alignment = Alignment.End,
                        ),
                    ) {
                        GlassButton(
                            text = dismissLabel,
                            onClick = onDismiss,
                            modifier = Modifier.width(132.dp),
                            variant = GlassButtonVariant.Secondary,
                        )
                        GlassButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            modifier = Modifier.width(132.dp),
                            variant = if (destructive) {
                                GlassButtonVariant.Destructive
                            } else {
                                GlassButtonVariant.Primary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Immutable
data class GlassListRowState(
    val title: String,
    val supporting: String? = null,
    val selected: Boolean = false,
)

@Composable
fun GlassListRow(
    state: GlassListRowState,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    val background = if (state.selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        Color.Transparent
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = UiSizes.touchTarget)
            .background(background, RoundedCornerShape(UiRadii.medium))
            .semantics(mergeDescendants = true) {
                selected = state.selected
                stateDescription = if (state.selected) "Elemento seleccionado" else "Elemento disponible"
            }
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .semantics { role = Role.Button }
                        .clickable(role = Role.Button, onClick = onClick)
                },
            )
            .padding(horizontal = UiSpacing.normal, vertical = UiSpacing.compact),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (trailing == null) 0.dp else 96.dp),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.xxs),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.supporting != null) {
                Text(
                    text = state.supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            trailing?.invoke()
        }
    }
}

@Composable
private fun feedbackColor(kind: GlassFeedbackKind): Color = when (kind) {
    GlassFeedbackKind.Error -> GlassTheme.tokens.error
    GlassFeedbackKind.Success -> GlassTheme.tokens.accent
    GlassFeedbackKind.Loading -> GlassTheme.tokens.accent
    GlassFeedbackKind.Empty, GlassFeedbackKind.Idle -> GlassTheme.tokens.slate
}
