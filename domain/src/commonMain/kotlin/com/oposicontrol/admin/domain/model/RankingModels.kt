package com.oposicontrol.admin.domain.model

/**
 * Rankings y ligas.
 *
 * La clasificación la calcula el producto a partir de la actividad real del
 * usuario; el panel la lee y la explica. Por eso en este fichero no hay ningún
 * modelo de "edición": no existe un caso de uso legítimo en el que un
 * administrador corrija a mano los puntos o la posición de alguien.
 *
 * Todo lo numérico es nullable a propósito. Un usuario recién entrado en liga
 * puede no tener todavía posición global calculada, y un `0` en ese hueco se
 * leería como "va el primero" — exactamente lo contrario de lo que ocurre.
 */

/**
 * Nivel de liga (`ligas_nivel`).
 *
 * [minimumQuestions] es el umbral de entrada y [calculationWindow] cuántas
 * preguntas entran en la nota media móvil de esa liga. Los dos vienen de la base
 * de datos: [expectedCalculationWindow] es sólo la regla de producto con la que
 * contrastarlos.
 */
data class LeagueLevel(
    val id: String,
    val code: String,
    val name: String,
    val minimumQuestions: Int,
    val calculationWindow: Int,
    val active: Boolean = true,
    val position: Int = 0,
) {
    /**
     * Ventana que la regla de producto asigna a este código: la liga de entrada
     * promedia las últimas 50 preguntas y el resto las últimas 100. `null` para
     * un código desconocido — una liga nueva no tiene regla escrita y no se le
     * inventa una.
     */
    val expectedCalculationWindow: Int?
        get() = when (code.trim().lowercase()) {
            "liga_50" -> 50
            "liga_200", "liga_500", "liga_800", "liga_1000" -> 100
            else -> null
        }

    /**
     * La ventana almacenada contradice la regla de producto. Importa porque dos
     * ligas con ventanas distintas de las previstas producen notas medias que ya
     * no son comparables entre sí, y la tabla las presenta como si lo fueran.
     */
    val windowDivergesFromRule: Boolean
        get() = expectedCalculationWindow?.let { it != calculationWindow } ?: false
}

/**
 * Una fila de clasificación.
 *
 * [globalPercentile] es un indicador comparativo **interno** de la app: dice
 * dónde queda este usuario frente al resto de usuarios de OposiBot. No es una
 * probabilidad de obtener plaza y la UI está obligada a etiquetarlo como tal.
 */
data class RankingEntry(
    val userId: String,
    val displayName: String? = null,
    val username: String? = null,
    val leagueLevelId: String? = null,
    val leagueCode: String? = null,
    val leagueName: String? = null,
    val calculationWindow: Int? = null,
    val rankingEligibleQuestions: Int? = null,
    val currentAverage: Double? = null,
    val evolutionReference: Double? = null,
    val evolution: Double? = null,
    val questionsSinceLastEvolution: Int? = null,
    val leagueRank: Int? = null,
    val globalRank: Int? = null,
    val globalPercentile: Double? = null,
    val updatedAt: String? = null,
) {
    /** Nombre con el que mostrar la fila, o null si no se pudo resolver ninguno. */
    val label: String?
        get() = displayName?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() }

    /**
     * El usuario aparece en la lista pero todavía no tiene estado competitivo
     * calculado. Ocurre en la búsqueda: encontrar a alguien que aún no compite es
     * un resultado legítimo, no un error.
     */
    val hasNoCompetitiveState: Boolean
        get() = leagueLevelId == null && currentAverage == null && globalRank == null
}

/** Qué significa realmente una página vacía. */
enum class RankingPageState {
    /** Hay filas reales. */
    CONTENT,

    /** Cero filas y conteo exacto confirmado: la tabla está realmente vacía. */
    EMPTY,

    /** Cero filas pero sin conteo fiable: no se puede afirmar que esté vacía. */
    UNAVAILABLE,
}

/**
 * Página de clasificación.
 *
 * [total] es nullable a propósito: si el conteo exacto no se pudo obtener se
 * dice que no se sabe, en lugar de mandar un 0 que la pantalla pintaría como
 * "no compite nadie". Con la tabla aprovisionada y vacía —que es el estado real
 * hoy— `total = 0` es un cero legítimo y debe distinguirse de `null`.
 */
data class RankingPage(
    val entries: List<RankingEntry> = emptyList(),
    val total: Int? = null,
    val limit: Int = RankingPageRequest.DEFAULT_LIMIT,
    val offset: Int = 0,
    val unavailableSections: Set<String> = emptySet(),
) {
    val state: RankingPageState
        get() = when {
            entries.isNotEmpty() -> RankingPageState.CONTENT
            total == null || RankingSection.TOTAL in unavailableSections -> RankingPageState.UNAVAILABLE
            else -> RankingPageState.EMPTY
        }

    fun isUnavailable(section: String): Boolean = section in unavailableSections

    /** Hay una página siguiente sólo si el total es conocido y quedan filas detrás. */
    val hasNextPage: Boolean
        get() = total?.let { offset + entries.size < it } ?: false

    val hasPreviousPage: Boolean get() = offset > 0

    /** Número de página (1-based) para etiquetar la paginación sin mentir sobre el total. */
    val pageNumber: Int get() = if (limit <= 0) 1 else (offset / limit) + 1
}

/**
 * Posición competitiva de un usuario.
 *
 * `competitive == null` significa que **todavía no compite**: la fila se crea al
 * alcanzar el mínimo de preguntas de la primera liga. Es un estado normal y se
 * distingue de "no se pudo leer" mediante [unavailableSections].
 */
data class UserStanding(
    val userId: String,
    val displayName: String? = null,
    val username: String? = null,
    val competitive: RankingEntry? = null,
    val league: LeagueLevel? = null,
    val unavailableSections: Set<String> = emptySet(),
) {
    fun isUnavailable(section: String): Boolean = section in unavailableSections

    val label: String?
        get() = displayName?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() }
}

/**
 * Claves de sección para los huecos honestos. Los literales deben coincidir con
 * los que envía el backend (`AdminRankingsSection`): si divergen, la pantalla
 * marcaría la sección equivocada.
 */
object RankingSection {
    const val TOTAL = "total"
    const val NOMBRES = "nombres"
    const val CATALOGO_LIGAS = "catalogoLigas"
    const val COMPETITIVO = "competitivo"
    const val PERFIL = "perfil"
}

/**
 * Paginación saneada del lado del cliente.
 *
 * El backend ya recorta, pero el cliente no debe llegar a pedir lo imposible: un
 * `offset` negativo o un `limit` desmedido serían un error del panel, no del
 * usuario, y se corrigen antes de salir a la red.
 *
 * **No es `data class` a propósito.** El `copy()` generado sería público aunque
 * el constructor sea privado, y `of(50, 0).copy(limit = 999_999)` saltaría el
 * tope duro sin pasar por [Companion.of] — es decir, justo el agujero que esta
 * clase existe para tapar. Sin `copy()`, el único camino es el saneado.
 */
class RankingPageRequest private constructor(
    val limit: Int,
    val offset: Int,
) {
    fun next(): RankingPageRequest = of(limit, offset + limit)

    fun previous(): RankingPageRequest = of(limit, offset - limit)

    override fun equals(other: Any?): Boolean =
        this === other || (other is RankingPageRequest && limit == other.limit && offset == other.offset)

    override fun hashCode(): Int = limit * 31 + offset

    override fun toString(): String = "RankingPageRequest(limit=$limit, offset=$offset)"

    companion object {
        const val DEFAULT_LIMIT = 50

        /** Mismo tope duro que el backend: una página no puede ser una descarga. */
        const val MAX_LIMIT = 200

        fun of(limit: Int? = null, offset: Int? = null): RankingPageRequest = RankingPageRequest(
            limit = when {
                limit == null || limit <= 0 -> DEFAULT_LIMIT
                limit > MAX_LIMIT -> MAX_LIMIT
                else -> limit
            },
            offset = offset?.takeIf { it > 0 } ?: 0,
        )
    }
}
