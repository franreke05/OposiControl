package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff

/**
 * Catálogo comercial (planes, packs de OT y tarifas).
 *
 * Separado de [CommerceRepository], que administra el modelo legacy de tienda
 * (`articulo_tienda` y compañía). Éste es el catálogo canónico que consume
 * OposiBot, así que un cambio aquí se ve en la app sin desplegar.
 */
interface CommercialCatalogRepository {
    suspend fun getProducts(): AppResult<List<CommercialProduct>>
    suspend fun saveProduct(product: CommercialProduct): AppResult<CommercialProduct>
    suspend fun saveTariff(productId: String, tariff: CommercialTariff): AppResult<CommercialTariff>
}
