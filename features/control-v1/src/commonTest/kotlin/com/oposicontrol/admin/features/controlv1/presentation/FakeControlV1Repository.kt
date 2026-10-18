package com.oposicontrol.admin.features.controlv1.presentation

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
import com.oposicontrol.admin.domain.model.ForumQueueItem
import com.oposicontrol.admin.domain.model.ForumQueuePage
import com.oposicontrol.admin.domain.model.ForumRestriction
import com.oposicontrol.admin.domain.model.GradingAuditEntry
import com.oposicontrol.admin.domain.model.GradingRules
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.ReferralItem
import com.oposicontrol.admin.domain.model.ReferralPage
import com.oposicontrol.admin.domain.model.ReferralRevokeResult
import com.oposicontrol.admin.domain.repository.ControlV1Repository

/** Doble del repositorio: guarda lo que se le pide y contesta lo que se configure. Un fallo se activa con [failNext]. */
class FakeControlV1Repository : ControlV1Repository {
    val calls = mutableListOf<String>()
    var failNext: String? = null

    var gradingRules: GradingRules? = null
    var gradingAudit: List<GradingAuditEntry> = emptyList()
    var config: List<ConfigEntry> = emptyList()
    var referrals: ReferralPage = ReferralPage(emptyList(), null, emptyMap())
    var referralPages: MutableList<ReferralPage> = mutableListOf()
    var revokeResult = ReferralRevokeResult(10, 0)
    var queue: ForumQueuePage = ForumQueuePage(emptyList(), null)
    var channels: List<ForumChannel> = emptyList()
    var blocks: List<String> = emptyList()
    var restrictions: List<ForumRestriction> = emptyList()
    var economy: ExamEconomyPage = ExamEconomyPage(emptyList(), 0, 25, 0)
    var orders: OrderPage = OrderPage(emptyList(), 0, 25, 0)
    var reviewCount = 0
    var fast: FastWindowPage = FastWindowPage(emptyList(), 0, 25, 0, null)
    var drift: List<ForumCounterDrift> = emptyList()

    private fun <T> result(name: String, value: () -> T): AppResult<T> {
        calls += name
        if (failNext == name || failNext == "*") {
            failNext = null
            return AppResult.Failure(AppError.Validation("fallo simulado en $name"))
        }
        return AppResult.Success(value())
    }

    override suspend fun getGradingRules(examId: String) = result("getGradingRules") { requireNotNull(gradingRules) }

    override suspend fun saveGradingRules(
        examId: String,
        annulledRule: String?,
        rationalPenalty: Boolean,
        numerator: Int,
        denominator: Int,
        reason: String?,
    ): AppResult<GradingRules> = result("saveGradingRules:$annulledRule:$rationalPenalty:$numerator/$denominator:$reason") {
        val base = requireNotNull(gradingRules)
        base.copy(annulledRule = annulledRule, rationalPenalty = rationalPenalty, fixedNumerator = numerator, fixedDenominator = denominator)
            .also { gradingRules = it }
    }

    override suspend fun getGradingAudit(examId: String) = result("getGradingAudit") { gradingAudit }
    override suspend fun getConfig(group: String) = result("getConfig:$group") { config }

    override suspend fun setConfig(key: String, value: Int): AppResult<ConfigEntry> = result("setConfig:$key=$value") {
        val updated = config.first { it.key == key }.copy(value = value)
        config = config.map { if (it.key == key) updated else it }
        updated
    }

    override suspend fun listReferrals(state: String?, cursor: String?): AppResult<ReferralPage> = result("listReferrals:$state:$cursor") {
        if (referralPages.isNotEmpty()) referralPages.removeAt(0) else referrals
    }

    override suspend fun revokeReferral(id: String, reason: String) = result("revokeReferral:$id:$reason") { revokeResult }

    override suspend fun forumQueue(type: ForumContentType, state: String, cursor: String?) = result("forumQueue:${type.wire}:$state:$cursor") { queue }

    override suspend fun moderateForum(type: ForumContentType, id: String, action: ForumAction, reason: String?, days: Int?) =
        result("moderateForum:${type.wire}:$id:${action.wire}:$reason:$days") { Unit }

    override suspend fun forumChannels() = result("forumChannels") { channels }

    override suspend fun createForumChannel(draft: ForumChannelDraft) = result("createForumChannel:${draft.slug}") {
        ForumChannel("new", draft.name, draft.slug, draft.description, draft.active, draft.position.toInt(), draft.specialtyId, draft.readOnly)
    }

    override suspend fun updateForumChannel(id: String, draft: ForumChannelDraft) = result("updateForumChannel:$id:${draft.slug}") {
        ForumChannel(id, draft.name, draft.slug, draft.description, draft.active, draft.position.toInt(), draft.specialtyId, draft.readOnly)
    }

    override suspend fun forumBlockList() = result("forumBlockList") { blocks }
    override suspend fun addForumBlock(pattern: String) = result("addForumBlock:$pattern") { blocks = blocks + pattern }
    override suspend fun removeForumBlock(pattern: String) = result("removeForumBlock:$pattern") { blocks = blocks - pattern }
    override suspend fun forumRestrictions() = result("forumRestrictions") { restrictions }

    override suspend fun liftForumRestriction(userId: String) = result("liftForumRestriction:$userId") {
        restrictions = restrictions.filterNot { it.userId == userId }
    }

    override suspend fun examEconomy(offset: Int, onlyWithoutCommunity: Boolean) = result("examEconomy:$offset:$onlyWithoutCommunity") { economy.copy(offset = offset) }

    override suspend fun saveExamEconomy(examId: String, draft: ExamEconomyDraft): AppResult<ExamEconomy> = result("saveExamEconomy:$examId:${draft.sellable}:${draft.price}:${draft.days}:${draft.communityId}") {
        val old = economy.items.first { it.id == examId }
        old.copy(sellableWithTickets = draft.sellable, priceTickets = draft.price.toIntOrNull(), accessDays = draft.days.toInt(), communityId = draft.communityId)
    }

    override suspend fun orders(state: String?, offset: Int) = result("orders:$state:$offset") { orders.copy(offset = offset) }
    override suspend fun ordersInReviewCount() = result("ordersInReviewCount") { reviewCount }
    override suspend fun order(id: String): AppResult<OrderItem> = result("order:$id") { orders.items.first { it.id == id } }
    override suspend fun addOrderNote(id: String, note: String) = result("addOrderNote:$id:$note") { Unit }
    override suspend fun fastWindows(offset: Int) = result("fastWindows:$offset") { fast.copy(offset = offset) }
    override suspend fun forumCounterDrift() = result("forumCounterDrift") { drift }
}

internal fun referralItem(id: String, state: String = "premiada") = ReferralItem(
    id = id, state = state, createdAt = "2026-10-17T10:00:00Z", inviterHandle = "ana", inviteeHandle = "luis",
    inviterPayment = "pagado", inviteePayment = "pagado", paymentAttempts = 0, lastError = null, heldUntil = null,
    rewardedAt = null, reason = null, flags = emptyList(),
)

internal fun queueItem(id: String, state: String = "pendiente_moderacion", type: ForumContentType = ForumContentType.POST) = ForumQueueItem(
    type = type, id = id, postId = null, channel = "General", title = "T", excerpt = "<b>texto</b>", state = state,
    authorHandle = "marta", createdAt = "2026-10-18T08:00:00Z", hasImage = false, pendingReports = 0, reportReasons = emptyList(),
)
