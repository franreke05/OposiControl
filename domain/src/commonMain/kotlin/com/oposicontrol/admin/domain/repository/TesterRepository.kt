package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.TesterReportPage

/** Incidencias reales de OposiTester (feedback_pregunta) — independiente de
 * [ModerationRepository] (informes del foro) y de `AdminOperationsRepository`
 * (conversaciones de Soporte general): son tres fuentes de datos distintas. */
interface TesterRepository {
    /**
     * Lista incidencias, más recientes primero. [status] filtra por el estado
     * real exacto ("pendiente", "resuelto", "descartado", "revisado"); `null`
     * trae todos los estados. [type] filtra por el valor real de
     * tipo_feedback si se indica. [cursor] null pide la primera página; en
     * caso contrario continúa donde terminó la página cuyo
     * [TesterReportPage.nextCursor] se pasa aquí.
     */
    suspend fun listReports(
        status: String?,
        cursor: String?,
        type: String? = null,
        pageSize: Int = 20,
    ): AppResult<TesterReportPage>
}
