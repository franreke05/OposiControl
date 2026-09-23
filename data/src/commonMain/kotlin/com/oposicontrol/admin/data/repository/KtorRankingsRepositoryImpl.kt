package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.LeagueLevel
import com.oposicontrol.admin.domain.model.RankingEntry
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.UserStanding
import com.oposicontrol.admin.domain.repository.RankingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rankings a través del límite canónico de Ktor.
 *
 * Sólo GET, y no por casualidad: la clasificación la calcula el producto y el
 * panel no la corrige. Si algún día apareciese aquí un `post`, sería la señal de
 * que se ha colado una edición manual de posiciones.
 *
 * Nada de Supabase directo desde el escritorio: la service role vive detrás de
 * Ktor.
 */
class KtorRankingsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : RankingsRepository {

    override suspend fun getLeagueLevels(): AppResult<List<LeagueLevel>> = adapter.safeCall {
        get<LeagueLevelsResponse>(LIGAS).data.map { it.toDomain() }
    }

    override suspend fun getGlobalRanking(page: RankingPageRequest): AppResult<RankingPage> = adapter.safeCall {
        get<RankingPageDto>(GLOBAL, page).toDomain()
    }

    override suspend fun getLeagueRanking(
        leagueLevelId: String,
        page: RankingPageRequest,
    ): AppResult<RankingPage> = adapter.safeCall {
        get<RankingPageDto>("$LIGA/$leagueLevelId", page).toDomain()
    }

    override suspend fun getUserStanding(userId: String): AppResult<UserStanding> = adapter.safeCall {
        get<UserStandingResponse>("$USUARIO/$userId").data.toDomain()
    }

    override suspend fun searchUsers(query: String, page: RankingPageRequest): AppResult<RankingPage> =
        adapter.safeCall {
            get<RankingPageDto>(USUARIOS, page, search = query.trim().takeIf { it.isNotBlank() }).toDomain()
        }

    /**
     * El `limit`/`offset` que sale a la red es el saneado por [RankingPageRequest],
     * nunca el crudo: el tope duro se aplica también aquí para que el panel no
     * llegue siquiera a pedir una descarga completa de la tabla.
     */
    private suspend inline fun <reified R> get(
        endpoint: String,
        page: RankingPageRequest? = null,
        search: String? = null,
    ): R {
        val response = client.get(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            if (page != null) {
                parameter("limit", page.limit)
                parameter("offset", page.offset)
            }
            if (search != null) parameter("q", search)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        return response.body()
    }

    suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }

    companion object {
        const val RANKINGS = "/api/v1/admin/rankings"
        const val LIGAS = "$RANKINGS/ligas"
        const val GLOBAL = "$RANKINGS/global"
        const val LIGA = "$RANKINGS/liga"
        const val USUARIO = "$RANKINGS/usuario"
        const val USUARIOS = "$RANKINGS/usuarios"
    }
}

// ---------- DTOs de transporte ----------

@Serializable
data class LeagueLevelsResponse(val data: List<LeagueLevelDto> = emptyList())

@Serializable
data class LeagueLevelDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    @SerialName("preguntas_minimas") val preguntasMinimas: Int = 0,
    @SerialName("ventana_calculo") val ventanaCalculo: Int = 0,
    val activa: Boolean = true,
    val posicion: Int = 0,
)

/**
 * `total` nullable a propósito: el backend omite el campo cuando el conteo no se
 * pudo obtener, y el default `null` mantiene la distinción entre "cero real" y
 * "no lo sé" hasta la pantalla.
 */
@Serializable
data class RankingPageDto(
    val data: List<RankingEntryDto> = emptyList(),
    val total: Int? = null,
    val limit: Int = RankingPageRequest.DEFAULT_LIMIT,
    val offset: Int = 0,
    val seccionesNoDisponibles: List<String> = emptyList(),
)

@Serializable
data class RankingEntryDto(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    @SerialName("liga_nivel_id") val ligaNivelId: String? = null,
    @SerialName("liga_codigo") val ligaCodigo: String? = null,
    @SerialName("liga_nombre") val ligaNombre: String? = null,
    @SerialName("ventana_calculo") val ventanaCalculo: Int? = null,
    @SerialName("preguntas_validas_ranking") val preguntasValidasRanking: Int? = null,
    @SerialName("nota_media_actual") val notaMediaActual: Double? = null,
    @SerialName("nota_referencia_evolucion") val notaReferenciaEvolucion: Double? = null,
    @SerialName("evolucion_nota") val evolucionNota: Double? = null,
    @SerialName("preguntas_desde_ultima_evolucion") val preguntasDesdeUltimaEvolucion: Int? = null,
    @SerialName("ranking_liga_posicion") val rankingLigaPosicion: Int? = null,
    @SerialName("ranking_global_posicion") val rankingGlobalPosicion: Int? = null,
    @SerialName("percentil_global") val percentilGlobal: Double? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

@Serializable
data class UserStandingResponse(val data: UserStandingDto = UserStandingDto())

@Serializable
data class UserStandingDto(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    val competitivo: RankingEntryDto? = null,
    val liga: LeagueLevelDto? = null,
    val seccionesNoDisponibles: List<String> = emptyList(),
)

internal fun LeagueLevelDto.toDomain() = LeagueLevel(
    id = id,
    code = codigo,
    name = nombre,
    minimumQuestions = preguntasMinimas,
    calculationWindow = ventanaCalculo,
    active = activa,
    position = posicion,
)

internal fun RankingEntryDto.toDomain() = RankingEntry(
    userId = usuarioId,
    displayName = nombreMostrar,
    username = username,
    leagueLevelId = ligaNivelId,
    leagueCode = ligaCodigo,
    leagueName = ligaNombre,
    calculationWindow = ventanaCalculo,
    rankingEligibleQuestions = preguntasValidasRanking,
    currentAverage = notaMediaActual,
    evolutionReference = notaReferenciaEvolucion,
    evolution = evolucionNota,
    questionsSinceLastEvolution = preguntasDesdeUltimaEvolucion,
    leagueRank = rankingLigaPosicion,
    globalRank = rankingGlobalPosicion,
    globalPercentile = percentilGlobal,
    updatedAt = actualizadoEn,
)

internal fun RankingPageDto.toDomain() = RankingPage(
    entries = data.map { it.toDomain() },
    total = total,
    limit = limit,
    offset = offset,
    unavailableSections = seccionesNoDisponibles.toSet(),
)

internal fun UserStandingDto.toDomain() = UserStanding(
    userId = usuarioId,
    displayName = nombreMostrar,
    username = username,
    competitive = competitivo?.toDomain(),
    league = liga?.toDomain(),
    unavailableSections = seccionesNoDisponibles.toSet(),
)
