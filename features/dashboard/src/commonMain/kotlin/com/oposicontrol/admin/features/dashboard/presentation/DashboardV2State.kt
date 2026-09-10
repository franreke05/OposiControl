package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardOverview

/**
 * Estado del panel de escritorio: **un `UiAsync` por franja**, no uno global.
 *
 * El dashboard anterior tenía un único `UiAsync<DashboardMetrics>`, así que un
 * fallo al leer especialidades dejaba en blanco también el estado del sistema,
 * la actividad y la economía. Aquí cada franja se carga, falla y se reintenta
 * por su cuenta.
 *
 * [refreshing] es lo que separa «todavía no hay nada que enseñar» de «hay datos
 * y estoy trayendo otros más nuevos». Sin esa separación, el refresco automático
 * de la franja de sistema —cada 60 s— sustituiría la franja por un esqueleto
 * mientras alguien la está leyendo. Es la misma garantía que ya protegía
 * `DashboardState.isRefreshing` en el camino móvil, aquí por bloque.
 */
data class DashboardV2State(
    val blocks: Map<DashboardBlock, UiAsync<DashboardBlockReport>> = emptyMap(),
    val refreshing: Set<DashboardBlock> = emptySet(),
) {
    fun block(block: DashboardBlock): UiAsync<DashboardBlockReport> = blocks[block] ?: UiAsync.Idle

    fun isRefreshing(block: DashboardBlock): Boolean = block in refreshing

    /** Hay alguna recarga en curso sobre datos ya visibles. */
    val anyRefreshing: Boolean get() = refreshing.isNotEmpty()
}

/**
 * Marca las franjas pedidas como en vuelo.
 *
 * Una franja que ya tiene datos pasa a [DashboardV2State.refreshing] y **no** a
 * `Loading`: lo que se ve sigue viéndose. Una que no los tiene sí va a
 * `Loading`, porque ahí no hay nada que conservar.
 */
internal fun DashboardV2State.startLoading(requested: Set<DashboardBlock>): DashboardV2State {
    val (showing, blank) = requested.partition { block(it) is UiAsync.Success }
    val loading: Map<DashboardBlock, UiAsync<DashboardBlockReport>> =
        blank.associateWith { UiAsync.Loading }
    return copy(blocks = blocks + loading, refreshing = refreshing + showing)
}

/**
 * Falló la petición entera (red caída, sin sesión, sin permisos).
 *
 * Las franjas que ya tenían datos los conservan: unos datos de hace un minuto
 * son más útiles que una pantalla de error, y ésta es exactamente la garantía
 * «recargar no borra» que el panel anterior ya cumplía.
 */
internal fun DashboardV2State.applyCallFailure(
    requested: Set<DashboardBlock>,
    error: AppError,
): DashboardV2State {
    val next = requested.associateWith { candidate ->
        val current = block(candidate)
        if (current is UiAsync.Success) current else UiAsync.Error(error)
    }
    return copy(blocks = blocks + next, refreshing = refreshing - requested)
}

/**
 * Aplica una respuesta del contrato agregado.
 *
 * Tres casos, y los tres distintos a propósito:
 *
 *  - la franja viene en [DashboardOverview.failedBlocks] → `Error`. Ktor la pidió
 *    y no la pudo componer, y eso se dice; ocultarlo detrás de los datos viejos
 *    dejaría una cifra caducada en pantalla sin ninguna señal de que lo está.
 *  - la franja viene en [DashboardOverview.blocks] → `Success`, aunque no traiga
 *    ninguna métrica con número: saber qué falta es información.
 *  - se pidió y no llega ni en una lista ni en la otra → `Error`. Es una
 *    violación del contrato, y fallar en silencio dejaría la franja girando para
 *    siempre.
 *
 * Si la respuesta trae franjas que no se pidieron, se aprovechan igual: pedir
 * sólo SYSTEM y recibir seis no es un error, es información gratis.
 */
internal fun DashboardV2State.applyOverview(
    requested: Set<DashboardBlock>,
    overview: DashboardOverview,
): DashboardV2State {
    val touched = requested + overview.blocks.keys + overview.failedBlocks
    val next = touched.associateWith { candidate ->
        val report = overview.blocks[candidate]
        when {
            candidate in overview.failedBlocks -> UiAsync.Error(blockFailedError(candidate))
            report != null -> UiAsync.Success(report)
            candidate in requested -> UiAsync.Error(blockMissingError(candidate))
            else -> block(candidate)
        }
    }
    return copy(blocks = blocks + next, refreshing = refreshing - touched)
}

/** `Unknown` y no `Network`: la petición fue bien, lo que falló fue componer la franja. */
internal fun blockFailedError(block: DashboardBlock): AppError =
    AppError.Unknown("El servidor no pudo componer la franja «${block.wire}».")

internal fun blockMissingError(block: DashboardBlock): AppError =
    AppError.Unknown("El servidor no devolvió la franja «${block.wire}» que se le pidió.")
