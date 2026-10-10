package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorMarketCatalogRepositoryImpl
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorMarketCatalogRepositoryImpl.kt"
private const val M = "/api/v1/admin/market"

private fun Env.repo() = KtorMarketCatalogRepositoryImpl(client, appConfig, session, adapter)

/**
 * Filas del registrador (R4-REC-B): KtorMarketCatalogRepositoryImpl. Son 4 sitios: los ayudantes `getList` (packs, sin
 * paginar), `allPages` (servicios, promociones y pop-ups, que el backend sirve en paginas OFFSET) y `post`, mas la
 * lectura de usos de una promocion. Cada fila ejecuta los metodos de su sitio, declara sus llamadas y los nombra en
 * `covers` (cobertura por punto de entrada, R5-HARDEN).
 */
internal val ROWS_B_MARKET_CATALOG_REPO: List<CallRow> = listOf(
    CallRow(FILE, "getList(packs)", listOf(get("$M/packs")), covers = setOf("getTicketPacks")) { repo().getTicketPacks() },
    CallRow(FILE, "allPages(servicios,promociones,popups)",
        listOf(get("$M/servicios?limit&offset"), get("$M/promociones?limit&offset"), get("$M/popups?limit&offset")),
        covers = setOf("getServices", "getPromotions", "getPopupRules"),
    ) {
        val r = repo()
        r.getServices(); r.getPromotions(); r.getPopupRules()
    },
    CallRow(FILE, "getPromotionUses", listOf(get("$M/promociones/{}/usos?cursor&limit"))) { repo().getPromotionUses("ID0", "c1") },
    CallRow(FILE, "post(packs,servicios,promociones,popups)", listOf(post("$M/packs"), post("$M/servicios"), post("$M/promociones"), post("$M/popups")),
        covers = setOf("saveTicketPack", "saveService", "savePromotion", "savePopupRule"),
    ) {
        val r = repo()
        r.saveTicketPack(MarketTicketPack(id = "ID0", code = "c", name = "n", ticketAmount = 1, priceCents = 1))
        r.saveService(MarketService(id = "ID0", code = "c", name = "n", serviceType = "t"))
        r.savePromotion(MarketPromotion(id = "ID0", code = "c", name = "n", discountType = "t", value = 1.0))
        r.savePopupRule(MarketPopupRule(id = "ID0", code = "c", trigger = "t", title = "t", message = "m"))
    },
)
