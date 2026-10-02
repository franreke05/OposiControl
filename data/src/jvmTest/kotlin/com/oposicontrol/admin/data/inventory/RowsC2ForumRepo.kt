package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorForumRepositoryImpl

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorForumRepositoryImpl.kt"
private const val H = "/api/v1/admin/moderacion/hilos"

private fun Env.repo() = KtorForumRepositoryImpl(client, appConfig, session, adapter)

/** Filas del registrador (R5-REC-C2): KtorForumRepositoryImpl (3 sitios). */
internal val ROWS_C2_FORUM_REPO: List<CallRow> = listOf(
    CallRow(FILE, "listThreads", get("$H?categoriaId&cursor&limit&reportados")) {
        repo().listThreads(cursor = "c", reportedOnly = true, categoryId = "ID1")
    },
    CallRow(FILE, "getThread", get("$H/{}")) { repo().getThread("ID0") },
    CallRow(FILE, "listReplies", get("$H/{}/respuestas?cursor&limit")) { repo().listReplies("ID0", cursor = "c") },
)
