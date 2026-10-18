package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ConfigEntry
import com.oposicontrol.admin.domain.model.ExamEconomy
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ExamEconomyPage
import com.oposicontrol.admin.domain.model.FastWindowPage
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannel
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumCounterDrift
import com.oposicontrol.admin.domain.model.ForumQueuePage
import com.oposicontrol.admin.domain.model.ForumRestriction
import com.oposicontrol.admin.domain.model.GradingAuditEntry
import com.oposicontrol.admin.domain.model.GradingRules
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.ReferralPage
import com.oposicontrol.admin.domain.model.ReferralRevokeResult

/**
 * Superficie de Control V1 contra Ktor (`/api/v1/admin/...`). Todo pasa por el límite canónico: el cliente no habla con
 * Supabase. El servidor valida de nuevo TODO lo que este lado valida por comodidad.
 */
interface ControlV1Repository {
    // ---- reglas de puntuación
    suspend fun getGradingRules(examId: String): AppResult<GradingRules>

    /** Guarda las cuatro columnas de reglas. [reason] es obligatorio en un examen publicado. */
    suspend fun saveGradingRules(
        examId: String,
        annulledRule: String?,
        rationalPenalty: Boolean,
        numerator: Int,
        denominator: Int,
        reason: String?,
    ): AppResult<GradingRules>

    suspend fun getGradingAudit(examId: String): AppResult<List<GradingAuditEntry>>

    // ---- configuración numérica (invitaciones, foro, ranking)
    suspend fun getConfig(group: String): AppResult<List<ConfigEntry>>
    suspend fun setConfig(key: String, value: Int): AppResult<ConfigEntry>

    // ---- invitaciones
    suspend fun listReferrals(state: String?, cursor: String?): AppResult<ReferralPage>
    suspend fun revokeReferral(id: String, reason: String): AppResult<ReferralRevokeResult>

    // ---- foro
    suspend fun forumQueue(type: ForumContentType, state: String, cursor: String?): AppResult<ForumQueuePage>
    suspend fun moderateForum(type: ForumContentType, id: String, action: ForumAction, reason: String?, days: Int?): AppResult<Unit>
    suspend fun forumChannels(): AppResult<List<ForumChannel>>
    suspend fun createForumChannel(draft: ForumChannelDraft): AppResult<ForumChannel>
    suspend fun updateForumChannel(id: String, draft: ForumChannelDraft): AppResult<ForumChannel>
    suspend fun forumBlockList(): AppResult<List<String>>
    suspend fun addForumBlock(pattern: String): AppResult<Unit>
    suspend fun removeForumBlock(pattern: String): AppResult<Unit>
    suspend fun forumRestrictions(): AppResult<List<ForumRestriction>>
    suspend fun liftForumRestriction(userId: String): AppResult<Unit>

    // ---- economía por examen
    suspend fun examEconomy(offset: Int, onlyWithoutCommunity: Boolean): AppResult<ExamEconomyPage>
    suspend fun saveExamEconomy(examId: String, draft: ExamEconomyDraft): AppResult<ExamEconomy>

    // ---- pedidos
    suspend fun orders(state: String?, offset: Int): AppResult<OrderPage>
    suspend fun ordersInReviewCount(): AppResult<Int>
    suspend fun order(id: String): AppResult<OrderItem>
    suspend fun addOrderNote(id: String, note: String): AppResult<Unit>

    // ---- alertas
    suspend fun fastWindows(offset: Int): AppResult<FastWindowPage>
    suspend fun forumCounterDrift(): AppResult<List<ForumCounterDrift>>
}
