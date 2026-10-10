package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorAdminOperationsRepositoryImpl
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ModerationAction
import com.oposicontrol.admin.domain.model.NewsType

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorAdminOperationsRepositoryImpl.kt"

private fun Env.repo() = KtorAdminOperationsRepositoryImpl(client, appConfig, session, adapter)

/** Filas del registrador (R4-REC-B): KtorAdminOperationsRepositoryImpl (13 sitios, una fila por metodo). */
internal val ROWS_B_ADMIN_OPERATIONS_REPO: List<CallRow> = listOf(
    CallRow(FILE, "getNewsTypes", get("/admin/news-types")) { repo().getNewsTypes() },
    CallRow(FILE, "upsertNewsType", post("/admin/news-types")) {
        repo().upsertNewsType(NewsType(id = "ID0", name = "n", slug = "n", isActive = true))
    },
    CallRow(FILE, "getExamAccesses", get("/api/v1/admin/accesos")) { repo().getExamAccesses() },
    CallRow(FILE, "upsertExamAccess", post("/api/v1/admin/accesos")) {
        repo().upsertExamAccess(ExamAccess(id = "ID0", userName = "u", examTitle = "e", accessType = "t", isActive = true))
    },
    CallRow(FILE, "getTicketWallets", get("/api/v1/admin/tickets")) { repo().getTicketWallets() },
    CallRow(FILE, "getTicketTransactions", get("/api/v1/admin/tickets/{}/transacciones")) { repo().getTicketTransactions("ID0") },
    CallRow(FILE, "appendTicketAdjustment", post("/api/v1/admin/tickets/{}/transacciones")) {
        repo().appendTicketAdjustment("ID0", 1, "r", "k")
    },
    CallRow(FILE, "getTicketsOverview", get("/api/v1/admin/tickets/resumen")) { repo().getTicketsOverview() },
    CallRow(FILE, "getSupportConversations", get("/api/v1/admin/soporte/conversaciones")) { repo().getSupportConversations() },
    CallRow(FILE, "getSupportMessages", get("/api/v1/admin/soporte/conversaciones/{}/mensajes")) { repo().getSupportMessages("ID0") },
    CallRow(FILE, "sendSupportMessage", post("/api/v1/admin/soporte/conversaciones/{}/mensajes")) {
        repo().sendSupportMessage("ID0", "a", "t", false)
    },
    CallRow(FILE, "getModerationActions", get("/api/v1/admin/moderacion/actions")) { repo().getModerationActions() },
    CallRow(FILE, "appendModerationAction", post("/admin/moderation/actions")) {
        repo().appendModerationAction(ModerationAction(id = "ID0", actionType = "t", targetUser = "u", executor = "e", reason = "r", createdAt = "c"))
    },
)
