package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.motionOverlayEntrance
import com.oposicontrol.admin.core.ui.components.overlayDialogProperties
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.theme.Spacing

/** `2026-10-18T10:00:01.123Z` → `2026-10-18 10:00`. Un valor ilegible se muestra tal cual, recortado: nunca se inventa una fecha. */
internal fun formatWhen(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    val trimmed = iso.trim()
    if (trimmed.length < 16 || trimmed[10] != 'T') return trimmed.take(32)
    return trimmed.substring(0, 10) + " " + trimmed.substring(11, 16)
}

/** Texto para una celda de usuario: handle público si existe, o un guion. Nunca nombre real ni correo. */
internal fun handleOrDash(handle: String?): String = handle?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "—"

/** Contenedor desplazable de una pantalla de Control V1: columna con el espaciado del panel. */
@Composable
internal fun ControlColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) { content() }
}

/** Fila de pastillas de filtro. [selected] `null` se pinta como «Todas» solo si [allLabel] no es null. */
@Composable
internal fun <T> FilterPills(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (value, label) ->
            WorkspaceFilterPill(label = label, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}

/** Pie de paginación por desplazamiento: «Anterior · n–m de total · Siguiente». */
@Composable
internal fun PagerBar(offset: Int, shown: Int, total: Int, hasPrevious: Boolean, hasNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(text = "Anterior", size = ButtonSize.COMPACT, enabled = hasPrevious, onClick = onPrevious)
        androidx.compose.material3.Text(
            text = if (shown == 0) "0 de $total" else "${offset + 1}–${offset + shown} de $total",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = com.oposicontrol.admin.core.ui.theme.GlassTheme.tokens.textSecondary,
        )
        SecondaryButton(text = "Siguiente", size = ButtonSize.COMPACT, enabled = hasNext, onClick = onNext)
    }
}

/** Feedback de una acción: muestra el error humanizado o el mensaje de éxito. */
@Composable
internal fun ActionFeedback(state: UiAsync<*>, success: String = "Hecho.", loading: String = "Guardando…") {
    FormFeedback(state = state, successText = success, loadingText = loading)
}

/**
 * Diálogo de formulario con el cristal del panel (el mismo marco que `ConfirmDialog`, pero con contenido propio).
 * Esc o clic fuera lo cierran: una salida segura siempre.
 */
@Composable
internal fun FormDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = overlayDialogProperties()) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 320.dp, max = 520.dp).motionOverlayEntrance(),
            shape = RoundedCornerShape(24.dp),
            tint = GlassTheme.tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) { content() }
        }
    }
}
