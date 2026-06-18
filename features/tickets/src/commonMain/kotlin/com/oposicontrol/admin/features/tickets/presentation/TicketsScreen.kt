package com.oposicontrol.admin.features.tickets.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.SummaryCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme

@Composable
fun TicketsScreen(
    viewModel: TicketsViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmAdjust by remember { mutableStateOf(false) }
    val selectedWallet = (state.wallets as? UiAsync.Success)?.value?.firstOrNull { it.userId == state.selectedUserId }

    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(TicketsIntent.UpdateSearch(it)) },
                    placeholder = "Buscar usuario o especialidad",
                )
                AsyncStatePane(state.wallets, emptyMessage = "No hay carteras.") { wallets ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Usuario", 1.2f),
                            AdminTableColumn("Especialidad", 1f),
                            AdminTableColumn("Saldo", 0.8f),
                            AdminTableColumn("Estado", 0.8f),
                        ),
                        rows = wallets.map { wallet ->
                            AdminTableRow(
                                key = wallet.userId,
                                cells = listOf(wallet.userName, wallet.specialty, wallet.balance.toString(), wallet.status),
                                selected = wallet.userId == state.selectedUserId,
                            )
                        },
                        onRowClick = { viewModel.dispatch(TicketsIntent.SelectWallet(it)) },
                    )
                }
            }
        },
        detailPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectedWallet != null) {
                    SummaryCard(
                        title = selectedWallet.userName,
                        value = "${selectedWallet.balance} tickets",
                        supporting = "${selectedWallet.specialty} · ${selectedWallet.status}",
                    )
                }

                FormSection(
                    title = "Ajuste de saldo",
                    description = "Abona o corrige tickets sobre carteras reales.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FieldLabel("Importe (+/-)")
                        GlassTextField(
                            value = state.adjustmentAmount,
                            onValueChange = { viewModel.dispatch(TicketsIntent.UpdateAdjustmentAmount(it)) },
                            placeholder = "Ej. +50 o -10",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FieldLabel("Razón")
                        GlassTextField(
                            value = state.adjustmentReason,
                            onValueChange = { viewModel.dispatch(TicketsIntent.UpdateAdjustmentReason(it)) },
                            placeholder = "Motivo del ajuste",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        GlassActionButton(
                            text = "Aplicar ajuste",
                            enabled = selectedWallet != null && state.adjustmentAmount.isNotBlank(),
                            onClick = { confirmAdjust = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FormFeedback(state.actionState, loadingText = "Ajustando...", successText = "Saldo actualizado.")
                    }
                }

                FormSection(
                    title = "Historial de transacciones",
                    description = "Movimientos del usuario seleccionado.",
                ) {
                    AsyncStatePane(state.transactions, emptyMessage = "Esta cartera no tiene movimientos.") { items ->
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(items, key = { it.id }) { movement ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StatusChip(movement.type)
                                    Text(movement.reason, style = MaterialTheme.typography.titleSmall)
                                    Text("${movement.amount} tickets", style = MaterialTheme.typography.bodyMedium)
                                    Text(movement.occurredAt, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    if (confirmAdjust) {
        ConfirmDialog(
            title = "Confirmar ajuste",
            message = "Se aplicará ${state.adjustmentAmount} tickets a ${selectedWallet?.userName ?: "la cartera"}. ¿Continuar?",
            confirmLabel = "Aplicar",
            destructive = false,
            onConfirm = {
                confirmAdjust = false
                viewModel.dispatch(TicketsIntent.ApplyAdjustment)
            },
            onDismiss = { confirmAdjust = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.tokens.textSecondary,
    )
}
