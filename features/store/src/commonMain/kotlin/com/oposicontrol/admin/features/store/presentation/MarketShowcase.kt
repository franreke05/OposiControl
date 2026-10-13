package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketService
import kotlin.time.Clock

internal const val MARKET_SHOWCASE_TAG: String = "market-showcase"

/** Hoy, en UTC y con forma `2026-10-14`: las ventanas de las promociones son días completos en UTC. */
// Un `Instant` se escribe en ISO y en UTC: sus diez primeros caracteres son el dia.
internal fun todayIsoDate(): String = Clock.System.now().toString().take(10)

/**
 * Una pestaña de la tienda lista para pintar: lo que está EN VIGOR de una clase de cosa, ya convertido en filas.
 *
 * [consumedByApp] dice si OposiBot lee hoy esa clase: productos sí; servicios, promociones y avisos todavía no. La
 * pantalla lo enseña para que «está en vigor» no se lea como «el usuario ya lo ve».
 */
internal data class MarketShowcase(
    val title: String,
    val icon: AppIcon,
    val noun: String,
    val rows: UiAsync<List<CatalogRowSpec>>,
    /** Cuántos hay configurados en total, en vigor o no. `null` mientras no se sabe. */
    val configured: Int?,
    val consumedByApp: Boolean,
)

/** Un producto se vende si está activo Y visible: activo pero oculto no aparece en la tienda de la app. */
internal fun showcaseProducts(products: List<CommercialProduct>): List<CommercialProduct> = products.filter { it.active && it.visible }

internal fun showcaseServices(services: List<MarketService>): List<MarketService> = services.filter { it.active }

internal fun showcasePopups(rules: List<MarketPopupRule>): List<MarketPopupRule> = rules.filter { it.active }

/**
 * Una promoción está en vigor si está activa y [today] cae dentro de su ventana. Las fechas son días completos en UTC, así
 * que basta comparar el día (`2026-10-14`); una ventana abierta por un lado no limita por ese lado.
 */
internal fun showcasePromotions(promotions: List<MarketPromotion>, today: String): List<MarketPromotion> = promotions.filter { promotion ->
    val from = promotion.validFrom?.take(10)
    val until = promotion.validUntil?.take(10)
    promotion.active && (from == null || from <= today) && (until == null || until >= today)
}

/** Convierte lo leído en la pestaña a pintar, conservando cargando / error / vacío tal cual. */
private fun <T> UiAsync<List<T>>.inForce(keep: (List<T>) -> List<T>, row: (T) -> CatalogRowSpec): UiAsync<List<CatalogRowSpec>> = when (this) {
    is UiAsync.Success -> keep(value).map(row).let { if (it.isEmpty()) UiAsync.Empty else UiAsync.Success(it) }
    is UiAsync.Error -> this
    UiAsync.Empty -> UiAsync.Empty
    UiAsync.Loading -> UiAsync.Loading
    UiAsync.Idle -> UiAsync.Idle
}

private fun UiAsync<List<*>>.count(): Int? = when (this) {
    is UiAsync.Success -> value.size
    UiAsync.Empty -> 0
    else -> null
}

internal fun showcaseOf(section: MarketSection, state: MarketState, today: String): MarketShowcase = when (section) {
    MarketSection.SERVICES -> MarketShowcase(
        title = "Servicios en vigor",
        icon = AppIcon.MARKET,
        noun = "servicios",
        rows = state.services.items.inForce(::showcaseServices, ::serviceRowSpec),
        configured = state.services.items.count(),
        consumedByApp = false,
    )
    MarketSection.PROMOTIONS -> MarketShowcase(
        title = "Promociones en vigor",
        icon = AppIcon.PLANS,
        noun = "promociones",
        rows = state.promotions.items.inForce({ showcasePromotions(it, today) }, ::promotionRowSpec),
        configured = state.promotions.items.count(),
        consumedByApp = false,
    )
    MarketSection.POPUPS -> MarketShowcase(
        title = "Avisos en vigor",
        icon = AppIcon.EVENTS,
        noun = "avisos",
        rows = state.popups.items.inForce(::showcasePopups, ::popupRowSpec),
        configured = state.popups.items.count(),
        consumedByApp = false,
    )
    // Productos y, por si llegara, cualquier otra sección: lo que la app sí vende.
    else -> MarketShowcase(
        title = "Productos y packs a la venta",
        icon = AppIcon.CATALOG,
        noun = "productos",
        rows = state.products.inForce(::showcaseProducts, ::productRowSpec),
        configured = state.products.count(),
        consumedByApp = true,
    )
}

/**
 * Una pestaña de la tienda: filas de consulta, sin selección ni edición.
 *
 * Reutiliza [WorkspaceCatalogRow] con un `onClick` vacío a propósito: la fila es la misma que en Catálogo para que se
 * reconozca, pero aquí no abre nada. Quien quiera cambiarla tiene «Editar en Catálogo» en la cabecera de la pantalla.
 */
@Composable
internal fun MarketShowcaseSection(showcase: MarketShowcase, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    WorkspaceSection(
        icon = showcase.icon,
        role = SemanticRole.COMMERCE,
        title = showcase.title,
        modifier = modifier.testTag(MARKET_SHOWCASE_TAG),
        action = { (showcase.rows as? UiAsync.Success)?.let { WorkspacePill(text = it.value.size.toString()) } },
    ) {
        if (!showcase.consumedByApp) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.INFO,
                title = "Configurado, pero la app todavía no lo usa",
                detail = "OposiBot hoy sólo lee productos, tarifas y planes. Esto está guardado en el servidor, pero la app todavía no lo enseña a los usuarios.",
            )
        }
        // En reposo todavía no se ha pedido nada: se dice, no se pinta un esqueleto de carga que no se iría.
        if (showcase.rows is UiAsync.Idle) {
            EmptyStateCard(title = "Sin cargar", message = "Pulsa «Actualizar» para leer los ${showcase.noun}.", icon = showcase.icon)
            return@WorkspaceSection
        }
        AsyncStatePane(
            state = showcase.rows,
            emptyTitle = "Nada en vigor",
            emptyMessage = when (showcase.configured) {
                null, 0 -> "No hay ${showcase.noun} configurados. Se crean en Catálogo."
                else -> "Hay ${showcase.configured} ${showcase.noun} configurados, pero ninguno está en vigor ahora. Se activan en Catálogo."
            },
            emptyIcon = showcase.icon,
            loadingRows = 5,
            onRetry = onRetry,
            stableContent = true,
        ) { rows ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    rows.forEach { row ->
                        key(row.key) {
                            WorkspaceCatalogRow(
                                title = row.title,
                                selected = false,
                                onClick = {},
                                icon = showcase.icon,
                                eyebrow = row.eyebrow,
                                description = row.description,
                                meta = row.meta,
                                trailing = { StatusChip(status = row.status, tone = row.statusTone) },
                            )
                        }
                    }
                }
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}
