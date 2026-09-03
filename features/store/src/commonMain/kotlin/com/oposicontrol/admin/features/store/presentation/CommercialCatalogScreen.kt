package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.formatPriceCents

/**
 * Catálogo comercial: planes, packs de OposiTickets y sus tarifas.
 *
 * Lo que se edita aquí es lo que ve el usuario final en OposiBot, así que la
 * pantalla enseña el precio ya formateado y, en los packs, el coste por ticket:
 * sin esa cifra no se puede saber de un vistazo si un pack grande compensa
 * frente a uno pequeño, que es la decisión real al fijar precios.
 */
@Composable
fun CommercialCatalogScreen(
    viewModel: CommercialCatalogViewModel,
) {
    val state by viewModel.state.collectAsState()

    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassActionButton(
                    text = "Actualizar catálogo",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.dispatch(CommercialCatalogIntent.Refresh) },
                )
                AsyncStatePane(
                    state.products,
                    emptyMessage = "No hay productos comerciales configurados.",
                ) { products ->
                    AdminTable(
                        columns = listOf(
                            AdminTableColumn("Producto", 1.4f),
                            AdminTableColumn("Tipo", 0.8f),
                            AdminTableColumn("Precio", 0.7f),
                            AdminTableColumn("OT", 0.5f),
                            AdminTableColumn("Estado", 0.7f),
                        ),
                        rows = products.map { product ->
                            AdminTableRow(
                                key = product.id,
                                cells = listOf(
                                    product.name,
                                    product.productType,
                                    product.priceCents
                                        ?.let { formatPriceCents(it, product.currency) }
                                        ?: "Por tarifa",
                                    product.ticketAmount?.toString() ?: "-",
                                    product.statusLabel(),
                                ),
                                selected = product.id == state.selectedId,
                                supporting = product.supportingLine(),
                            )
                        },
                        onRowClick = { viewModel.dispatch(CommercialCatalogIntent.Select(it)) },
                    )
                }
            }
        },
        detailPane = {
            val product = state.selected
            if (product == null) {
                FormSection(
                    title = "Producto comercial",
                    description = "Selecciona un producto del catálogo para editarlo.",
                ) {
                    Text(
                        text = "Sin producto seleccionado.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                return@CrudSplitLayout
            }

            FormSection(
                title = product.code.ifBlank { "Producto comercial" },
                description = "Estos valores los lee OposiBot: al guardar cambian para los usuarios.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Nombre", style = MaterialTheme.typography.labelMedium)
                    GlassTextField(
                        value = state.editorName,
                        onValueChange = { viewModel.dispatch(CommercialCatalogIntent.UpdateName(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. 2.600 OposiTickets",
                    )

                    Text(
                        text = "Precio (${product.currency})",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GlassTextField(
                        value = state.editorPrice,
                        onValueChange = { viewModel.dispatch(CommercialCatalogIntent.UpdatePrice(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "12,99",
                    )

                    Text(text = "OposiTickets del pack", style = MaterialTheme.typography.labelMedium)
                    GlassTextField(
                        value = state.editorTicketAmount,
                        onValueChange = { viewModel.dispatch(CommercialCatalogIntent.UpdateTicketAmount(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Vacio si el producto no es un pack",
                    )

                    // Coste por ticket del estado ya guardado: es la comparacion
                    // que decide si la escala de packs tiene sentido.
                    product.centsPerTicket?.let { costPerTicket ->
                        Text(
                            text = "Coste actual por OposiTicket: " +
                                "${formatCentsPerTicket(costPerTicket)} ${product.currency}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(text = "Etiqueta promocional", style = MaterialTheme.typography.labelMedium)
                    GlassTextField(
                        value = state.editorPromotionalLabel,
                        onValueChange = {
                            viewModel.dispatch(CommercialCatalogIntent.UpdatePromotionalLabel(it))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Más elegido",
                    )

                    GlassToggleRow(
                        title = "Activo",
                        description = "Un producto inactivo no se puede comprar.",
                        checked = state.editorActive,
                        onCheckedChange = { viewModel.dispatch(CommercialCatalogIntent.ToggleActive(it)) },
                    )
                    GlassToggleRow(
                        title = "Visible en el catálogo",
                        description = "Controla si aparece en OposiMarket.",
                        checked = state.editorVisible,
                        onCheckedChange = { viewModel.dispatch(CommercialCatalogIntent.ToggleVisible(it)) },
                    )
                    GlassToggleRow(
                        title = "Destacado",
                        description = "Muestra la etiqueta promocional. Requiere que sea visible.",
                        checked = state.editorHighlighted,
                        onCheckedChange = {
                            viewModel.dispatch(CommercialCatalogIntent.ToggleHighlighted(it))
                        },
                    )

                    if (state.editorHighlighted && !state.editorVisible) {
                        // Mismo criterio que aplica el backend, avisado antes de
                        // pulsar Guardar: una etiqueta sobre algo oculto no la ve nadie.
                        Text(
                            text = "Un producto destacado pero no visible no se muestra a nadie.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    GlassActionButton(
                        text = "Guardar producto",
                        onClick = { viewModel.dispatch(CommercialCatalogIntent.Save) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FormFeedback(state.actionState, successText = "Catálogo actualizado.")
                }
            }

            if (product.tariffs.isNotEmpty()) {
                CommercialTariffsSection(product)
            }
        },
    )
}

/**
 * Tarifas del producto, en solo lectura por ahora.
 *
 * Se muestran porque son las que fijan de verdad el precio de un plan (alta vs
 * renovacion, mensual vs anual) y sin verlas el precio unitario de arriba
 * enganaria. Editarlas exige mas reglas: se hace en su propio paso.
 */
@Composable
private fun CommercialTariffsSection(product: CommercialProduct) {
    FormSection(
        title = "Tarifas",
        description = "Precio por periodo y fase. La fase separa el alta de la renovacion.",
    ) {
        AdminTable(
            columns = listOf(
                AdminTableColumn("Periodo", 0.8f),
                AdminTableColumn("Fase", 0.9f),
                AdminTableColumn("Precio", 0.7f),
                AdminTableColumn("Estado", 0.7f),
            ),
            rows = product.tariffs.map { tariff ->
                AdminTableRow(
                    key = tariff.id,
                    cells = listOf(
                        tariff.billingPeriod,
                        tariff.pricePhase,
                        formatPriceCents(tariff.priceCents, tariff.currency),
                        if (tariff.active && tariff.visible) "active" else "inactive",
                    ),
                )
            },
        )
    }
}

private fun CommercialProduct.statusLabel(): String = when {
    !active -> "inactive"
    !visible -> "hidden"
    highlighted -> "destacado"
    else -> "active"
}

private fun CommercialProduct.supportingLine(): String = buildList {
    if (!promotionalLabel.isNullOrBlank()) add(promotionalLabel)
    centsPerTicket?.let { add("${formatCentsPerTicket(it)} $currency/OT") }
    if (tariffs.isNotEmpty()) add("${tariffs.size} tarifas")
}.joinToString(" · ").ifBlank { code }

/**
 * Coste por ticket con cuatro decimales: a 100 OT por euro las diferencias entre
 * packs viven en la tercera cifra, y redondear a dos las borraria justo cuando
 * se necesitan para comparar.
 */
internal fun formatCentsPerTicket(centsPerTicket: Double): String {
    // Se redondea, no se trunca: truncar sesga siempre a la baja y haría parecer
    // que un pack sale algo más barato de lo que es.
    val tenThousandths = kotlin.math.round(centsPerTicket * 10_000).toLong()
    val units = tenThousandths / 10_000
    val fraction = (tenThousandths % 10_000).toString().padStart(4, '0')
    return "$units,$fraction"
}
