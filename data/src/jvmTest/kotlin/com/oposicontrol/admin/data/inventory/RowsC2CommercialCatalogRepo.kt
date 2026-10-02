package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorCommercialCatalogRepositoryImpl
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorCommercialCatalogRepositoryImpl.kt"
private const val P = "/api/v1/admin/comercial/productos"

private fun Env.repo() = KtorCommercialCatalogRepositoryImpl(client, appConfig, session, adapter)

/** Filas del registrador (R5-REC-C2): KtorCommercialCatalogRepositoryImpl (3 sitios). */
internal val ROWS_C2_COMMERCIAL_CATALOG_REPO: List<CallRow> = listOf(
    CallRow(FILE, "getProducts", get(P)) { repo().getProducts() },
    CallRow(FILE, "saveProduct", post(P)) {
        repo().saveProduct(CommercialProduct(id = "ID0", code = "c", productType = "t", name = "n"))
    },
    CallRow(FILE, "saveTariff", post("$P/{}/tarifas")) {
        repo().saveTariff("ID0", CommercialTariff(id = "ID1", productId = "ID0", billingPeriod = "mensual", pricePhase = "p", priceCents = 1))
    },
)
