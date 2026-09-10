package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Escala de espaciado del panel de escritorio.
 *
 * Un backoffice se lee por su ritmo vertical: si cada pantalla elige su propio
 * hueco (hoy conviven 18/20/22/24/28/30/34/42 dp) el ojo no llega a aprender la
 * estructura y todo parece improvisado aunque cada pantalla, por separado, esté
 * bien. Estos son los ÚNICOS valores permitidos en superficie nueva.
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val huge = 48.dp

    /** Margen exterior del contenido, por régimen de ventana. */
    val screenCompact = 20.dp
    val screenStandard = 24.dp
    val screenWide = 32.dp

    /** Separación entre secciones de una misma pantalla. */
    val section = 24.dp

    /** Separación interna de una card. */
    val card = 16.dp

    /** Separación entre campos de un formulario. */
    val field = 14.dp
}

object Radius {
    val sm: Shape = RoundedCornerShape(8.dp)
    val md: Shape = RoundedCornerShape(10.dp)

    /**
     * Radio de tarjeta de dato: KPI, tarjeta de atención, bloque de lista.
     *
     * Existe porque el 12dp ya estaba escrito a mano en tres sitios —`SummaryCard`,
     * `GlassSurface` en su rama de escritorio y `ErrorStateCard`— y la escala
     * saltaba de 10 a 14, así que cualquier superficie nueva tenía que elegir
     * entre desalinearse 2dp de las tarjetas del propio sistema o copiar el
     * número una cuarta vez.
     *
     * Es puramente aditivo: no cambia [md] ni [lg], y los tres sitios que ya
     * llevan el 12dp a mano se alinean en un paso posterior.
     */
    val card: Shape = RoundedCornerShape(12.dp)
    val lg: Shape = RoundedCornerShape(14.dp)
    val xl: Shape = RoundedCornerShape(18.dp)
    val pill: Shape = RoundedCornerShape(999.dp)
}

/**
 * Alturas y anchos canónicos.
 *
 * Escritorio, no móvil: un botón de 50dp y un input de 54dp son medidas pensadas
 * para el dedo, y en un panel que se maneja con ratón sólo consiguen que quepan
 * menos datos por pantalla sin ganar nada a cambio.
 */
object Sizing {
    val buttonCompact = 32.dp
    val buttonNormal = 40.dp
    val buttonLarge = 44.dp

    val iconButton = 36.dp
    val iconButtonSmall = 28.dp
    val icon = 18.dp
    val iconLarge = 20.dp

    val inputHeight = 40.dp
    val inputTextMaxWidth = 420.dp
    val inputDescriptionMaxWidth = 680.dp

    val tableHeaderHeight = 40.dp
    val tableRowHeight = 48.dp

    val sidebarExpanded = 244.dp
    val sidebarCollapsed = 68.dp
    val headerHeight = 60.dp

    val tooltipMaxWidth = 300.dp
    val popoverWidth = 360.dp
    val helpPanelWidth = 380.dp

    /** Ancho máximo de una columna de lectura (login, diálogos, formularios centrados). */
    val formColumnMaxWidth = 440.dp
}

object Elevation {
    val flat = 0.dp
    val card = 6.dp
    val raised = 10.dp
    val overlay = 18.dp
}
