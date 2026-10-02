package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorProductAnalyticsRepositoryImpl
import com.oposicontrol.admin.domain.model.AnalyticsSection

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorProductAnalyticsRepositoryImpl.kt"
private const val A = "/api/v1/admin/analytics"

/**
 * Filas del registrador (R5-REC-C2): KtorProductAnalyticsRepositoryImpl. Un solo sitio (el ayudante `get`) y un solo punto
 * de entrada (`getSection`): la fila recorre las 6 secciones y declara las 6 rutas.
 */
internal val ROWS_C2_PRODUCT_ANALYTICS_REPO: List<CallRow> = listOf(
    CallRow(
        FILE, "get(producto,estudio,retencion,comunidad,economia,marketing)",
        listOf(get("$A/producto"), get("$A/estudio"), get("$A/retencion"), get("$A/comunidad"), get("$A/economia"), get("$A/marketing")),
        covers = setOf("getSection"),
    ) {
        val r = KtorProductAnalyticsRepositoryImpl(client, appConfig, session, adapter)
        AnalyticsSection.entries.forEach { r.getSection(it) }
    },
)
