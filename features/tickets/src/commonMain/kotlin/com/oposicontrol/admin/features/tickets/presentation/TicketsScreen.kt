package com.oposicontrol.admin.features.tickets.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveBar
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveTone
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

internal const val TICKETS_CATALOG_TAG: String = "tickets-catalog"
internal const val TICKETS_EDITOR_TAG: String = "tickets-editor"
internal const val TICKETS_RAIL_TAG: String = "tickets-rail"

/**
 * OposiTickets, en escritorio.
 *
 * El mismo workspace de tres columnas que Contenido —catálogo de carteras, ficha de la cartera y resumen— porque la tarea
 * es la misma: ir y volver entre una fila y su detalle. Vive en el bloque Usuarios del menú: una cartera es de una persona,
 * y [onOpenUser] lleva a su ficha sin duplicarla aquí.
 */
@Composable
fun TicketsScreen(
    viewModel: TicketsViewModel,
    /** Abre la ficha del usuario dueño de la cartera. `null` = esta composición no puede navegar (no se pinta el botón). */
    onOpenUser: ((userId: String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    TicketsContent(state = state, onIntent = viewModel::dispatch, onOpenUser = onOpenUser)
}

/** El workspace, por ESTADO: el banco de pruebas visual lo renderiza contra estados fabricados, sin Ktor ni Koin. */
@Composable
internal fun TicketsContent(
    state: TicketsState,
    onIntent: (TicketsIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenUser: ((userId: String) -> Unit)? = null,
) {
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier -> WalletCatalogPane(state, onIntent, paneModifier.testTag(TICKETS_CATALOG_TAG)) },
        editor = { paneModifier, contentWidth, fillsHeight ->
            WalletEditorPane(state, onIntent, onOpenUser, contentWidth, fillsHeight, paneModifier.testTag(TICKETS_EDITOR_TAG))
        },
        rail = { paneModifier, scrolls -> TicketsRail(state, paneModifier.testTag(TICKETS_RAIL_TAG), scrolls) },
    )

    val pending = state.pendingAdjustment
    if (pending != null && pending.awaitingConfirmation) {
        // Antes de mover saldo, el diálogo repite los tres datos de la operación —a quién, cuánto y por qué—: es la última
        // oportunidad de ver un importe mal tecleado antes de que quede registrado.
        val removes = pending.amount < 0
        ConfirmDialog(
            title = if (removes) "Retirar OposiTickets" else "Conceder OposiTickets",
            message = buildString {
                append("Usuario: ${pending.userName}\n")
                append("Cantidad: ${formatSignedOt(pending.amount)}\n")
                append("Motivo: ${pending.reason}\n\n")
                append("Quedará registrado como un movimiento y no se puede deshacer.")
            },
            confirmLabel = if (removes) "Retirar" else "Conceder",
            // Rojo sólo cuando se quita saldo: conceder no es destructivo, y pintarlo de rojo enseñaría a ignorar el color.
            destructive = removes,
            onConfirm = { onIntent(TicketsIntent.ConfirmAdjustment) },
            onDismiss = { onIntent(TicketsIntent.DismissAdjustment) },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Catálogo
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun WalletCatalogPane(state: TicketsState, onIntent: (TicketsIntent) -> Unit, modifier: Modifier = Modifier) {
    val hasFilters = walletCatalogHasFilters(state.searchQuery, state.filter)
    WorkspaceSection(
        icon = AppIcon.TICKETS,
        role = SemanticRole.INFO,
        title = "Carteras",
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                if (state.allWallets.isNotEmpty()) WorkspacePill(text = formatCount(state.allWallets.size))
                DesktopIconButton(
                    label = "Actualizar carteras",
                    size = Sizing.iconButtonSmall,
                    onClick = { onIntent(TicketsIntent.Refresh) },
                ) { AppIconImage(AppIcon.REFRESH, size = 14.dp) }
            }
        },
    ) {
        GlassSearchBar(
            value = state.searchQuery,
            onValueChange = { onIntent(TicketsIntent.UpdateSearch(it)) },
            placeholder = "Buscar usuario o especialidad…",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletFilter.entries.forEach { filter ->
                WorkspaceFilterPill(
                    label = filter.label,
                    selected = filter == state.filter,
                    onClick = { onIntent(TicketsIntent.UpdateFilter(filter)) },
                )
            }
            if (hasFilters) {
                Box(modifier = Modifier.weight(1f))
                GhostButton(
                    text = "Limpiar",
                    size = ButtonSize.COMPACT,
                    onClick = {
                        onIntent(TicketsIntent.UpdateSearch(""))
                        onIntent(TicketsIntent.UpdateFilter(WalletFilter.ALL))
                    },
                )
            }
        }
        AsyncStatePane(
            state = state.wallets,
            // Vacío significa dos cosas: sin filtros, «nadie tiene cartera»; con ellos, «ninguna ASÍ».
            emptyTitle = if (hasFilters) "Sin resultados" else "Sin carteras",
            emptyMessage = if (hasFilters) {
                "Ninguna cartera coincide con la búsqueda y el filtro de saldo."
            } else {
                "Ningún usuario tiene todavía una cartera de OposiTickets."
            },
            emptyIcon = if (hasFilters) AppIcon.SEARCH else AppIcon.TICKETS,
            loadingRows = 6,
            onRetry = { onIntent(TicketsIntent.Refresh) },
        ) { wallets ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    wallets.forEach { wallet ->
                        WorkspaceCatalogRow(
                            title = wallet.userName.ifBlank { "Usuario sin nombre" },
                            selected = wallet.userId == state.selectedUserId,
                            onClick = { onIntent(TicketsIntent.SelectWallet(wallet.userId)) },
                            icon = AppIcon.TICKETS,
                            description = wallet.specialty.ifBlank { "Sin especialidad principal" },
                            trailing = { WorkspacePill(text = formatOt(wallet.balance)) },
                        )
                    }
                }
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Ficha de la cartera
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun WalletEditorPane(
    state: TicketsState,
    onIntent: (TicketsIntent) -> Unit,
    onOpenUser: ((String) -> Unit)?,
    contentWidth: Dp,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val wallet = state.selectedWallet
    val selectedUserId = state.selectedUserId
    val movementCount = (state.transactions as? UiAsync.Success)?.value?.size

    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = {
            WorkspaceEditorHeader(
                icon = AppIcon.TICKETS,
                title = when {
                    selectedUserId == null -> "OposiTickets"
                    wallet == null -> "Cartera sin abrir"
                    else -> wallet.userName.ifBlank { "Usuario sin nombre" }
                },
                subtitle = when {
                    selectedUserId == null -> "Ninguna cartera abierta."
                    wallet == null -> "Este usuario no aparece en el catálogo de carteras."
                    else -> wallet.specialty.ifBlank { "Sin especialidad principal" }
                },
            ) {
                if (wallet != null) WorkspacePill(text = "Saldo ${formatOt(wallet.balance)}", icon = AppIcon.TICKETS)
                if (selectedUserId != null && onOpenUser != null) {
                    SecondaryButton(
                        text = "Ver ficha",
                        size = ButtonSize.COMPACT,
                        onClick = { onOpenUser(selectedUserId) },
                        icon = { AppIconImage(AppIcon.USERS, size = 14.dp) },
                    )
                }
            }
        },
        tabs = if (selectedUserId == null) {
            null
        } else {
            {
                val compactTabs = contentWidth < WorkspaceSizing.tabIconsMinWidth
                DesktopTabs(
                    tabs = listOf(
                        DesktopTab(
                            id = TicketsTab.MOVEMENTS.id,
                            label = TicketsTab.MOVEMENTS.label,
                            icon = AppIcon.CLOCK.takeUnless { compactTabs },
                            badge = movementCount?.takeIf { it > 0 }?.toString(),
                        ),
                        DesktopTab(TicketsTab.ADJUST.id, TicketsTab.ADJUST.label, AppIcon.EDIT.takeUnless { compactTabs }),
                    ),
                    selectedId = state.tab.id,
                    onSelect = { onIntent(TicketsIntent.SelectTab(TicketsTab.fromId(it))) },
                    variant = DesktopTabsVariant.WORKSPACE,
                )
            }
        },
        footer = if (selectedUserId != null && state.tab == TicketsTab.ADJUST) {
            { AdjustmentSaveBar(state, onIntent) }
        } else {
            null
        },
    ) {
        when {
            selectedUserId == null -> EmptyStateCard(
                title = "Sin cartera abierta",
                message = "Elige una cartera del catálogo para ver sus movimientos y ajustar su saldo.",
                icon = AppIcon.TICKETS,
            )
            state.tab == TicketsTab.MOVEMENTS -> MovementsSection(state, onIntent)
            else -> AdjustmentSection(state, onIntent)
        }
    }
}

@Composable
private fun MovementsSection(state: TicketsState, onIntent: (TicketsIntent) -> Unit) {
    FormSection(
        title = "Movimientos",
        description = "Entradas y salidas de OposiTickets, del más reciente al más antiguo.",
        help = Help.ledger,
        icon = AppIcon.CLOCK,
        iconRole = SemanticRole.INFO,
    ) {
        AsyncStatePane(
            state = state.transactions,
            emptyTitle = "Sin movimientos",
            emptyMessage = "Esta cartera todavía no ha registrado ninguna entrada ni salida de OposiTickets.",
            emptyIcon = AppIcon.TICKETS,
            loadingRows = 4,
            onRetry = { onIntent(TicketsIntent.RetryTransactions) },
        ) { movements ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                movements.forEach { MovementRow(it) }
                if (movements.size >= MOVEMENTS_SERVER_LIMIT) {
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.INFO,
                        title = "Se muestran los $MOVEMENTS_SERVER_LIMIT movimientos más recientes",
                        detail = "El historial anterior existe, pero esta pantalla todavía no puede paginarlo.",
                    )
                }
            }
        }
    }
}

/**
 * Un asiento: qué fue, por qué, cuánto y cómo quedó el saldo.
 *
 * El importe lleva su signo escrito y el tipo lleva su chip con icono: nada se dice sólo con el color.
 */
@Composable
private fun MovementRow(movement: TicketMovement) {
    val tokens = GlassTheme.tokens
    val typeLabel = ticketMovementTypeLabel(movement.type)
    val amountLabel = formatSignedOt(movement.amount)
    val trail = ticketBalanceTrail(movement)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, Radius.card)
            .padding(Spacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = "$typeLabel, $amountLabel, ${formatLedgerInstant(movement.occurredAt)}"
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = typeLabel, tone = ticketMovementTone(movement.amount))
                Text(
                    text = formatLedgerInstant(movement.occurredAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                )
            }
            Text(
                text = movement.reason.ifBlank { "Sin motivo registrado" },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Origen: ${ticketOriginLabel(movement.originType)}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = amountLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (movement.amount > 0) tokens.successInk else tokens.textPrimary,
                maxLines = 1,
            )
            trail?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AdjustmentSection(state: TicketsState, onIntent: (TicketsIntent) -> Unit) {
    val wallet = state.selectedWallet
    val amount = parseAdjustmentAmount(state.adjustmentAmount)
    FormSection(
        title = "Ajuste de saldo",
        description = "Abona o retira OposiTickets de esta cartera. El saldo lo mueve el servidor.",
        help = Help.saldo,
        icon = AppIcon.EDIT,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Cantidad",
                value = state.adjustmentAmount,
                onValueChange = { onIntent(TicketsIntent.UpdateAdjustmentAmount(it)) },
                placeholder = "Ej. 50 o -10",
                required = true,
                supportingText = "Entero distinto de cero. En positivo abona; en negativo retira. Máximo " +
                    "${formatCount(ADJUSTMENT_MAX_AMOUNT)} por operación.",
            )
            LabeledField(
                label = "Motivo",
                value = state.adjustmentReason,
                onValueChange = { onIntent(TicketsIntent.UpdateAdjustmentReason(it)) },
                placeholder = "Por qué se hace este ajuste",
                required = true,
                singleLine = false,
                minLines = 3,
                maxWidth = Sizing.inputDescriptionMaxWidth,
                supportingText = "Queda registrado en el movimiento y en auditoría.",
            )
            if (wallet != null && amount != null && amount != 0) {
                WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        text = "Saldo tras el ajuste",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTheme.tokens.textSecondary,
                    )
                    Text(
                        text = "${formatOt(wallet.balance)} → ${formatOt(wallet.balance + amount)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassTheme.tokens.textPrimary,
                    )
                    Text(
                        text = "Estimación con el saldo que se ve ahora: el definitivo lo calcula el servidor al aplicar el ajuste.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
            }
            if (state.pendingAdjustment != null && state.actionState is UiAsync.Error) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "No se sabe si el ajuste llegó a aplicarse",
                    detail = "Vuelve a aplicarlo sin cambiar la cantidad ni el motivo: si ya se registró, el servidor " +
                        "devolverá ese mismo movimiento y no lo duplicará.",
                )
            }
            FormFeedback(
                state = state.actionState,
                loadingText = "Aplicando el ajuste…",
                successText = "Saldo actualizado.",
            )
        }
    }
}

@Composable
private fun AdjustmentSaveBar(state: TicketsState, onIntent: (TicketsIntent) -> Unit) {
    val saving = state.actionState is UiAsync.Loading
    val problem = adjustmentProblem(state.adjustmentAmount, state.adjustmentReason, hasWallet = state.selectedUserId != null)
    val draft = state.hasAdjustmentDraft
    WorkspaceSaveBar(
        message = when {
            saving -> "Aplicando el ajuste…"
            !draft -> "Sin ajuste pendiente."
            problem != null -> problem
            else -> "Ajuste listo para revisar."
        },
        tone = when {
            !draft -> WorkspaceSaveTone.INFO
            problem != null -> WorkspaceSaveTone.WARNING
            else -> WorkspaceSaveTone.SUCCESS
        },
        detail = if (draft && problem == null) "Al continuar se pedirá confirmación antes de mover el saldo." else null,
        prominent = draft,
        primaryLabel = "Revisar y aplicar",
        canSave = draft && problem == null && !saving,
        saving = saving,
        onSave = { onIntent(TicketsIntent.RequestAdjustment) },
        onDiscard = if (draft && !saving) ({ onIntent(TicketsIntent.DiscardAdjustment) }) else null,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun TicketsRail(state: TicketsState, modifier: Modifier = Modifier, scrolls: Boolean = true) {
    WorkspaceRail(modifier = modifier, scrolls = scrolls) {
        WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Vista rápida") {
            when (val overview = state.overview) {
                is UiAsync.Success -> WorkspaceFacts(ticketsOverviewFacts(overview.value))
                is UiAsync.Error -> {
                    WorkspaceFacts(ticketsOverviewFacts(null))
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.WARNING,
                        title = "No se pudo leer el resumen",
                        detail = "Las carteras y sus movimientos siguen disponibles. Usa «Actualizar» para reintentarlo.",
                    )
                }
                else -> WorkspaceFacts(
                    listOf("Carteras", "Movimientos", "Abonos", "Cargos").map {
                        com.oposicontrol.admin.core.ui.components.WorkspaceFact(it, "Cargando…")
                    },
                )
            }
        }
        WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Información") {
            WorkspaceInfoLines(ticketsInformationLines)
        }
    }
}
