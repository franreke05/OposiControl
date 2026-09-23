package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.AnalyticsSectionReport

/**
 * Analítica de producto, sólo lectura.
 *
 * Una sección por llamada y no un informe completo: abrir "Producto" no debe
 * disparar los conteos de economía. Todo pasa por el límite canónico de Ktor —
 * son lecturas con service role sobre tablas de negocio, así que el cliente no
 * habla nunca directamente con Supabase.
 *
 * No hay ningún método de escritura, y no debe haberlo: esta superficie mide, no
 * modifica.
 */
interface ProductAnalyticsRepository {
    suspend fun getSection(section: AnalyticsSection): AppResult<AnalyticsSectionReport>
}
