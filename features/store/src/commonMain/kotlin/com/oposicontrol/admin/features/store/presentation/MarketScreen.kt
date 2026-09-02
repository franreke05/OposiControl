package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.domain.model.formatPriceCents

/**
 * OposiMarket: packs de OposiTickets, servicios, promociones y reglas de pop-up.
 *
 * Cuatro tablas distintas en una pantalla con secciones, no cuatro entradas de
 * menú: quien ajusta precios suele mirarlas juntas, y separarlas obligaría a
 * navegar de un lado a otro para comparar.
 *
 * De momento es consulta: las escrituras existen ya en el backend con sus reglas,
 * pero la edición se añade con sus diálogos de confirmación en su propio paso —
 * son datos comerciales que el usuario final ve al instante.
 */
@Composable
fun MarketScreen(viewModel: MarketViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarketSection.entries.forEach { section ->
                GlassActionButton(
                    text = section.label(),
                    onClick = { viewModel.dispatch(MarketIntent.SelectSection(section)) },
                    tint = if (section == state.section) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            GlassActionButton(
                text = "Actualizar",
                onClick = { viewModel.dispatch(MarketIntent.Refresh) },
            )
        }

        when (state.section) {
            MarketSection.PACKS -> PacksSection(state)
            MarketSection.SERVICES -> ServicesSection(state)
            MarketSection.PROMOTIONS -> PromotionsSection(state)
            MarketSection.POPUPS -> PopupRulesSection(state)
        }
    }
}

@Composable
private fun PacksSection(state: MarketState) {
    FormSection(
        title = "Packs de OposiTickets",
        description = "El coste por ticket indica si la escala de packs compensa.",
    ) {
        AsyncStatePane(state.packs, emptyMessage = "No hay packs configurados todavía.") { packs ->
            AdminTable(
                columns = listOf(
                    AdminTableColumn("Pack", 1.2f),
                    AdminTableColumn("OT", 0.6f),
                    AdminTableColumn("Precio", 0.7f),
                    AdminTableColumn("€/OT", 0.7f),
                    AdminTableColumn("Estado", 0.6f),
                ),
                rows = packs.map { pack ->
                    AdminTableRow(
                        key = pack.id,
                        cells = listOf(
                            pack.name,
                            pack.ticketAmount.toString(),
                            formatPriceCents(pack.priceCents, pack.currency),
                            pack.centsPerTicket?.let { formatCentsPerTicket(it) } ?: "-",
                            if (pack.active) "active" else "inactive",
                        ),
                        supporting = pack.code,
                    )
                },
            )
        }
    }
}

@Composable
private fun ServicesSection(state: MarketState) {
    FormSection(
        title = "Servicios",
        description = "Potenciadores y protectores del Market.",
    ) {
        AsyncStatePane(state.services, emptyMessage = "No hay servicios configurados todavía.") { services ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Servicio", 1.2f),
                        AdminTableColumn("Tipo", 0.8f),
                        AdminTableColumn("Precio OT", 0.7f),
                        AdminTableColumn("Precio", 0.7f),
                        AdminTableColumn("Estado", 0.6f),
                    ),
                    rows = services.map { service ->
                        AdminTableRow(
                            key = service.id,
                            cells = listOf(
                                service.name,
                                service.serviceType,
                                service.priceTickets?.toString() ?: "-",
                                service.priceCents?.let { formatPriceCents(it, "EUR") } ?: "-",
                                if (service.active) "active" else "inactive",
                            ),
                            supporting = service.code,
                        )
                    },
                )
                // El backend ya lo impide al guardar; si apareciese aquí sería un
                // dato preexistente y conviene que se vea.
                services.filter { it.isOfferedWithoutPrice }.forEach { service ->
                    Text(
                        text = "${service.name}: activo sin precio, no se puede adquirir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromotionsSection(state: MarketState) {
    FormSection(title = "Promociones", description = "Cupones y descuentos con ventana de validez.") {
        AsyncStatePane(state.promotions, emptyMessage = "No hay promociones configuradas todavía.") { promotions ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Código", 0.9f),
                        AdminTableColumn("Nombre", 1.2f),
                        AdminTableColumn("Tipo", 0.8f),
                        AdminTableColumn("Valor", 0.6f),
                        AdminTableColumn("Estado", 0.6f),
                    ),
                    rows = promotions.map { promotion ->
                        AdminTableRow(
                            key = promotion.id,
                            cells = listOf(
                                promotion.code,
                                promotion.name,
                                promotion.discountType,
                                promotion.value.toString(),
                                if (promotion.active) "active" else "inactive",
                            ),
                            supporting = listOfNotNull(promotion.validFrom, promotion.validUntil)
                                .joinToString(" → ")
                                .ifBlank { "Sin ventana de validez" },
                        )
                    },
                )
                promotions.filter { it.hasInvertedWindow }.forEach { promotion ->
                    Text(
                        text = "${promotion.code}: la ventana de validez está invertida, nunca se aplicará.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PopupRulesSection(state: MarketState) {
    FormSection(
        title = "Reglas de pop-up",
        description = "Dónde, cuándo y a quién se muestra cada pop-up comercial.",
    ) {
        AsyncStatePane(state.popupRules, emptyMessage = "No hay reglas configuradas todavía.") { rules ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminTable(
                    columns = listOf(
                        AdminTableColumn("Regla", 1.1f),
                        AdminTableColumn("Disparador", 1.0f),
                        AdminTableColumn("Pantalla", 0.8f),
                        AdminTableColumn("Frecuencia", 0.8f),
                        AdminTableColumn("Prioridad", 0.6f),
                        AdminTableColumn("Estado", 0.6f),
                    ),
                    rows = rules.map { rule ->
                        AdminTableRow(
                            key = rule.id,
                            cells = listOf(
                                rule.code,
                                rule.trigger,
                                rule.screen ?: "-",
                                rule.minimumFrequencyHours?.let { "cada ${it}h" } ?: "sin límite",
                                rule.priority.toString(),
                                if (rule.active) "active" else "inactive",
                            ),
                            supporting = rule.title,
                        )
                    },
                )
                rules.filter { it.canRepeatOnEveryTrigger }.forEach { rule ->
                    Text(
                        text = "${rule.code}: activa sin frecuencia mínima, puede reaparecer en cada disparo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun MarketSection.label(): String = when (this) {
    MarketSection.PACKS -> "Packs OT"
    MarketSection.SERVICES -> "Servicios"
    MarketSection.PROMOTIONS -> "Promociones"
    MarketSection.POPUPS -> "Pop-ups"
}
