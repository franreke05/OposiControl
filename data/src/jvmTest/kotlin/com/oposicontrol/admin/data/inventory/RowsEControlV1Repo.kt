package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorControlV1RepositoryImpl
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.OrderDecision
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumContentType

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorControlV1RepositoryImpl.kt"
private const val A = "/api/v1/admin"

private fun Env.repo() = KtorControlV1RepositoryImpl(client, appConfig, session, adapter)

/**
 * Filas del registrador (E6-V1-PANEL): KtorControlV1RepositoryImpl, un sitio de egreso por metodo publico.
 * Los argumentos ejercen todas las ramas con filtros para que cada `parameter("x"` quede grabado.
 */
internal val ROWS_E_CONTROL_V1_REPO: List<CallRow> = listOf(
    CallRow(FILE, "getGradingRules", get("$A/examenes/{}/reglas-puntuacion")) { repo().getGradingRules("ID0") },
    CallRow(FILE, "saveGradingRules", put("$A/examenes/{}/reglas-puntuacion")) { repo().saveGradingRules("ID0", null, false, 0, 1, "motivo") },
    CallRow(FILE, "getGradingAudit", get("$A/examenes/{}/reglas-puntuacion/auditoria")) { repo().getGradingAudit("ID0") },
    CallRow(FILE, "officialQuestions", get("$A/examenes/{}/preguntas-oficiales")) { repo().officialQuestions("ID0") },
    CallRow(FILE, "saveOfficialQuestion", put("$A/examenes/{}/preguntas-oficiales/{}")) {
        repo().saveOfficialQuestion("ID0", "ID1", com.oposicontrol.admin.domain.model.OfficialQuestionDraft(false, "", false, ""))
    },
    CallRow(FILE, "getConfig", get("$A/config-v1?grupo")) { repo().getConfig("referido") },
    CallRow(FILE, "setConfig", put("$A/config-v1/{}")) { repo().setConfig("ID0", 1) },
    CallRow(FILE, "listReferrals", get("$A/referidos/invitaciones?cursor&estado")) { repo().listReferrals("premiada", "c1") },
    CallRow(FILE, "revokeReferral", post("$A/referidos/invitaciones/{}/revocar")) { repo().revokeReferral("ID0", "motivo") },
    CallRow(FILE, "forumQueue", get("$A/foro/cola?cursor&estado&tipo")) { repo().forumQueue(ForumContentType.POST, "pendiente_moderacion", "c1") },
    CallRow(FILE, "moderateForum", post("$A/foro/moderar")) { repo().moderateForum(ForumContentType.POST, "ID0", ForumAction.MUTE, "motivo", 7) },
    CallRow(FILE, "forumChannels", get("$A/foro/canales")) { repo().forumChannels() },
    CallRow(FILE, "createForumChannel", post("$A/foro/canales")) { repo().createForumChannel(ForumChannelDraft.EMPTY.copy(name = "General", slug = "general")) },
    CallRow(FILE, "updateForumChannel", patch("$A/foro/canales/{}")) { repo().updateForumChannel("ID0", ForumChannelDraft.EMPTY.copy(name = "General", slug = "general")) },
    CallRow(FILE, "forumBlockList", get("$A/foro/bloqueos")) { repo().forumBlockList() },
    CallRow(FILE, "addForumBlock", post("$A/foro/bloqueos")) { repo().addForumBlock("casino") },
    CallRow(FILE, "removeForumBlock", delete("$A/foro/bloqueos/{}")) { repo().removeForumBlock("ID0") },
    CallRow(FILE, "forumRestrictions", get("$A/foro/restricciones")) { repo().forumRestrictions() },
    CallRow(FILE, "liftForumRestriction", delete("$A/foro/restricciones/{}")) { repo().liftForumRestriction("ID0") },
    CallRow(FILE, "forumImagePurge", get("$A/foro/imagenes-purga")) { repo().forumImagePurge() },
    CallRow(FILE, "examEconomy", get("$A/examenes-economia?limit&offset&sinComunidad")) { repo().examEconomy(0, true) },
    CallRow(FILE, "saveExamEconomy", patch("$A/examenes-economia/{}")) { repo().saveExamEconomy("ID0", ExamEconomyDraft(true, "100", "30", "c1")) },
    CallRow(FILE, "orders", get("$A/pedidos?estado&limit&offset")) { repo().orders("en_revision", 0) },
    CallRow(FILE, "ordersInReviewCount", get("$A/pedidos/en-revision/total")) { repo().ordersInReviewCount() },
    CallRow(FILE, "order", get("$A/pedidos/{}")) { repo().order("ID0") },

    CallRow(FILE, "addOrderNote", post("$A/pedidos/{}/nota")) { repo().addOrderNote("ID0", "nota") },
    CallRow(FILE, "resolveOrder", post("$A/pedidos/{}/resolver")) { repo().resolveOrder("ID0", OrderDecision.CREDIT, "nota") },
    CallRow(FILE, "fastWindows", get("$A/alertas/ranking-ventanas-rapidas?limit&offset")) { repo().fastWindows(0) },
    CallRow(FILE, "forumCounterDrift", get("$A/alertas/foro-contadores")) { repo().forumCounterDrift() },
)
