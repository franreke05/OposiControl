package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUsesPage
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack

/**
 * OposiMarket: packs de OposiTickets, servicios, promociones y reglas de pop-up.
 *
 * Todo pasa por el límite canónico de Ktor. Son datos comerciales y de dinero:
 * el cliente nunca habla directamente con Supabase para esto.
 *
 * Servicios, promociones y pop-ups se sirven en páginas (`items`, `total`, `limit`, `offset`): sus lecturas recorren
 * todas las páginas hasta `total`, así que ninguna lista se queda en la primera en silencio.
 */
interface MarketCatalogRepository {
    suspend fun getTicketPacks(): AppResult<List<MarketTicketPack>>
    suspend fun saveTicketPack(pack: MarketTicketPack): AppResult<MarketTicketPack>

    suspend fun getServices(): AppResult<List<MarketService>>
    suspend fun saveService(service: MarketService): AppResult<MarketService>

    suspend fun getPromotions(): AppResult<List<MarketPromotion>>
    suspend fun savePromotion(promotion: MarketPromotion): AppResult<MarketPromotion>

    /** Una página de usos de la promoción (más recientes primero). [cursor] es el `nextCursor` de la página anterior. */
    suspend fun getPromotionUses(promotionId: String, cursor: String? = null): AppResult<MarketPromotionUsesPage>

    suspend fun getPopupRules(): AppResult<List<MarketPopupRule>>
    suspend fun savePopupRule(rule: MarketPopupRule): AppResult<MarketPopupRule>
}
