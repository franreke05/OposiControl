package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.result.AppError
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
import com.oposicontrol.admin.domain.model.GradingSnapshot
import com.oposicontrol.admin.domain.model.OfficialQuestionDraft
import com.oposicontrol.admin.domain.model.OfficialQuestions
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.ReferralPage
import com.oposicontrol.admin.domain.model.ReferralRevokeResult
import com.oposicontrol.admin.domain.repository.ControlV1Repository

/** Doble del repositorio para las pruebas del editor de exámenes: solo las reglas y las marcas de preguntas hacen algo. */
internal class ExamsFakeControlRepo(
    var rules: GradingRules? = null,
    var audit: List<GradingAuditEntry> = emptyList(),
    var official: OfficialQuestions? = null,
) : ControlV1Repository {
    val saves = mutableListOf<String>()
    val questionSaves = mutableListOf<String>()
    var failSave = false
    var failQuestionSave = false

    override suspend fun getGradingRules(examId: String): AppResult<GradingRules> = AppResult.Success(requireNotNull(rules))

    override suspend fun saveGradingRules(
        examId: String,
        annulledRule: String?,
        rationalPenalty: Boolean,
        numerator: Int,
        denominator: Int,
        reason: String?,
    ): AppResult<GradingRules> {
        saves += "$annulledRule|$rationalPenalty|$numerator/$denominator|$reason"
        if (failSave) return AppResult.Failure(AppError.Validation("rechazado por el servidor", "OFFICIAL_RULES_INVALID"))
        val updated = requireNotNull(rules).copy(
            annulledRule = annulledRule, rationalPenalty = rationalPenalty, fixedNumerator = numerator, fixedDenominator = denominator,
        )
        rules = updated
        audit = listOf(
            GradingAuditEntry(
                "a${audit.size + 1}", "2026-10-18T10:00:00Z", "actor", reason,
                GradingSnapshot(null, false, 0, 1), GradingSnapshot(annulledRule, rationalPenalty, numerator, denominator),
            ),
        ) + audit
        return AppResult.Success(updated)
    }

    override suspend fun getGradingAudit(examId: String): AppResult<List<GradingAuditEntry>> = AppResult.Success(audit)

    override suspend fun officialQuestions(examId: String): AppResult<OfficialQuestions> = AppResult.Success(requireNotNull(official))

    override suspend fun saveOfficialQuestion(examId: String, questionId: String, draft: OfficialQuestionDraft): AppResult<OfficialQuestions> {
        questionSaves += "$questionId|${draft.annulled}|${draft.reason}|${draft.isReserve}|${draft.order}"
        if (failQuestionSave) return AppResult.Failure(AppError.Validation("Esta función todavía no está disponible en el servidor conectado."))
        val current = requireNotNull(official)
        val updated = current.copy(
            questions = current.questions.map {
                if (it.questionId != questionId) {
                    it
                } else {
                    it.copy(
                        annulled = draft.annulled,
                        annulmentReason = draft.reason.ifBlank { null }.takeIf { draft.annulled },
                        isReserve = draft.isReserve,
                        reserveOrder = draft.order.toIntOrNull().takeIf { draft.isReserve },
                    )
                }
            },
        )
        official = updated
        return AppResult.Success(updated)
    }

    override suspend fun getConfig(group: String): AppResult<List<ConfigEntry>> = TODO()
    override suspend fun setConfig(key: String, value: Int): AppResult<ConfigEntry> = TODO()
    override suspend fun listReferrals(state: String?, cursor: String?): AppResult<ReferralPage> = TODO()
    override suspend fun revokeReferral(id: String, reason: String): AppResult<ReferralRevokeResult> = TODO()
    override suspend fun forumQueue(type: ForumContentType, state: String, cursor: String?): AppResult<ForumQueuePage> = TODO()
    override suspend fun moderateForum(type: ForumContentType, id: String, action: ForumAction, reason: String?, days: Int?): AppResult<Unit> = TODO()
    override suspend fun forumChannels(): AppResult<List<ForumChannel>> = TODO()
    override suspend fun createForumChannel(draft: ForumChannelDraft): AppResult<ForumChannel> = TODO()
    override suspend fun updateForumChannel(id: String, draft: ForumChannelDraft): AppResult<ForumChannel> = TODO()
    override suspend fun forumBlockList(): AppResult<List<String>> = TODO()
    override suspend fun addForumBlock(pattern: String): AppResult<Unit> = TODO()
    override suspend fun removeForumBlock(pattern: String): AppResult<Unit> = TODO()
    override suspend fun forumRestrictions(): AppResult<List<ForumRestriction>> = TODO()
    override suspend fun liftForumRestriction(userId: String): AppResult<Unit> = TODO()
    override suspend fun examEconomy(offset: Int, onlyWithoutCommunity: Boolean): AppResult<ExamEconomyPage> = TODO()
    override suspend fun saveExamEconomy(examId: String, draft: ExamEconomyDraft): AppResult<ExamEconomy> = TODO()
    override suspend fun orders(state: String?, offset: Int): AppResult<OrderPage> = TODO()
    override suspend fun ordersInReviewCount(): AppResult<Int> = TODO()
    override suspend fun order(id: String): AppResult<OrderItem> = TODO()
    override suspend fun addOrderNote(id: String, note: String): AppResult<Unit> = TODO()
    override suspend fun fastWindows(offset: Int): AppResult<FastWindowPage> = TODO()
    override suspend fun forumCounterDrift(): AppResult<List<ForumCounterDrift>> = TODO()
}
