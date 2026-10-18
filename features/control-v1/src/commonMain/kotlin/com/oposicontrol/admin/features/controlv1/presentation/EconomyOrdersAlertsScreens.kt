package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.ExamEconomy
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderNote
import com.oposicontrol.admin.domain.model.OrderStates

internal const val EXAM_ECONOMY_TABLE_TAG = "control-exam-economy"
internal const val ORDERS_TABLE_TAG = "control-orders"
internal const val FAST_WINDOWS_TABLE_TAG = "control-fast-windows"

// ====================================================================================================================
// Comunidad y precios por examen
// ====================================================================================================================

@Composable
fun ExamEconomyScreen(viewModel: ExamEconomyViewModel) {
    val state by viewModel.state.collectAsState()
    ControlColumn { ExamEconomyContent(state, viewModel::dispatch) }
}

@Composable
internal fun ExamEconomyContent(state: ExamEconomyUiState, onIntent: (ExamEconomyIntent) -> Unit) {
    FormSection(
        title = "Comunidad y precio de cada examen",
        description = "El precio en OposiTickets y los días de acceso son de CADA examen; no hay valores por defecto. " +
            "Todo examen necesita una comunidad. Los packs de tickets se editan en Catálogo > Productos.",
        icon = AppIcon.TICKETS,
        action = { SecondaryButton(text = "Actualizar", size = ButtonSize.COMPACT, onClick = { onIntent(ExamEconomyIntent.Refresh) }) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            WorkspaceFilterPill("Todos", !state.onlyWithoutCommunity, { onIntent(ExamEconomyIntent.SetOnlyWithoutCommunity(false)) })
            WorkspaceFilterPill("Sin comunidad", state.onlyWithoutCommunity, { onIntent(ExamEconomyIntent.SetOnlyWithoutCommunity(true)) })
        }
        AsyncStatePane(
            state = state.exams,
            emptyTitle = "Sin exámenes",
            emptyMessage = if (state.onlyWithoutCommunity) "Ningún examen está sin comunidad." else "Todavía no hay exámenes.",
            onRetry = { onIntent(ExamEconomyIntent.Refresh) },
        ) { exams ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AdminTable(
                    modifier = Modifier.testTag(EXAM_ECONOMY_TABLE_TAG),
                    columns = listOf(
                        AdminTableColumn("Examen", weight = 2f),
                        AdminTableColumn("Tipo"),
                        AdminTableColumn("Comunidad"),
                        AdminTableColumn("Precio", numeric = true),
                        AdminTableColumn("Días", numeric = true),
                    ),
                    rows = exams.map { e ->
                        AdminTableRow(
                            key = e.id,
                            cells = listOf(
                                e.title.ifBlank { e.code },
                                e.examType,
                                e.communityName ?: "SIN COMUNIDAD",
                                priceLabel(e),
                                e.accessDays.toString(),
                            ),
                            selected = e.id == state.selectedId,
                            supporting = e.code.takeIf { it.isNotBlank() },
                        )
                    },
                    onRowClick = { onIntent(ExamEconomyIntent.Select(it)) },
                )
                PagerBar(
                    offset = state.offset,
                    shown = exams.size,
                    total = state.total,
                    hasPrevious = state.hasPrevious,
                    hasNext = state.hasNext,
                    onPrevious = { onIntent(ExamEconomyIntent.PreviousPage) },
                    onNext = { onIntent(ExamEconomyIntent.NextPage) },
                )
            }
        }
    }
    val exam = state.selected
    val draft = state.draft
    if (exam != null && draft != null) {
        FormSection(title = exam.title.ifBlank { exam.code }, description = "Cambios auditados con tu cuenta.") {
            val communities = (state.communities as? UiAsync.Success)?.value.orEmpty()
            DropdownField(
                label = "Comunidad",
                selectedLabel = communities.firstOrNull { it.id == draft.communityId }?.name ?: exam.communityName ?: "Elige una comunidad",
                options = communities.map { it.id to it.name },
                onSelect = { onIntent(ExamEconomyIntent.Update(draft.copy(communityId = it))) },
                required = true,
                supportingText = state.errors["community"] ?: communitiesHint(state),
                isError = state.errors.containsKey("community"),
            )
            GlassToggleRow(
                title = "Se puede comprar con tickets",
                description = "Si está apagado, el examen no se vende con OT aunque tenga precio.",
                checked = draft.sellable,
                onCheckedChange = { onIntent(ExamEconomyIntent.Update(draft.copy(sellable = it))) },
            )
            LabeledField(
                label = "Precio en OT",
                value = draft.price,
                onValueChange = { onIntent(ExamEconomyIntent.Update(draft.copy(price = it))) },
                supportingText = state.errors["price"] ?: "Entero. Obligatorio y mayor que 0 si se puede comprar.",
                isError = state.errors.containsKey("price"),
            )
            LabeledField(
                label = "Días de acceso de la compra",
                value = draft.days,
                onValueChange = { onIntent(ExamEconomyIntent.Update(draft.copy(days = it))) },
                supportingText = state.errors["days"] ?: "Una recompra suma estos días al acceso vigente.",
                isError = state.errors.containsKey("days"),
            )
            ActionFeedback(state.save, success = "Examen guardado.")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostButton(text = "Cerrar", onClick = { onIntent(ExamEconomyIntent.Discard) })
                PrimaryButton(text = "Guardar", loading = state.save is UiAsync.Loading, onClick = { onIntent(ExamEconomyIntent.Save) })
            }
        }
    }
}

private fun priceLabel(e: ExamEconomy): String = when {
    e.priceTickets == null -> "Sin precio"
    e.sellableWithTickets -> "${e.priceTickets} OT"
    else -> "${e.priceTickets} OT (no se vende)"
}

private fun communitiesHint(state: ExamEconomyUiState): String? = when (state.communities) {
    is UiAsync.Error -> "No se pudieron cargar las comunidades."
    UiAsync.Empty -> "No hay comunidades activas: créalas en Contenido > Comunidades."
    else -> null
}

// ====================================================================================================================
// Pedidos
// ====================================================================================================================

@Composable
fun OrdersScreen(viewModel: OrdersViewModel) {
    val state by viewModel.state.collectAsState()
    ControlColumn { OrdersContent(state, viewModel::dispatch) }
}

@Composable
internal fun OrdersContent(state: OrdersUiState, onIntent: (OrdersIntent) -> Unit) {
    FormSection(
        title = "Pedidos",
        description = "Los pedidos «En revisión» son pagos que el servidor no ha podido dar por buenos o malos solo. " +
            "Aquí se leen y se anota qué se ha comprobado; el estado, el saldo y los accesos los mueve el servidor.",
        icon = AppIcon.TICKETS,
        action = { SecondaryButton(text = "Actualizar", size = ButtonSize.COMPACT, onClick = { onIntent(OrdersIntent.Refresh) }) },
    ) {
        state.reviewCount?.takeIf { it > 0 }?.let {
            WorkspaceNotice(WorkspaceNoticeTone.WARNING, "$it pedidos esperan revisión", detail = "Hasta que el servidor ofrezca la resolución de pedidos, se comprueban con el proveedor y se anota aquí.")
        }
        FilterPills(
            options = listOf<Pair<String?, String>>(null to "Todos") + OrderStates.ALL.map { it to OrderStates.label(it) },
            selected = state.filter,
            onSelect = { onIntent(OrdersIntent.SetFilter(it)) },
        )
        AsyncStatePane(
            state = state.orders,
            emptyTitle = "Sin pedidos",
            emptyMessage = if (state.filter == "en_revision") "No hay pedidos en revisión." else "No hay pedidos con este filtro.",
            onRetry = { onIntent(OrdersIntent.Refresh) },
        ) { orders ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AdminTable(
                    modifier = Modifier.testTag(ORDERS_TABLE_TAG),
                    columns = listOf(
                        AdminTableColumn("Fecha"),
                        AdminTableColumn("Usuario"),
                        AdminTableColumn("Producto"),
                        AdminTableColumn("Importe", numeric = true),
                        AdminTableColumn("Estado"),
                    ),
                    rows = orders.map { o ->
                        AdminTableRow(
                            key = o.id,
                            cells = listOf(formatWhen(o.createdAt), handleOrDash(o.userHandle), o.productCode, moneyLabel(o), OrderStates.label(o.state)),
                            selected = o.id == state.selectedId,
                            supporting = if (o.refund != null) "Con caso de reembolso" else null,
                        )
                    },
                    onRowClick = { onIntent(OrdersIntent.Select(it)) },
                )
                PagerBar(state.offset, orders.size, state.total, state.hasPrevious, state.hasNext, { onIntent(OrdersIntent.PreviousPage) }, { onIntent(OrdersIntent.NextPage) })
            }
        }
    }
    state.selected?.let { OrderDetail(it, state, onIntent) }
}

@Composable
private fun OrderDetail(order: OrderItem, state: OrdersUiState, onIntent: (OrdersIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    FormSection(title = "Pedido de ${handleOrDash(order.userHandle)}", description = "Identificador del proveedor: ${order.transactionId ?: "—"}") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            WorkspacePill(OrderStates.label(order.state))
            WorkspacePill(order.provider.ifBlank { "Proveedor desconocido" })
            order.tickets?.let { WorkspacePill("$it OT") }
            Text(moneyLabel(order), style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
        }
        Text(
            "Creado ${formatWhen(order.createdAt)} · Pagado ${formatWhen(order.paidAt)} · Caduca ${formatWhen(order.expiresAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        order.refund?.let { r ->
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.INFO,
                title = "Caso de reembolso (${r.state})",
                detail = "Revertidos ${r.ticketsReverted} OT sin gastar · ${r.ticketsSpent} OT gastados en accesos. Política: ${r.policy ?: "—"}.",
            )
        }
        LabeledField(
            label = "Nota interna",
            value = state.note,
            onValueChange = { onIntent(OrdersIntent.UpdateNote(it)) },
            supportingText = state.noteError ?: "De ${OrderNote.MIN} a ${OrderNote.MAX} caracteres. No cambia el pedido: queda en auditoría con tu cuenta.",
            isError = state.noteError != null,
            singleLine = false,
            minLines = 2,
        )
        ActionFeedback(state.noteResult, success = "Nota registrada.")
        PrimaryButton(text = "Anotar", size = ButtonSize.COMPACT, loading = state.noteResult is UiAsync.Loading, onClick = { onIntent(OrdersIntent.SaveNote) })
    }
}

private fun moneyLabel(o: OrderItem): String {
    val cents = o.priceCents ?: return "—"
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    val euros = abs / 100
    val rest = (abs % 100).toString().padStart(2, '0')
    return "$sign$euros,$rest ${o.currency.ifBlank { "EUR" }}"
}

// ====================================================================================================================
// Alertas
// ====================================================================================================================

@Composable
fun AlertsScreen(viewModel: AlertsViewModel) {
    val state by viewModel.state.collectAsState()
    ControlColumn { AlertsContent(state, viewModel::dispatch) }
}

@Composable
internal fun AlertsContent(state: AlertsUiState, onIntent: (AlertsIntent) -> Unit) {
    FormSection(
        title = "Ventanas de ranking demasiado rápidas",
        description = "Usuarios con una ventana completa de respuestas cuya mediana de tiempo está por debajo del mínimo configurado: no entran en el ranking. " +
            "Es un freno contra cuentas automáticas, no una prueba: hay quien responde rápido de verdad. Solo lectura.",
        icon = AppIcon.WARNING,
        action = { SecondaryButton(text = "Actualizar", size = ButtonSize.COMPACT, onClick = { onIntent(AlertsIntent.Refresh) }) },
    ) {
        state.thresholdMs?.let { WorkspacePill(if (it == 0) "Control apagado (mínimo 0 ms)" else "Mínimo vigente: $it ms") }
        AsyncStatePane(
            state = state.fastWindows,
            emptyTitle = "Sin alertas",
            emptyMessage = "Ningún usuario con ventana completa está por debajo del mínimo.",
            onRetry = { onIntent(AlertsIntent.Refresh) },
        ) { rows ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AdminTable(
                    modifier = Modifier.testTag(FAST_WINDOWS_TABLE_TAG),
                    columns = listOf(
                        AdminTableColumn("Usuario"),
                        AdminTableColumn("Liga"),
                        AdminTableColumn("Ventana", numeric = true),
                        AdminTableColumn("Mediana (ms)", numeric = true),
                        AdminTableColumn("Actualizado"),
                    ),
                    rows = rows.map { w ->
                        AdminTableRow(
                            key = w.userId,
                            cells = listOf(handleOrDash(w.handle), w.leagueName ?: "—", w.window?.toString() ?: "—", w.medianMs?.toString() ?: "—", formatWhen(w.updatedAt)),
                        )
                    },
                )
                PagerBar(state.offset, rows.size, state.total, state.hasPrevious, state.hasNext, { onIntent(AlertsIntent.PreviousPage) }, { onIntent(AlertsIntent.NextPage) })
            }
        }
    }
    FormSection(
        title = "Contadores del foro desviados",
        description = "El trabajo periódico del foro deja una marca cuando los contadores de las publicaciones no cuadran con las filas. Solo lectura.",
        icon = AppIcon.FORUM,
    ) {
        AsyncStatePane(
            state = state.drift,
            emptyTitle = "Sin desvíos",
            emptyMessage = "Los contadores del foro cuadran.",
            onRetry = { onIntent(AlertsIntent.Refresh) },
        ) { rows ->
            AdminTable(
                columns = listOf(AdminTableColumn("Fecha"), AdminTableColumn("Publicaciones afectadas", numeric = true)),
                rows = rows.map { AdminTableRow(key = it.id, cells = listOf(formatWhen(it.at), it.rows?.toString() ?: "—")) },
            )
        }
    }
}
