package com.oposicontrol.admin.features.rankings.presentation

import com.oposicontrol.admin.domain.model.LeagueLevel
import kotlin.math.roundToInt

/**
 * Formateo de la pantalla de rankings.
 *
 * Vive aparte del Composable para poder probarlo: aquí es donde un signo perdido
 * o un cero inventado se convertirían en una lectura falsa del rendimiento de un
 * opositor.
 */

/** Marca de hueco: la fila existe pero ese dato todavía no se ha calculado. */
const val NO_DATA_TEXT = "NO_DATA"

/** Marca de fallo: no se pudo leer. Nunca se confunde con [NO_DATA_TEXT]. */
const val NOT_AVAILABLE_TEXT = "NO_DISPONIBLE"

/**
 * Aviso obligatorio junto al percentil.
 *
 * `percentil_global` compara a un usuario con el resto de usuarios de la app.
 * Presentarlo sin esta frase invita a leerlo como una probabilidad de sacar
 * plaza, que es una afirmación que el producto no puede sostener.
 */
const val PERCENTILE_DISCLAIMER =
    "Indicador comparativo interno frente a otros usuarios de la app. " +
        "No es una probabilidad oficial de obtener plaza."

/** Nota sobre 10 con un decimal, como se le presenta al usuario final. */
fun formatScore(score: Double): String {
    val tenths = (score * 10).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    val absolute = if (tenths < 0) -tenths else tenths
    return "$sign${absolute / 10},${absolute % 10}"
}

/**
 * Evolución con signo explícito.
 *
 * "+0,8" y "0,8" se leen igual de un vistazo, y una bajada sin signo se leería
 * como una subida. El signo va siempre, incluso en el cero: "±0,0" dice
 * "medido y plano", que no es lo mismo que "sin medir".
 */
fun formatSignedScore(delta: Double): String {
    val tenths = (delta * 10).roundToInt()
    val sign = when {
        tenths > 0 -> "+"
        tenths < 0 -> "-"
        else -> "±"
    }
    val absolute = if (tenths < 0) -tenths else tenths
    return "$sign${absolute / 10},${absolute % 10}"
}

/** Nota, o marca de hueco si todavía no hay ninguna. */
fun formatScoreOrNoData(score: Double?): String = score?.let(::formatScore) ?: NO_DATA_TEXT

/** Evolución con signo, o marca de hueco si aún no se ha calculado ninguna. */
fun formatSignedScoreOrNoData(delta: Double?): String = delta?.let(::formatSignedScore) ?: NO_DATA_TEXT

/** Posición en formato ordinal corto. Un hueco nunca se pinta como "0.º". */
fun formatPosition(position: Int?): String = position?.let { "$it.º" } ?: NO_DATA_TEXT

/**
 * Percentil con su unidad. Sólo es válido en 0..100: cualquier otra cosa es un
 * dato corrupto y se marca como hueco en lugar de pintarse.
 */
fun formatPercentile(percentile: Double?): String =
    percentile?.takeIf { it in 0.0..100.0 }?.let { "${it.roundToInt()}%" } ?: NO_DATA_TEXT

/** Ventana de cálculo en palabras: el número solo no dice qué mide. */
fun formatWindow(window: Int?): String = window?.let { "últimas $it preguntas" } ?: NO_DATA_TEXT

fun formatCount(count: Int?): String = count?.toString() ?: NO_DATA_TEXT

/**
 * Total de una página.
 *
 * Tres lecturas distintas y ninguna se puede confundir con otra: un total
 * conocido, un cero confirmado y un total ilegible.
 */
fun formatTotal(total: Int?): String = when (total) {
    null -> NOT_AVAILABLE_TEXT
    0 -> "0"
    else -> total.toString()
}

/**
 * Aviso de divergencia de una liga.
 *
 * Devuelve null cuando la ventana coincide con la regla: no hay nada que avisar
 * y una tarjeta de aviso permanente se acabaría ignorando.
 */
fun leagueWindowWarning(league: LeagueLevel): String? {
    if (!league.windowDivergesFromRule) return null
    return "${league.name}: la base guarda una ventana de ${league.calculationWindow} preguntas, " +
        "pero la regla de producto para ${league.code} es de ${league.expectedCalculationWindow}. " +
        "Las notas medias de esta liga no son comparables con las del resto."
}

/** Etiqueta de la fila: nombre, username o el id como último recurso legible. */
fun rowLabel(displayName: String?, username: String?, userId: String): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: username?.takeIf { it.isNotBlank() }
        ?: userId
