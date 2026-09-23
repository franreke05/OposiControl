package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.LeagueLevel
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.UserStanding

/**
 * Lectura de rankings y ligas.
 *
 * **El contrato es de solo lectura y lo es a propósito.** La clasificación la
 * calcula el producto; que un administrador pueda corregirla a mano destruiría
 * su significado, y no habría forma de distinguir después una posición calculada
 * de una retocada. Si un ranking está mal, se arregla el cálculo.
 *
 * Todo pasa por el límite canónico de Ktor: el cliente no habla con Supabase.
 */
interface RankingsRepository {

    /** Catálogo de niveles de liga. Es el único dato de este vertical hoy poblado. */
    suspend fun getLeagueLevels(): AppResult<List<LeagueLevel>>

    /** Clasificación global paginada. */
    suspend fun getGlobalRanking(page: RankingPageRequest): AppResult<RankingPage>

    /** Clasificación dentro de un nivel de liga. */
    suspend fun getLeagueRanking(leagueLevelId: String, page: RankingPageRequest): AppResult<RankingPage>

    /** Posición competitiva de un usuario concreto. */
    suspend fun getUserStanding(userId: String): AppResult<UserStanding>

    /**
     * Búsqueda de usuarios por nombre o username, con su estado competitivo ya
     * resuelto: se busca a alguien precisamente para ver dónde está clasificado.
     */
    suspend fun searchUsers(query: String, page: RankingPageRequest): AppResult<RankingPage>
}
