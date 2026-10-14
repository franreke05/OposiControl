package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.presentation.formatRelativeTime
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceMetaLine
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestRules
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import kotlin.time.Clock
import kotlinx.datetime.Instant

internal const val ACCESS_REQUESTS_CATALOG_TAG: String = "access-requests-catalog"
internal const val ACCESS_REQUESTS_DETAIL_TAG: String = "access-requests-detail"

/**
 * Solicitudes de acceso al panel: el bloque de Sistema > Administradores que solo ve un superadministrador.
 *
 * El mismo workspace que OposiTickets —catalogo a la izquierda, ficha a la derecha— porque la tarea es la misma: recorrer
 * una cola y decidir sobre cada fila. Aprobar concede el rol `admin` (lo decide el servidor: aqui no se elige rol) y
 * rechazar BORRA la cuenta que se creo al pedir acceso. Hasta que se aprueba, la cuenta no puede iniciar sesion. Las dos piden
 * confirmacion.
 */
@Composable
fun AccessRequestsScreen(viewModel: AccessRequestsViewModel) {
    val state by viewModel.state.collectAsState()
    AccessRequestsContent(state = state, onIntent = viewModel::dispatch)
}

/** El workspace, por ESTADO: se puede renderizar contra estados fabricados, sin Koin ni red. */
@Composable
internal fun AccessRequestsContent(
    state: AccessRequestsState,
    onIntent: (AccessRequestsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember(state.allRequests) { Clock.System.now() }
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier -> RequestCatalogPane(state, now, onIntent, paneModifier.testTag(ACCESS_REQUESTS_CATALOG_TAG)) },
        editor = { paneModifier, _, fillsHeight ->
            RequestDetailPane(state, now, onIntent, fillsHeight, paneModifier.testTag(ACCESS_REQUESTS_DETAIL_TAG))
        },
    )

    state.pendingDecision?.let { pending ->
        val approves = pending.kind == AccessDecisionKind.APPROVE
        ConfirmDialog(
            title = if (approves) "Aprobar solicitud" else "Rechazar solicitud",
            message = accessDecisionMessage(pending),
            confirmLabel = if (approves) "Aprobar" else "Rechazar",
            // Rojo solo al rechazar: aprobar concede acceso, no destruye nada, pero tampoco es un clic sin consecuencias
            // (por eso se confirma igualmente).
            destructive = !approves,
            onConfirm = { onIntent(AccessRequestsIntent.ConfirmDecision) },
            onDismiss = { onIntent(AccessRequestsIntent.DismissDecision) },
        )
    }
}

/** Texto de la confirmacion: a quien, que va a pasar y —al rechazar— el motivo que quedara guardado. Funcion pura. */
internal fun accessDecisionMessage(pending: PendingAccessDecision): String = buildString {
    val request = pending.request
    append("Persona: ${request.name.ifBlank { "Sin nombre" }}\n")
    append("Correo: ${request.email}\n\n")
    when (pending.kind) {
        AccessDecisionKind.APPROVE -> append(
            "Se activará su cuenta, se le concederá el rol de administrador y podrá entrar en OposiControl con la contraseña que eligió. " +
                "Quedará registrado en la auditoría.",
        )
        AccessDecisionKind.REJECT -> {
            if (pending.reason.isNotBlank()) append("Motivo: ${pending.reason}\n\n")
            append("Se rechazará la solicitud y se borrará la cuenta que se creó al pedir acceso. Si quiere entrar, tendrá que enviar otra solicitud.")
        }
    }
}

internal fun accessRequestStatusLabel(status: AccessRequestStatus): String = when (status) {
    AccessRequestStatus.PENDING -> "Pendiente"
    AccessRequestStatus.APPROVED -> "Aprobada"
    AccessRequestStatus.REJECTED -> "Rechazada"
}

internal fun accessRequestStatusTone(status: AccessRequestStatus): StatusTone = when (status) {
    AccessRequestStatus.PENDING -> StatusTone.ATTENTION
    AccessRequestStatus.APPROVED -> StatusTone.POSITIVE
    AccessRequestStatus.REJECTED -> StatusTone.NEGATIVE
}

/** Insignia de verificacion del correo de una solicitud pendiente (el servidor calcula los dos indicadores). */
internal enum class AccessVerificationBadge(val label: String, val tone: StatusTone) {
    VERIFIED("Correo verificado", StatusTone.POSITIVE),
    UNVERIFIED("Sin verificar", StatusTone.ATTENTION),
    EXPIRED("Caducada", StatusTone.NEGATIVE),
}

internal fun accessVerificationBadge(request: AccessRequest): AccessVerificationBadge = when {
    request.emailVerified -> AccessVerificationBadge.VERIFIED
    request.expired -> AccessVerificationBadge.EXPIRED
    else -> AccessVerificationBadge.UNVERIFIED
}

internal const val ACCESS_UNVERIFIED_TITLE = "Correo sin verificar"
internal const val ACCESS_UNVERIFIED_DETAIL =
    "Quien la envía aún no ha introducido el código que le llegó por correo. No se puede aprobar hasta que lo haga; " +
        "si pasa 1 hora sin verificarlo, la solicitud caduca."
internal const val ACCESS_EXPIRED_TITLE = "Solicitud caducada"
internal const val ACCESS_EXPIRED_DETAIL =
    "Pasó 1 hora sin que se verificara el correo y ya no se puede aprobar. Puedes rechazarla; si quiere entrar, tendrá que enviar otra."

/** «Hace 3 h» / «el 12/10/2026»; una fecha ilegible no se inventa. */
internal fun accessRequestTimeLabel(iso: String?, now: Instant): String =
    formatRelativeTime(iso.orEmpty(), now).replaceFirstChar { it.uppercase() }.ifBlank { "Fecha desconocida" }

internal fun pendingCountLabel(count: Int): String = if (count == 1) "1 pendiente" else "$count pendientes"

// ---------------------------------------------------------------------------------------------------------------------
// Catalogo
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestCatalogPane(
    state: AccessRequestsState,
    now: Instant,
    onIntent: (AccessRequestsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceSection(
        icon = AppIcon.ROLES,
        role = SemanticRole.WARNING,
        // Titulo corto y sin subtitulo: la columna del catalogo mide 310 dp y lleva a su derecha el recuento y el boton.
        title = "Solicitudes",
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                if (state.pendingCount > 0) WorkspacePill(text = pendingCountLabel(state.pendingCount), icon = AppIcon.CLOCK)
                DesktopIconButton(
                    label = "Actualizar solicitudes",
                    size = Sizing.iconButtonSmall,
                    onClick = { onIntent(AccessRequestsIntent.Refresh) },
                ) { AppIconImage(AppIcon.REFRESH, size = 14.dp) }
            }
        },
    ) {
        // Cuatro filtros no caben en una linea de la columna del catalogo: pasan a la siguiente en vez de recortarse.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            AccessRequestFilter.entries.forEach { filter ->
                WorkspaceFilterPill(
                    label = filter.label,
                    selected = filter == state.filter,
                    onClick = { onIntent(AccessRequestsIntent.UpdateFilter(filter)) },
                )
            }
        }
        AsyncStatePane(
            state = state.requests,
            emptyTitle = if (state.filter == AccessRequestFilter.ALL) "Sin solicitudes" else "Sin resultados",
            emptyMessage = if (state.filter == AccessRequestFilter.ALL) {
                "Nadie ha pedido acceso al panel todavía."
            } else {
                "Ninguna solicitud está en ese estado."
            },
            emptyIcon = AppIcon.ROLES,
            loadingRows = 5,
            onRetry = { onIntent(AccessRequestsIntent.Refresh) },
            // La lista sigue en pantalla cuando una solicitud cambia de estado: no vuelve arriba.
            stableContent = true,
        ) { requests ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    requests.forEach { request ->
                        key(request.id) {
                            WorkspaceCatalogRow(
                                title = request.name.ifBlank { "Sin nombre" },
                                selected = request.id == state.selectedId,
                                onClick = { onIntent(AccessRequestsIntent.Select(request.id)) },
                                icon = AppIcon.USERS,
                                description = request.email,
                                meta = listOf(accessRequestTimeLabel(request.createdAt, now)),
                                trailing = {
                                    // Todas las pendientes dirian «Pendiente» (ya lo cuenta la pastilla de arriba): lo que
                                    // distingue a una pendiente es si se puede aprobar, asi que su fila lleva la verificacion.
                                    if (request.status == AccessRequestStatus.PENDING) {
                                        val badge = accessVerificationBadge(request)
                                        StatusChip(status = badge.label, tone = badge.tone)
                                    } else {
                                        StatusChip(
                                            status = accessRequestStatusLabel(request.status),
                                            tone = accessRequestStatusTone(request.status),
                                        )
                                    }
                                },
                                // La fila recien aprobada, rechazada o verificada se resalta un instante.
                                changeKey = request.status to accessVerificationBadge(request),
                            )
                        }
                    }
                }
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Ficha de la solicitud
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun RequestDetailPane(
    state: AccessRequestsState,
    now: Instant,
    onIntent: (AccessRequestsIntent) -> Unit,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val request = state.selected
    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = {
            WorkspaceEditorHeader(
                icon = AppIcon.USERS,
                title = request?.name?.ifBlank { "Sin nombre" } ?: "Solicitudes de acceso",
                subtitle = request?.email ?: "Ninguna solicitud abierta.",
                role = SemanticRole.WARNING,
            ) {
                if (request != null) {
                    StatusChip(status = accessRequestStatusLabel(request.status), tone = accessRequestStatusTone(request.status))
                    if (request.status == AccessRequestStatus.PENDING) {
                        val badge = accessVerificationBadge(request)
                        StatusChip(status = badge.label, tone = badge.tone)
                    }
                }
            }
        },
        contentKey = request?.id,
    ) {
        if (request == null) {
            EmptyStateCard(
                title = "Sin solicitud abierta",
                message = "Elige una solicitud de la lista para ver quién la envía y por qué.",
                icon = AppIcon.ROLES,
            )
            return@WorkspaceEditor
        }
        RequestFacts(request, now)
        FormFeedback(
            state = state.decisionState,
            loadingText = "Aplicando la decisión…",
            successText = (state.decisionState as? UiAsync.Success)?.value.orEmpty(),
        )
        when (request.status) {
            AccessRequestStatus.PENDING -> PendingDecisionPanel(request, state, onIntent)
            AccessRequestStatus.APPROVED -> WorkspaceNotice(
                tone = WorkspaceNoticeTone.INFO,
                title = "Solicitud aprobada",
                detail = "La cuenta tiene el rol de administrador. Para cambiarlo o retirarlo, abre su ficha en la pestaña Usuarios.",
            )
            // Solo se llega aqui con un rechazo antiguo (de cuando rechazar no borraba la cuenta) o si el borrado fallo:
            // un rechazo normal quita la fila de la lista.
            AccessRequestStatus.REJECTED -> {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "Solicitud rechazada, pero su cuenta sigue creada",
                    detail = (request.resolutionReason?.takeIf { it.isNotBlank() }?.let { "Motivo: ${it.trim().trimEnd('.')}. " } ?: "") +
                        "La cuenta está sin activar y sin ningún rol: no puede iniciar sesión. Bórrala para terminar el rechazo.",
                )
                DangerOutlineButton(
                    text = "Borrar la cuenta",
                    onClick = { onIntent(AccessRequestsIntent.AskReject(request.id)) },
                    enabled = !state.isDeciding,
                )
            }
        }
    }
}

@Composable
private fun RequestFacts(request: AccessRequest, now: Instant) {
    WorkspacePanel {
        WorkspaceMetaLine(label = "Nombre", value = request.name.ifBlank { "Sin nombre" })
        WorkspaceMetaLine(label = "Correo", value = request.email)
        WorkspaceMetaLine(label = "Enviada", value = accessRequestTimeLabel(request.createdAt, now))
        if (request.emailVerifiedAt != null) {
            WorkspaceMetaLine(label = "Correo verificado", value = accessRequestTimeLabel(request.emailVerifiedAt, now))
        }
        if (request.status != AccessRequestStatus.PENDING) {
            WorkspaceMetaLine(label = "Resuelta", value = accessRequestTimeLabel(request.resolvedAt, now))
        }
    }
    WorkspacePanel {
        Text(text = "Motivo", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
        Text(
            text = request.reason.ifBlank { "Sin motivo." },
            style = MaterialTheme.typography.bodyMedium,
            color = GlassTheme.tokens.textPrimary,
        )
    }
}

@Composable
private fun PendingDecisionPanel(request: AccessRequest, state: AccessRequestsState, onIntent: (AccessRequestsIntent) -> Unit) {
    // Aprobar solo se ofrece con el correo verificado y a tiempo; el servidor lo vuelve a comprobar (409 si no).
    when (accessVerificationBadge(request)) {
        AccessVerificationBadge.VERIFIED -> Unit
        AccessVerificationBadge.UNVERIFIED -> WorkspaceNotice(
            tone = WorkspaceNoticeTone.INFO,
            title = ACCESS_UNVERIFIED_TITLE,
            detail = ACCESS_UNVERIFIED_DETAIL,
        )
        AccessVerificationBadge.EXPIRED -> WorkspaceNotice(
            tone = WorkspaceNoticeTone.WARNING,
            title = ACCESS_EXPIRED_TITLE,
            detail = ACCESS_EXPIRED_DETAIL,
        )
    }
    WorkspacePanel {
        LabeledField(
            label = "Motivo del rechazo (opcional)",
            value = state.rejectionReason,
            onValueChange = { onIntent(AccessRequestsIntent.UpdateRejectionReason(it)) },
            placeholder = "Solo si vas a rechazarla",
            singleLine = false,
            minLines = 2,
            supportingText = "${state.rejectionReason.length} / ${AccessRequestRules.RESOLUTION_REASON_MAX}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (request.canBeApproved) {
                PrimaryButton(
                    text = "Aprobar",
                    onClick = { onIntent(AccessRequestsIntent.AskApprove(request.id)) },
                    enabled = !state.isDeciding,
                    loading = state.isDeciding,
                    icon = { AppIconImage(AppIcon.CHECK, size = 14.dp) },
                )
            }
            DangerOutlineButton(
                text = "Rechazar",
                onClick = { onIntent(AccessRequestsIntent.AskReject(request.id)) },
                enabled = !state.isDeciding,
            )
        }
        if (request.canBeApproved) {
            Text(
                text = "Aprobar concede el rol de administrador (nunca el de superadministrador).",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.textSecondary,
            )
        }
    }
}
