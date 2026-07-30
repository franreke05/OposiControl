package com.oposicontrol.admin.features.tickets.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.StatusChip
import kotlinx.coroutines.flow.collect

@Composable
fun TicketsScreen(
    viewModel: TicketsViewModel,
    onEffect: (TicketsEffect) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel, onEffect) {
        viewModel.effects.collect(onEffect)
    }

    TicketsContent(
        state = state,
        onIntent = viewModel::dispatch,
    )
}

@Composable
fun TicketsContent(
    state: TicketsState,
    onIntent: (TicketsIntent) -> Unit,
) {
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { onIntent(TicketsIntent.UpdateSearch(it)) },
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
                                key = wallet.userId.value,
                                cells = listOf(wallet.userName, wallet.specialty, wallet.balance.toString(), wallet.status),
                                selected = wallet.userId == state.selectedUserId,
                            )
                        },
                        onRowClick = { onIntent(TicketsIntent.SelectWallet(WalletUserId(it))) },
                    )
                }
            }
        },
        detailPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormSection(
                    title = "Ajuste de saldo",
                    description = "Vista operativa para abonar o corregir tickets sobre carteras reales.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip("active")
                        OutlinedTextField(
                            value = state.adjustmentAmount,
                            onValueChange = { onIntent(TicketsIntent.UpdateAdjustmentAmount(it)) },
                            label = { Text("Importe (+/-)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.adjustmentReason,
                            onValueChange = { onIntent(TicketsIntent.UpdateAdjustmentReason(it)) },
                            label = { Text("Razon") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onIntent(TicketsIntent.ApplyAdjustment) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Aplicar ajuste")
                        }
                        when (val actionState = state.actionState) {
                            is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                            UiAsync.Loading -> Text("Ajustando...")
                            is UiAsync.Success -> Text("Saldo actualizado.")
                            else -> Unit
                        }
                    }
                }

                FormSection(
                    title = "Historial de transacciones",
                    description = "Lectura operativa del saldo del usuario seleccionado.",
                ) {
                    AsyncStatePane(state.transactions, emptyMessage = "Esta cartera no tiene movimientos.") { items ->
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(items, key = { it.id.value }) { movement ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StatusChip(movement.type)
                                    Text(movement.reason, style = MaterialTheme.typography.titleSmall)
                                    Text("${movement.amount} tickets", style = MaterialTheme.typography.bodyMedium)
                                    Text(movement.occurredAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
