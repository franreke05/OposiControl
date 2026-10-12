package com.oposicontrol.admin.core.ui.theme

import com.oposicontrol.admin.core.ui.components.WORKSPACE_COLOR_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La escala de movimiento es un contrato: cada duracion cae en el rango de su categoria y ninguna hace esperar.
 * Si alguien «ajusta» un numero y lo saca de su rango, esto lo dice antes que una revision visual.
 */
class OposiMotionTest {

    @Test
    fun everyDurationStaysInTheRangeOfItsCategory() {
        assertTrue(OposiMotion.Instant in 60..110, "respuesta a una pulsacion")
        assertTrue(OposiMotion.Fast in 120..150, "color de hover y seleccion")
        assertTrue(OposiMotion.Normal in 160..220, "cambio de estado de un control")
        assertTrue(OposiMotion.Emphasis in 200..280, "entrada de pagina y de contenido")
        assertTrue(OposiMotion.Overlay in 220..320, "dialogos")
    }

    @Test
    fun theScaleIsOrderedAndNothingMakesTheUserWait() {
        val scale = listOf(OposiMotion.Instant, OposiMotion.Fast, OposiMotion.Normal, OposiMotion.Emphasis, OposiMotion.Overlay)
        assertEquals(scale.sorted(), scale)
        // Una pagina entera (cabecera y, desfasado, el contenido) esta en pantalla antes de lo que tarda un dialogo.
        assertTrue(OposiMotion.Emphasis + OposiMotion.Stagger <= OposiMotion.Overlay)
        assertTrue(OposiMotion.Stagger <= 60, "el desfase es un matiz, no una cola")
        assertTrue(OposiMotion.Highlight <= 1_000, "el resalte se apaga en menos de un segundo")
    }

    @Test
    fun theEntranceIsSubtle() {
        assertTrue(OposiMotion.EnterOffset.value in 4f..12f, "se nota que llega, no que se mueve")
        assertTrue(OposiMotion.OverlayStartScale in 0.94f..0.99f)
    }

    @Test
    fun theWorkspaceConstantIsTheToken() {
        // Las pantallas de Contenido escriben 140 a mano; el workspace y el token tienen que decir lo mismo.
        assertEquals(140, OposiMotion.Fast)
        assertEquals(OposiMotion.Fast, WORKSPACE_COLOR_MILLIS)
    }
}
