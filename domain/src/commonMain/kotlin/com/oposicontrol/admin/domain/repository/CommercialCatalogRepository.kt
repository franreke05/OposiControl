package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.model.PlanBenefit

/**
 * Catálogo comercial (planes, packs de OT y tarifas).
 *
 * Separado de [CommerceRepository], que administra el modelo legacy de tienda
 * (`articulo_tienda` y compañía). Éste es el catálogo canónico que consume
 * OposiBot, así que un cambio aquí se ve en la app sin desplegar.
 */
interface CommercialCatalogRepository {
    /**
     * TODOS los productos. El backend los sirve en páginas de como mucho 100 (`items`, `total`, `limit`, `offset`): esta
     * lectura recorre las páginas hasta `total`, así que el catálogo nunca se queda en la primera en silencio.
     */
    suspend fun getProducts(): AppResult<List<CommercialProduct>>

    /** Alta (id en blanco) o cambio de un producto. */
    suspend fun saveProduct(product: CommercialProduct): AppResult<CommercialProduct>

    /** Alta (id en blanco) o cambio de una tarifa del producto. */
    suspend fun saveTariff(productId: String, tariff: CommercialTariff): AppResult<CommercialTariff>

    /** Borra un producto con sus tarifas. El servidor lo rechaza (409) si alguna suscripción o compra lo usa. */
    suspend fun deleteProduct(productId: String): AppResult<Unit>

    /** Borra una tarifa del producto. */
    suspend fun deleteTariff(productId: String, tariffId: String): AppResult<Unit>

    /** TODOS los planes, con sus beneficios. Misma paginación que [getProducts]. */
    suspend fun getPlans(): AppResult<List<CommercialPlan>>

    /** Alta (id en blanco) o cambio de un plan. */
    suspend fun savePlan(plan: CommercialPlan): AppResult<CommercialPlan>

    /** Alta (id en blanco) o cambio de un beneficio del plan. */
    suspend fun savePlanBenefit(planId: String, benefit: PlanBenefit): AppResult<PlanBenefit>
}
