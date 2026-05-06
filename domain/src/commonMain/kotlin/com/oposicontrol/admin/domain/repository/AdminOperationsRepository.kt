package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet

interface AdminOperationsRepository {
    suspend fun getNewsTypes(): AppResult<List<NewsType>>
    suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType>

    suspend fun getExamAccesses(): AppResult<List<ExamAccess>>
    suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess>

    suspend fun getTicketWallets(): AppResult<List<TicketWallet>>
    suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>>
    suspend fun appendTicketAdjustment(userId: String, amount: Double, reason: String): AppResult<TicketTransaction>

    suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>>
    suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>>
    suspend fun sendSupportMessage(
        conversationId: String,
        assignee: String,
        text: String,
        internal: Boolean,
    ): AppResult<SupportMessage>

    suspend fun getModerationActions(): AppResult<List<ModerationAction>>
    suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction>
}
