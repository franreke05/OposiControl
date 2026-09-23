package com.oposicontrol.admin.features.reports.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.ForumReportDetail
import kotlin.time.Clock

@Composable
fun ReportDetailScreen(viewModel: ReportDetailViewModel) {
    val state by viewModel.state.collectAsState()
    ReportDetailContent(
        state = state,
        onRetry = { viewModel.dispatch(ReportDetailIntent.Load) },
        onResolve = { viewModel.dispatch(ReportDetailIntent.Resolve) },
        onDiscard = { viewModel.dispatch(ReportDetailIntent.Discard) },
    )
}

@Composable
private fun ReportDetailContent(
    state: ReportDetailState,
    onRetry: () -> Unit,
    onResolve: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (val reportState = state.report) {
        UiAsync.Idle, UiAsync.Loading -> LoadingDetail()
        UiAsync.Empty -> EmptyStateCard(
            title = "Informe no disponible",
            message = "Este informe ya no existe o no se pudo cargar.",
        )
        is UiAsync.Error -> ErrorDetail(error = reportState.error, onRetry = onRetry)
        is UiAsync.Success -> ReportDetailBody(
            report = reportState.value,
            actionState = state.actionState,
            onResolve = onResolve,
            onDiscard = onDiscard,
        )
    }
}

@Composable
fun LoadingDetail() = com.oposicontrol.admin.core.ui.components.LoadingDetail()

@Composable
private fun ErrorDetail(error: AppError, onRetry: () -> Unit) {
    EmptyStateCard(
        title = friendlyErrorTitleDetail(error),
        message = friendlyErrorMessageDetail(error),
        action = { GlassActionButton(text = "Reintentar", onClick = onRetry) },
    )
}

@Composable
private fun ReportDetailBody(
    report: ForumReportDetail,
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

        DetailSection(title = "Motivo del reporte") {
            Text(
                text = ui.reason.ifBlank { "Sin motivo especificado." },
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (ui.description.isNotBlank() && ui.description != ui.reason) {
            DetailSection(title = "Descripción del usuario") {
                Text(text = ui.description, style = MaterialTheme.typography.bodyMedium)
            }
        }

        DetailSection(title = "Contenido reportado") {
            val preview = ui.contentPreview
            if (preview != null) {
                Text(
                    text = preview.threadTitle?.let { "${preview.typeLabel} en \"$it\"" } ?: preview.typeLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (preview.text.isNotBlank()) {
                    Text(text = preview.text, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = buildString {
                        append(preview.author.ifBlank { "Usuario no disponible" })
                        if (preview.timeLabel.isNotBlank()) { append(" · "); append(preview.timeLabel) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.currentStateLabel.isNotBlank()) {
                    Text(
                        text = "Estado actual del contenido: ${preview.currentStateLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = ui.entityTypeLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "No se pudo recuperar el contenido original. Puede que ya no exista.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DetailSection(title = "Reportado por") {
            Text(
                text = ui.reporterName.ifBlank { "Usuario no disponible" },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(text = "Reportado el ${ui.createdLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.resolvedLabel?.let {
                Text(text = "Resuelto el $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (actionState is UiAsync.Error) {
            Text(
                text = friendlyErrorMessageDetail(actionState.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (ui.statusKind == ReportStatusKind.PENDING) {
            Text(
                text = "Gestión del informe",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GlassTheme.tokens.accent,
            )
            GlassActionButton(
                text = if (busy) "Procesando…" else "Resolver informe",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { pendingAction = PendingAction.RESOLVE },
            )
            Text(
                text = "→ Descartar informe",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(enabled = !busy) { pendingAction = PendingAction.DISCARD }
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "Descartar informe" },
            )
            // No se muestra "Acciones sobre contenido": no se pudo verificar en BD/backend
            // ninguna acción real de moderación de contenido (ocultar/cerrar/restaurar) que
            // esta pantalla pueda invocar de forma segura. Ver CONTENT MODERATION: NOT_AVAILABLE
            // en el informe final — no se inventan acciones que el backend no soporta.
        } else {
            Text(
                text = "Este informe ya está ${ui.statusLabel.lowercase()} y no requiere más acciones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (pendingAction) {
        PendingAction.RESOLVE -> ConfirmDialog(
            title = "Resolver informe",
            message = "Resolver este informe no modificará el contenido reportado ni aplicará ninguna sanción automática. El informe pasará a resuelto y queda registrado.",
            confirmLabel = "Resolver",
            destructive = false,
            onConfirm = { pendingAction = null; onResolve() },
            onDismiss = { pendingAction = null },
        )
        PendingAction.DISCARD -> ConfirmDialog(
            title = "Descartar informe",
            message = "El informe se marcará como descartado. El contenido reportado permanece sin cambios. Esta acción queda registrada.",
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
private fun StatusPill(kind: ReportStatusKind, label: String) {
    val color = when (kind) {
        ReportStatusKind.PENDING -> GlassTheme.tokens.accent
        ReportStatusKind.RESOLVED -> GlassTheme.tokens.success
        ReportStatusKind.DISCARDED, ReportStatusKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    com.oposicontrol.admin.core.ui.components.DetailStatusPill(label, color)
}

@Composable
fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) = com.oposicontrol.admin.core.ui.components.DetailSection(title, content)

private fun friendlyErrorTitleDetail(error: AppError): String = when (error) {
    is AppError.Network -> "No se pudo conectar"
    is AppError.Authentication -> "Sesión no disponible"
    is AppError.Authorization -> "Acceso no disponible"
    is AppError.NotFound -> "Informe no disponible"
    is AppError.Validation, is AppError.Unknown -> "No se pudo cargar el informe"
}

private fun friendlyErrorMessageDetail(error: AppError): String = when (error) {
    is AppError.Network -> "No hemos podido cargar el informe. Comprueba la conexión."
    is AppError.Authentication -> "Tu sesión ha caducado. Vuelve a iniciar sesión."
    is AppError.Authorization -> "No tienes permisos para consultar este informe."
    is AppError.NotFound -> "Este informe ya no está disponible."
    is AppError.Validation, is AppError.Unknown -> "Ha ocurrido un problema inesperado. Puedes intentarlo de nuevo."
}
