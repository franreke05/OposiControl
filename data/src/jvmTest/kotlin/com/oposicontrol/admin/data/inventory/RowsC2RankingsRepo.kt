package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.data.repository.KtorRankingsRepositoryImpl
import com.oposicontrol.admin.domain.model.RankingPageRequest

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorRankingsRepositoryImpl.kt"
private const val R = "/api/v1/admin/rankings"

/**
 * Filas del registrador (R5-REC-C2): KtorRankingsRepositoryImpl. Un solo sitio (el ayudante `get`) pero 5 metodos publicos:
 * la fila ejecuta los 5, declara las 5 llamadas y los nombra en `covers`.
 */
internal val ROWS_C2_RANKINGS_REPO: List<CallRow> = listOf(
    CallRow(
        FILE, "get(ligas,global,liga,usuario,usuarios)",
        listOf(get("$R/ligas"), get("$R/global"), get("$R/liga/{}"), get("$R/usuario/{}"), get("$R/usuarios")),
        covers = setOf("getLeagueLevels", "getGlobalRanking", "getLeagueRanking", "getUserStanding", "searchUsers"),
    ) {
        val r = KtorRankingsRepositoryImpl(client, appConfig, session, adapter)
        val page = RankingPageRequest.of()
        r.getLeagueLevels(); r.getGlobalRanking(page); r.getLeagueRanking("ID0", page); r.getUserStanding("ID0"); r.searchUsers("q", page)
    },
)
