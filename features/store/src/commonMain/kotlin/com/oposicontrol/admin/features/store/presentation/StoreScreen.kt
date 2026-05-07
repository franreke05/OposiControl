package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun StoreScreen(
    viewModel: StoreViewModel,
) {
    val state by viewModel.state.collectAsState()
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterBar(
                    searchValue = state.searchQuery,
                    onSearchChange = { viewModel.dispatch(StoreIntent.UpdateSearch(it)) },
                    placeholder = "Buscar articulo o tipo",
                )
                AsyncStatePane(state.items, emptyMessage = "No hay articulos disponibles.") { items ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Nombre", 1.2f),
                            AdminTableColumn("Tipo", 1f),
                            AdminTableColumn("Tickets", 0.7f),
                            AdminTableColumn("Estado", 0.8f),
                        ),
                        rows = items.map { item ->
                            AdminTableRow(
                                key = item.id,
                                cells = listOf(item.name, item.type, item.priceTickets.toString(), item.status),
                                selected = item.id == state.selectedId,
                            )
                        },
                        onRowClick = { viewModel.dispatch(StoreIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedId == null) "Nuevo articulo" else "Detalle de articulo",
                description = "Preparado para conectar la compra transaccional de tickets via RPC.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(state.editorStatus)
                    OutlinedTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(StoreIntent.UpdateName(it)) },
                        label = { Text("Nombre") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorDescription,
                        onValueChange = { viewModel.dispatch(StoreIntent.UpdateDescription(it)) },
                        label = { Text("Descripcion") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorType,
                        onValueChange = { viewModel.dispatch(StoreIntent.UpdateType(it)) },
                        label = { Text("Tipo de articulo") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorPriceTickets,
                        onValueChange = { viewModel.dispatch(StoreIntent.UpdatePriceTickets(it)) },
                        label = { Text("Precio tickets") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.editorStatus,
                        onValueChange = { viewModel.dispatch(StoreIntent.UpdateStatus(it)) },
                        label = { Text("Estado") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.dispatch(StoreIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Guardar articulo")
                    }
                    StoreCommerceBlocks(state = state, onIntent = viewModel::dispatch)
                    when (val actionState = state.actionState) {
                        is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Guardando...")
                        is UiAsync.Success -> Text("Articulo listo para publicar en tienda.")
                        else -> Unit
                    }
                }
            }
        },
    )
}

@Composable
private fun StoreCommerceBlocks(
    state: StoreState,
    onIntent: (StoreIntent) -> Unit,
) {
    FormSection(
        title = "Ofertas",
        description = "Descuento o precio especial programado sobre el articulo seleccionado.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.offerTitle, { onIntent(StoreIntent.UpdateOfferTitle(it)) }, label = { Text("Titulo oferta") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.offerDiscountPercent, { onIntent(StoreIntent.UpdateOfferDiscountPercent(it)) }, label = { Text("Descuento %") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.offerSpecialPriceTickets, { onIntent(StoreIntent.UpdateOfferSpecialPriceTickets(it)) }, label = { Text("Precio especial tickets") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.offerStartsAt, { onIntent(StoreIntent.UpdateOfferStartsAt(it)) }, label = { Text("Inicio ISO") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.offerEndsAt, { onIntent(StoreIntent.UpdateOfferEndsAt(it)) }, label = { Text("Fin ISO") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onIntent(StoreIntent.SaveOffer) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar oferta") }
            AsyncStatePane(state.offers, emptyMessage = "Sin ofertas.") { offers ->
                AdminTable(
                    columns = listOf(AdminTableColumn("Titulo", 1f), AdminTableColumn("Beneficio", 0.7f), AdminTableColumn("Estado", 0.7f)),
                    rows = offers.map { AdminTableRow(it.id, listOf(it.title, it.benefit, it.status), supporting = it.window) },
                )
            }
        }
    }
    FormSection(
        title = "Recursos temporales",
        description = "Ventanas de disponibilidad limitada y duracion de acceso.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.limitedTitle, { onIntent(StoreIntent.UpdateLimitedTitle(it)) }, label = { Text("Titulo recurso") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.limitedResourceId, { onIntent(StoreIntent.UpdateLimitedResourceId(it)) }, label = { Text("ID recurso") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.limitedAccessHours, { onIntent(StoreIntent.UpdateLimitedAccessHours(it)) }, label = { Text("Horas acceso") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.limitedStartsAt, { onIntent(StoreIntent.UpdateLimitedStartsAt(it)) }, label = { Text("Inicio ISO") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.limitedEndsAt, { onIntent(StoreIntent.UpdateLimitedEndsAt(it)) }, label = { Text("Fin ISO") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onIntent(StoreIntent.SaveLimitedResource) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar recurso temporal") }
            AsyncStatePane(state.limitedResources, emptyMessage = "Sin recursos temporales.") { resources ->
                AdminTable(
                    columns = listOf(AdminTableColumn("Titulo", 1f), AdminTableColumn("Horas", 0.5f), AdminTableColumn("Estado", 0.7f)),
                    rows = resources.map { AdminTableRow(it.id, listOf(it.title, it.durationHours.toString(), it.status), supporting = it.window) },
                )
            }
        }
    }
    FormSection(
        title = "Rafagas",
        description = "Campanas de examen con multiplicador y recompensa de tickets.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.burstTitle, { onIntent(StoreIntent.UpdateBurstTitle(it)) }, label = { Text("Titulo rafaga") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstDescription, { onIntent(StoreIntent.UpdateBurstDescription(it)) }, label = { Text("Descripcion") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstType, { onIntent(StoreIntent.UpdateBurstType(it)) }, label = { Text("Tipo de examen") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstLinkedExamId, { onIntent(StoreIntent.UpdateBurstLinkedExamId(it)) }, label = { Text("ID examen") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstMultiplier, { onIntent(StoreIntent.UpdateBurstMultiplier(it)) }, label = { Text("Multiplicador") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstRewardTickets, { onIntent(StoreIntent.UpdateBurstRewardTickets(it)) }, label = { Text("Tickets recompensa") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstStartsAt, { onIntent(StoreIntent.UpdateBurstStartsAt(it)) }, label = { Text("Inicio ISO") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.burstEndsAt, { onIntent(StoreIntent.UpdateBurstEndsAt(it)) }, label = { Text("Fin ISO") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onIntent(StoreIntent.SaveBurst) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar rafaga") }
            AsyncStatePane(state.bursts, emptyMessage = "Sin rafagas.") { bursts ->
                AdminTable(
                    columns = listOf(AdminTableColumn("Titulo", 1f), AdminTableColumn("Examen", 0.7f), AdminTableColumn("Estado", 0.7f)),
                    rows = bursts.map { AdminTableRow(it.id, listOf(it.title, it.linkedExamId.orEmpty(), it.status), supporting = it.reward) },
                )
            }
        }
    }
}
