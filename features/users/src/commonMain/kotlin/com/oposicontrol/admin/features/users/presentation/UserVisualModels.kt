package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserDetailSection

/**
 * Lo que la pantalla de Usuarios PINTA y que se puede decidir sin componer.
 *
 * Es presentación pura: no lee ni escribe nada, sólo da forma a datos que ya
 * están en [UserDetail]. Por eso puede probarse sin Compose y sin ViewModel.
 */

/** Ausencia de dato: la sección existe pero no hay fila. Nunca un cero. */
internal const val NO_DATA_TEXT = "NO_DATA"

/** La sección no se pudo leer. Distinta de [NO_DATA_TEXT]: no es que no haya dato, es que no se sabe. */
internal const val NOT_AVAILABLE_TEXT = "NO_DISPONIBLE"

/** Iniciales del avatar: dos letras como mucho; sin nombre, las de la cuenta; sin nada, «?». */
internal fun userInitials(displayName: String, accountName: String): String {
    val source = displayName.trim().ifBlank { accountName.trim() }
    val words = source.split(Regex("[\\s._-]+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> "${words.first().first()}${words.last().first()}".uppercase()
    }
}

private val AVATAR_ROLES: List<SemanticRole> = listOf(
    // Sin verde ni ámbar: esos tonos ya significan «Registrado» y «Administración» en las
    // pastillas de la misma fila, y un avatar verde competiría con ellas.
    SemanticRole.INFO,
    SemanticRole.INSIGHT,
    SemanticRole.COMMERCE,
    SemanticRole.NEUTRAL,
)

/**
 * La familia de color del avatar de un usuario.
 *
 * Sólo distingue una fila de otra a simple vista: no significa nada (ni estado ni
 * rol). Sale del id para que el mismo usuario tenga siempre el mismo color.
 */
internal fun userAvatarRole(userId: String): SemanticRole =
    AVATAR_ROLES[(userId.hashCode().toLong() and 0x7fffffff).toInt() % AVATAR_ROLES.size]

/** 2450 → «2.450». Sin separador por debajo de mil. */
internal fun formatThousands(value: Int): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** Cómo llega el valor de una tarjeta de la banda de métricas. */
internal enum class UserKpiState {
    /** Hay dato y se enseña. */
    DATA,

    /** La sección se leyó y no tiene fila: no hay dato que enseñar. */
    NO_DATA,

    /** La sección no se pudo leer. */
    UNAVAILABLE,

    /** La ficha todavía se está cargando. */
    LOADING,
}

/** Una tarjeta de la banda de métricas de la ficha. */
internal data class UserKpi(
    val id: String,
    val label: String,
    val icon: AppIcon,
    val role: SemanticRole,
    val state: UserKpiState,
    /** Sólo con [UserKpiState.DATA]. */
    val value: String = "",
    val caption: String? = null,
)

/**
 * Las cinco métricas de la cabecera de la ficha, con la semántica de secciones que
 * ya tiene [UserDetail]: **ausente se dice, no se rellena**. Una sección sin fila es
 * [UserKpiState.NO_DATA]; una que falló al leer es [UserKpiState.UNAVAILABLE]. Ningún
 * estado se sustituye por un cero.
 */
internal fun userKpis(detail: UiAsync<UserDetail>): List<UserKpi> {
    val loaded = (detail as? UiAsync.Success)?.value
    if (loaded == null) {
        val state = when (detail) {
            is UiAsync.Error -> UserKpiState.UNAVAILABLE
            UiAsync.Empty -> UserKpiState.NO_DATA
            else -> UserKpiState.LOADING
        }
        return kpiSkeleton(state)
    }

    val competitive = loaded.competitive
    val competitiveState = sectionState(loaded, UserDetailSection.COMPETITIVE, competitive != null)
    val league = competitive?.let { it.leagueName ?: it.leagueCode }
    val rank = competitive?.globalRank
    val streak = loaded.streak
    val streakState = sectionState(loaded, UserDetailSection.STREAK, streak != null)
    val economy = loaded.economy
    val economyState = sectionState(loaded, UserDetailSection.ECONOMY, economy != null)

    return listOf(
        UserKpi(
            id = "liga",
            label = "Liga",
            icon = AppIcon.RANKINGS,
            role = SemanticRole.COMMERCE,
            state = if (competitiveState == UserKpiState.DATA && league == null) UserKpiState.NO_DATA else competitiveState,
            value = league.orEmpty(),
        ),
        UserKpi(
            id = "ranking",
            label = "Ranking",
            icon = AppIcon.ANALYTICS,
            role = SemanticRole.INFO,
            state = if (competitiveState == UserKpiState.DATA && rank == null) UserKpiState.NO_DATA else competitiveState,
            value = rank?.let { "#${formatThousands(it)}" }.orEmpty(),
            caption = competitive?.leagueRank?.let { "En liga #$it" },
        ),
        UserKpi(
            id = "racha",
            label = "Racha",
            icon = AppIcon.PERFORMANCE,
            role = SemanticRole.BRAND,
            state = streakState,
            value = streak?.let { "${it.currentDays} días" }.orEmpty(),
            caption = streak?.let { "Mejor: ${it.bestDays} días" },
        ),
        UserKpi(
            id = "oposi-tickets",
            label = "OposiTickets",
            icon = AppIcon.TICKETS,
            role = SemanticRole.INFO,
            state = economyState,
            value = economy?.let { "${formatThousands(it.ticketBalance)} OT" }.orEmpty(),
        ),
        UserKpi(
            id = "suscripcion",
            label = "Suscripción",
            icon = AppIcon.PLANS,
            role = SemanticRole.INSIGHT,
            state = economyState,
            value = economy?.let { it.productCode ?: "gratuito" }.orEmpty(),
            caption = economy?.subscriptionStatus?.takeIf { it.isNotBlank() },
        ),
    ).map { kpi ->
        // Sólo una métrica con dato lleva valor: `NO_DISPONIBLE` no arrastra el de una fila
        // que existe pero cuya sección no se pudo leer.
        if (kpi.state == UserKpiState.DATA) kpi else kpi.copy(value = "", caption = null)
    }
}

private fun sectionState(detail: UserDetail, section: String, present: Boolean): UserKpiState = when {
    detail.isUnavailable(section) -> UserKpiState.UNAVAILABLE
    present -> UserKpiState.DATA
    else -> UserKpiState.NO_DATA
}

private fun kpiSkeleton(state: UserKpiState): List<UserKpi> = listOf(
    UserKpi("liga", "Liga", AppIcon.RANKINGS, SemanticRole.COMMERCE, state),
    UserKpi("ranking", "Ranking", AppIcon.ANALYTICS, SemanticRole.INFO, state),
    UserKpi("racha", "Racha", AppIcon.PERFORMANCE, SemanticRole.BRAND, state),
    UserKpi("oposi-tickets", "OposiTickets", AppIcon.TICKETS, SemanticRole.INFO, state),
    UserKpi("suscripcion", "Suscripción", AppIcon.PLANS, SemanticRole.INSIGHT, state),
)
