package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.model.PlanBenefit
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository

internal const val PLAN_FREE = "aaaaaaaa-0000-4000-8000-000000000000"
internal const val PLAN_PRO = "aaaaaaaa-0000-4000-8000-000000000001"
internal const val PRODUCT_PACK = "bbbbbbbb-0000-4000-8000-000000000001"
internal const val PRODUCT_SUB = "bbbbbbbb-0000-4000-8000-000000000002"

internal fun samplePlans(): List<CommercialPlan> = listOf(
    CommercialPlan(PLAN_FREE, "gratuito", "Gratuito", level = 0, position = 1),
    CommercialPlan(
        id = PLAN_PRO,
        code = "razonado",
        name = "Razonado",
        level = 2,
        position = 2,
        benefits = listOf(
            PlanBenefit("b1", PLAN_PRO, "examenes_mensuales", integerValue = 10),
            PlanBenefit("b2", PLAN_PRO, "sin_publicidad", booleanValue = true),
        ),
    ),
)

internal fun sampleProducts(): List<CommercialProduct> = listOf(
    CommercialProduct(PRODUCT_PACK, "pack_500", "pack_tickets", "500 OposiTickets", priceCents = 499, ticketAmount = 500, position = 1),
    CommercialProduct(
        id = PRODUCT_SUB,
        code = "sub_razonado",
        productType = "suscripcion",
        name = "Suscripción Razonado",
        planId = PLAN_PRO,
        position = 2,
        tariffs = listOf(CommercialTariff("t1", PRODUCT_SUB, "mensual", "first_year", 299, position = 1)),
    ),
)

/** Repositorio en memoria: guarda lo que se le manda y asigna id a lo nuevo, como el servidor. */
internal class FakeCatalogRepository(
    products: List<CommercialProduct> = sampleProducts(),
    plans: List<CommercialPlan> = samplePlans(),
) : CommercialCatalogRepository {
    var products: List<CommercialProduct> = products
    var plans: List<CommercialPlan> = plans
    var productsFailure: AppError? = null
    var plansFailure: AppError? = null
    var saveFailure: AppError? = null

    var productReads = 0
    var planReads = 0
    val savedProducts = mutableListOf<CommercialProduct>()
    val savedTariffs = mutableListOf<Pair<String, CommercialTariff>>()
    val savedPlans = mutableListOf<CommercialPlan>()
    val savedBenefits = mutableListOf<Pair<String, PlanBenefit>>()
    val deletedProducts = mutableListOf<String>()
    val deletedTariffs = mutableListOf<Pair<String, String>>()
    var deleteFailure: AppError? = null
    private var nextId = 0

    private fun newId(prefix: String) = "$prefix-new-${++nextId}"

    override suspend fun getProducts(): AppResult<List<CommercialProduct>> {
        productReads++
        return productsFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(products)
    }

    override suspend fun saveProduct(product: CommercialProduct): AppResult<CommercialProduct> {
        saveFailure?.let { return AppResult.Failure(it) }
        savedProducts += product
        val saved = product.copy(id = product.id.ifBlank { newId("product") })
        products = if (products.any { it.id == saved.id }) products.map { if (it.id == saved.id) saved else it } else products + saved
        return AppResult.Success(saved)
    }

    override suspend fun saveTariff(productId: String, tariff: CommercialTariff): AppResult<CommercialTariff> {
        saveFailure?.let { return AppResult.Failure(it) }
        savedTariffs += productId to tariff
        return AppResult.Success(tariff.copy(id = tariff.id.ifBlank { newId("tariff") }))
    }

    override suspend fun deleteProduct(productId: String): AppResult<Unit> {
        deleteFailure?.let { return AppResult.Failure(it) }
        deletedProducts += productId
        products = products.filterNot { it.id == productId }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteTariff(productId: String, tariffId: String): AppResult<Unit> {
        deleteFailure?.let { return AppResult.Failure(it) }
        deletedTariffs += productId to tariffId
        return AppResult.Success(Unit)
    }

    override suspend fun getPlans(): AppResult<List<CommercialPlan>> {
        planReads++
        return plansFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(plans)
    }

    override suspend fun savePlan(plan: CommercialPlan): AppResult<CommercialPlan> {
        saveFailure?.let { return AppResult.Failure(it) }
        savedPlans += plan
        val saved = plan.copy(id = plan.id.ifBlank { newId("plan") })
        plans = if (plans.any { it.id == saved.id }) plans.map { if (it.id == saved.id) saved else it } else plans + saved
        return AppResult.Success(saved)
    }

    override suspend fun savePlanBenefit(planId: String, benefit: PlanBenefit): AppResult<PlanBenefit> {
        saveFailure?.let { return AppResult.Failure(it) }
        savedBenefits += planId to benefit
        return AppResult.Success(benefit.copy(id = benefit.id.ifBlank { newId("benefit") }, planId = planId))
    }
}
