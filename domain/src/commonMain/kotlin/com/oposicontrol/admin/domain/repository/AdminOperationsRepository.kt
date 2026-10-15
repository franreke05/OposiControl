package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType
import com.oposicontrol.admin.domain.model.SupportConversationSummary
import com.oposicontrol.admin.domain.model.SupportMessage
import com.oposicontrol.admin.domain.model.TicketTransaction
import com.oposicontrol.admin.domain.model.TicketWallet
import com.oposicontrol.admin.domain.model.TicketsOverview

interface AdminOperationsRepository {
    suspend fun getNewsTypes(): AppResult<List<NewsType>>
    suspend fun upsertNewsType(newsType: NewsType): AppResult<NewsType>

    suspend fun getExamAccesses(): AppResult<List<ExamAccess>>
    /** Concede un acceso. Siempre es una concesión nueva (o la vigente, si ya la tenía): nunca reescribe otra fila. */
    suspend fun upsertExamAccess(access: ExamAccess): AppResult<ExamAccess>

    /** Retira un acceso: le pone fin de vigencia. Idempotente. */
    suspend fun revokeExamAccess(accessId: String): AppResult<ExamAccess>

    suspend fun getTicketWallets(): AppResult<List<TicketWallet>>
    suspend fun getTicketTransactions(userId: String): AppResult<List<TicketTransaction>>
    /**
     * Ajuste de saldo (positivo abona, negativo retira). [idempotencyKey] identifica la OPERACIÓN: repetir la llamada con
     * la misma clave devuelve el asiento original en vez de aplicar el ajuste dos veces. El backend la exige.
     */
    suspend fun appendTicketAdjustment(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<TicketTransaction>
    suspend fun getTicketsOverview(): AppResult<TicketsOverview>

    suspend fun getSupportConversations(): AppResult<List<SupportConversationSummary>>
    suspend fun getSupportMessages(conversationId: String): AppResult<List<SupportMessage>>
    /**
     * Responde en una conversación de soporte.
     *
     * [assignee] está OBSOLETO y no viaja: el backend (`POST /api/v1/admin/soporte/conversaciones/{id}/mensajes`) nunca
     * aplicó la asignación. Se conserva, con valor por defecto, solo para no romper a los llamadores mientras la UI de
     * soporte deja de pedirlo; los nuevos llamadores lo omiten con argumentos con nombre.
     */
    suspend fun sendSupportMessage(
        conversationId: String,
        assignee: String = "",
        text: String,
        internal: Boolean,
    ): AppResult<SupportMessage>

    suspend fun getModerationActions(): AppResult<List<ModerationAction>>

    /**
     * Registra una medida de moderación sobre un usuario (`POST /api/v1/admin/moderacion/actions`). El backend solo aplica
     * la advertencia (`advertencia`): cualquier otro tipo es un 422/400, nunca un éxito. [ModerationAction.targetUser] debe
     * ser el uuid del usuario; el ejecutor lo pone el servidor desde la sesión. El éxito no devuelve fila: el resultado es
     * la acción enviada, sin id, y quien llama debe releer el historial del servidor en vez de pintarla.
     */
    suspend fun appendModerationAction(action: ModerationAction): AppResult<ModerationAction>
}
