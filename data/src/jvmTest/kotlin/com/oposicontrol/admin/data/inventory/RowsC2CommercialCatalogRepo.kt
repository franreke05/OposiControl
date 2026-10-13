package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorCommercialCatalogRepositoryImpl
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.model.PlanBenefit

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorCommercialCatalogRepositoryImpl.kt"
private const val P = "/api/v1/admin/comercial/productos"

private fun Env.repo() = KtorCommercialCatalogRepositoryImpl(client, appConfig, session, adapter)

private const val PL = "/api/v1/admin/planes"

/**
 * Filas del registrador (R5-REC-C2): KtorCommercialCatalogRepositoryImpl (7 sitios). `getProducts` y `getPlans` comparten
 * el ayudante paginado `allPages` (un sitio, dos puntos de entrada): la fila ejecuta los dos y los nombra en `covers`.
 */
internal val ROWS_C2_COMMERCIAL_CATALOG_REPO: List<CallRow> = listOf(
    CallRow(FILE, "allPages(productos,planes)", listOf(get("$P?limit&offset"), get("$PL?limit&offset")), covers = setOf("getProducts", "getPlans")) {
        val r = repo()
        r.getProducts(); r.getPlans()
    },
    CallRow(FILE, "savePlan", post(PL)) { repo().savePlan(CommercialPlan(id = "ID0", code = "c", name = "n")) },
    CallRow(FILE, "savePlanBenefit", post("$PL/{}/beneficios")) {
        repo().savePlanBenefit("ID0", PlanBenefit(id = "ID1", planId = "ID0", code = "c", booleanValue = true))
    },
    CallRow(FILE, "saveProduct", post(P)) {
        repo().saveProduct(CommercialProduct(id = "ID0", code = "c", productType = "t", name = "n"))
    },
    CallRow(FILE, "saveTariff", post("$P/{}/tarifas")) {
        repo().saveTariff("ID0", CommercialTariff(id = "ID1", productId = "ID0", billingPeriod = "mensual", pricePhase = "p", priceCents = 1))
    },
    CallRow(FILE, "deleteProduct", delete("$P/{}")) { repo().deleteProduct("ID0") },
    CallRow(FILE, "deleteTariff", delete("$P/{}/tarifas/{}")) { repo().deleteTariff("ID0", "ID1") },
)
