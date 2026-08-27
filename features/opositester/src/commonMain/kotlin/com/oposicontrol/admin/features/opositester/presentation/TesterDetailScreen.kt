package com.oposicontrol.admin.features.opositester.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.TesterReportDetail
import kotlin.time.Clock

@Composable
fun TesterDetailScreen(viewModel: TesterDetailViewModel) {
    val state by viewModel.state.collectAsState()
    TesterDetailContent(
        state = state,
        onRetry = { viewModel.dispatch(TesterDetailIntent.Load) },
        onResolve = { viewModel.dispatch(TesterDetailIntent.Resolve) },
        onDiscard = { viewModel.dispatch(TesterDetailIntent.Discard) },
    )
}

@Composable
private fun TesterDetailContent(
    state: TesterDetailState,
    onRetry: () -> Unit,
    onResolve: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (val reportState = state.report) {
        UiAsync.Idle, UiAsync.Loading -> LoadingDetail()
        UiAsync.Empty -> EmptyStateCard(
            title = "Incidencia no disponible",
            message = "Esta incidencia ya no existe o no se pudo cargar. Vuelve a OposiTester.",
        )
        is UiAsync.Error -> ErrorDetail(error = reportState.error, onRetry = onRetry)
        is UiAsync.Success -> TesterDetailBody(
            report = reportState.value,
            actionState = state.actionState,
            onResolve = onResolve,
            onDiscard = onDiscard,
        )
    }
}

@Composable
private fun LoadingDetail() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingStateCard(rows = 2)
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 2)
    }
}

@Composable
private fun ErrorDetail(error: AppError, onRetry: () -> Unit) {
    // "Incidencia no disponible" es el mensaje de NotFound (404): mismo texto
    // que el estado Empty, para no dar dos redacciones distintas al mismo
    // resultado ("ya no existe" es indistinguible de "no se encontró").
    EmptyStateCard(
        title = friendlyErrorTitleDetail(error),
        message = friendlyErrorMessageDetail(error),
        action = { GlassActionButton(text = "Reintentar", onClick = onRetry) },
    )
}

@Composable
private fun TesterDetailBody(
    report: TesterReportDetail,
    actionState: UiAsync<Unit>,
    onResolve: () -> Unit,
    onDiscard: () -> Unit,
) {
    val now = remember { Clock.System.now() }
    val ui = remember(report, now) { report.toDetailUi(now) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    val busy = actionState is UiAsync.Loading

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusPill(ui.statusKind, ui.statusLabel)

        Text(
            text = ui.typeLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append(ui.authorName)
                if (ui.timeLabel.isNotBlank()) { append(" · "); append(ui.timeLabel) }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DetailSection(title = "Descripción completa") {
            Text(text = ui.description, style = MaterialTheme.typography.bodyLarge)
        }

        val question = ui.question
        if (question != null) {
            DetailSection(title = "Contexto") {
                Text(
                    text = question.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append(question.code)
                        question.specialtyName?.let { append(" · "); append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (actionState is UiAsync.Error) {
            Text(
                text = friendlyErrorMessageDetail(actionState.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (ui.statusKind == TesterReportStatusKind.PENDING) {
            Text(
                text = "Gestión",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GlassTheme.tokens.accent,
            )
            GlassActionButton(
                text = if (busy) "Procesando…" else "Resolver incidencia",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { pendingAction = PendingAction.RESOLVE },
            )
            Text(
                text = "→ Descartar incidencia",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(enabled = !busy) { pendingAction = PendingAction.DISCARD }
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "Descartar incidencia" },
            )
        } else {
            Text(
                text = "Esta incidencia ya está ${ui.statusLabel.lowercase()} y no requiere más acciones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (pendingAction) {
        PendingAction.RESOLVE -> ConfirmDialog(
            title = "Resolver incidencia",
            message = "La incidencia pasará a resuelta y queda registrada. No modifica la pregunta ni notifica automáticamente al usuario.",
            confirmLabel = "Resolver",
            destructive = false,
            onConfirm = { pendingAction = null; onResolve() },
            onDismiss = { pendingAction = null },
        )
        PendingAction.DISCARD -> ConfirmDialog(
            title = "Descartar incidencia",
            message = "La incidencia se marcará como descartada (por ejemplo, duplicada o no reproducible). Esta acción queda registrada.",
            confirmLabel = "Descartar",
            destructive = true,
            onConfirm = { pendingAction = null; onDiscard() },
            onDismiss = { pendingAction = null },
        )
        null -> Unit
    }
}

private enum class PendingAction { RESOLVE, DISCARD }

@Composable
private fun StatusPill(kind: TesterReportStatusKind, label: String) {
    val color = when (kind) {
        TesterReportStatusKind.PENDING -> GlassTheme.tokens.accent
        TesterReportStatusKind.RESOLVED -> GlassTheme.tokens.success
        TesterReportStatusKind.DISCARDED, TesterReportStatusKind.REVIEWED, TesterReportStatusKind.UNKNOWN ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    GlassSurface(
        modifier = Modifier.defaultMinSize(minHeight = 32.dp),
        shape = RoundedCornerShape(16.dp),
        tint = color.copy(alpha = 0.16f),
        strokeColor = color.copy(alpha = 0.45f),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GlassTheme.tokens.accent,
            )
            content()
        }
    }
}

private fun friendlyErrorTitleDetail(error: AppError): String = when (error) {
    is AppError.Network -> "No se pudo conectar"
    is AppError.Authentication -> "Sesión no disponible"
    is AppError.Authorization -> "Acceso no disponible"
    is AppError.NotFound -> "Incidencia no disponible"
    is AppError.Validation, is AppError.Unknown -> "No se pudo cargar la incidencia"
}

private fun friendlyErrorMessageDetail(error: AppError): String = when (error) {
    is AppError.Network -> "No hemos podido cargar la incidencia. Comprueba la conexión."
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "No tienes permisos para consultar esta incidencia."
    is AppError.NotFound -> "Esta incidencia ya no está disponible. Vuelve a OposiTester."
    is AppError.Validation, is AppError.Unknown -> "Ha ocurrido un problema inesperado. Puedes intentarlo de nuevo."
}
