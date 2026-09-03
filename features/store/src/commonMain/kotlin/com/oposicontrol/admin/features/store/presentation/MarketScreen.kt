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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.Spacing
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
        // Antes eran cuatro botones rellenos, el activo en coral y los otros tres
        // en gris sólido. Cuatro rectángulos de color compitiendo entre sí para
        // decir algo que una pestaña dice con un subrayado: esto no son cuatro
        // acciones, es una sola vista con cuatro caras.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DesktopTabs(
                modifier = Modifier.weight(1f),
                tabs = MarketSection.entries.map { DesktopTab(it.name, it.label(), it.icon()) },
                selectedId = state.section.name,
                onSelect = { id ->
                    MarketSection.entries.firstOrNull { it.name == id }
                        ?.let { viewModel.dispatch(MarketIntent.SelectSection(it)) }
                },
            )
            SecondaryButton(
                text = "Actualizar",
                onClick = { viewModel.dispatch(MarketIntent.Refresh) },
                size = ButtonSize.COMPACT,
                icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
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
        help = Help.packs,
    ) {
        AsyncStatePane(
            state = state.packs,
            emptyTitle = "Sin packs",
            emptyMessage = "No se ha configurado ningún pack de OposiTickets todavía.",
            emptyIcon = AppIcon.CATALOG,
        ) { packs ->
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
        help = Help.potenciadores,
    ) {
        AsyncStatePane(
            state = state.services,
            emptyTitle = "Sin servicios",
            emptyMessage = "No hay potenciadores ni protectores publicados en el Market.",
            emptyIcon = AppIcon.MARKET,
        ) { services ->
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
    FormSection(
        title = "Promociones",
        description = "Cupones y descuentos con ventana de validez.",
        help = Help.promociones,
    ) {
        AsyncStatePane(
            state = state.promotions,
            emptyTitle = "Sin promociones activas",
            emptyMessage = "No se ha creado ninguna promoción todavía.",
            emptyIcon = AppIcon.PLANS,
        ) { promotions ->
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
        help = Help.reglasPopup,
    ) {
        AsyncStatePane(
            state = state.popupRules,
            emptyTitle = "Sin reglas de aparición",
            emptyMessage = "Ninguna oferta tiene reglas: por ahora no se muestra ningún pop-up.",
            emptyIcon = AppIcon.INFO,
        ) { rules ->
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

private fun MarketSection.icon(): AppIcon = when (this) {
    MarketSection.PACKS -> AppIcon.CATALOG
    MarketSection.SERVICES -> AppIcon.MARKET
    MarketSection.PROMOTIONS -> AppIcon.PLANS
    MarketSection.POPUPS -> AppIcon.INFO
}
