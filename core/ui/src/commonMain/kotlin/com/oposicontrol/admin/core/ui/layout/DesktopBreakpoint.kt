package com.oposicontrol.admin.core.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Regímenes de ventana DENTRO del escritorio.
 *
 * [AppBreakpoint] decide otra cosa — si la app se dibuja como móvil o como
 * escritorio — y por eso se queda como está: cambiarlo movería la barra inferior
 * del móvil. Éste subdivide lo que aquel colapsa en un único `EXPANDED`, que hoy
 * cubre desde 1040dp hasta 2560dp con exactamente el mismo layout.
 *
 * No son tres aplicaciones distintas: son tres densidades de la misma.
 */
enum class DesktopBreakpoint {
    /** Portátil pequeño y ventanas reducidas a mano. Sidebar colapsada, columnas secundarias ocultas. */
    COMPACT,

    /** 1366×768 · 1440×900 · 1600×900. El caso normal. */
    STANDARD,

    /** 1920×1080 y superiores. Master-detail, tablas completas, más KPIs por fila. */
    WIDE,
    ;

    val isWide: Boolean get() = this == WIDE
    val isCompact: Boolean get() = this == COMPACT
    val isAtLeastStandard: Boolean get() = this != COMPACT
}

fun desktopBreakpointFor(width: Dp): DesktopBreakpoint = when {
    width < 1100.dp -> DesktopBreakpoint.COMPACT
    width < 1500.dp -> DesktopBreakpoint.STANDARD
    else -> DesktopBreakpoint.WIDE
}

/** Margen exterior del contenido para cada régimen. */
val DesktopBreakpoint.screenPadding: Dp
    get() = when (this) {
        DesktopBreakpoint.COMPACT -> Spacing.screenCompact
        DesktopBreakpoint.STANDARD -> Spacing.screenStandard
        DesktopBreakpoint.WIDE -> Spacing.screenWide
    }

/**
 * Cuántas tarjetas de KPI caben por fila sin que el número quede flotando en un
 * campo de vacío ni las tarjetas se aplasten.
 */
val DesktopBreakpoint.kpiColumns: Int
    get() = when (this) {
        DesktopBreakpoint.COMPACT -> 2
        DesktopBreakpoint.STANDARD -> 4
        DesktopBreakpoint.WIDE -> 5
    }

/** Si el panel de detalle cabe junto a la lista o tiene que apilarse debajo. */
val DesktopBreakpoint.supportsMasterDetail: Boolean
    get() = this != DesktopBreakpoint.COMPACT

val LocalDesktopBreakpoint = staticCompositionLocalOf { DesktopBreakpoint.STANDARD }

object DesktopWindow {
    val breakpoint: DesktopBreakpoint
        @Composable get() = LocalDesktopBreakpoint.current
}
