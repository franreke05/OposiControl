package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.core.common.result.AppError

interface ModerationRepository {
    suspend fun listPendingReports(): AppResult<List<ForumReport>>
    /** [includeResolved] = true trae todos los estados; false replica [listPendingReports]. */
    suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>>
    suspend fun getReport(reportId: String): AppResult<ForumReportDetail>
    suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit>
    /** Concede tickets manualmente a un usuario. Devuelve el nuevo saldo. */
    /**
     * Concede OposiTickets.
     *
     * [idempotencyKey] es obligatoria y debe ser **estable para la misma
     * operación lógica**: si se generase una nueva en cada intento, un doble clic
     * o un reintento de red concedería los tickets dos veces. Quien llama la fija
     * al abrir el diálogo, no al pulsar.
     */
    suspend fun grantTickets(
        userId: String,
        amount: Int,
        reason: String,
        idempotencyKey: String,
    ): AppResult<Int>
    /** Registra una advertencia de moderación sobre un usuario. */
    suspend fun warnUser(userId: String, message: String): AppResult<Unit>

    /**
     * Da de baja ([blocked] = true) o reactiva una cuenta, con un motivo obligatorio. Solo lo puede hacer un
     * superadministrador: lo decide el servidor (403 si no lo es). No borra nada; la cuenta deja de poder entrar.
     *
     * Con implementación por defecto porque es una operación nueva: un repositorio que no la conozca dice que no puede,
     * nunca que la hizo.
     */
    suspend fun setAccountBlocked(userId: String, blocked: Boolean, reason: String): AppResult<Unit> =
        AppResult.Failure(AppError.Validation("Este entorno no puede cambiar el estado de una cuenta."))
}
