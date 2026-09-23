package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import kotlin.test.*

class NewsPresentationTest {
    @Test fun unknownStateNeverClaimsPublished() { assertEquals("Sin identificar", newsStatusLabel("active")) }
    @Test fun catalogFailureNeverLeaksIdOrSelectsDefault() {
        assertEquals("No disponible", catalogName("123", UiAsync.Error(AppError.Validation("failure"))))
        assertEquals("Sin identificar", catalogName("123", UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE")))))
    }
    @Test fun catalogDisplaysHumanName() { assertEquals("TCAE", catalogName("1", UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE"))))) }
    @Test fun scheduleRejectsImpossibleDate() { assertNull(newsScheduleIso("31/02/2026", "09:00")); assertNull(newsScheduleIso("12/09/2026", "25:00")) }
    @Test fun scheduleConvertsHumanInputToUtc() { assertEquals("2026-09-12T09:30:00Z", newsScheduleIso("12/09/2026", "09:30")) }
    @Test fun panesFitAndLeaveReadableEditor() {
        listOf(1920, 1600, 1366, 1080).forEach { window ->
            val width = window - 292
            val sizes = newsPaneSizes(width)
            val columns = listOf(sizes.library, sizes.editor, sizes.inspector, sizes.rail).filter { it > 0 }
            // Las columnas más los huecos que las separan tienen que sumar el
            // ancho exacto: si sobra, queda una franja muerta; si falta, la
            // última columna se sale de la ventana.
            val gaps = (columns.size - 1).coerceAtLeast(0) * 16
            assertEquals(width, columns.sum() + gaps, "Las columnas no cuadran a $window")
            // El editor es el protagonista: por debajo de esto el lienzo deja de
            // poder mirarse mientras se trabaja.
            assertTrue(sizes.editor >= 500, "Editor ilegible a $window: ${sizes.editor}")
        }
    }

    @Test fun theInspectorOnlyAppearsWhenThereIsRoomForIt() {
        // Estrujar el inspector no lo hace utilizable: desaparece y sus
        // propiedades caen dentro del editor, que sí puede desplazarse.
        assertEquals(0, newsPaneSizes(900).inspector)
        assertEquals(0, newsPaneSizes(1100).inspector)
        assertTrue(newsPaneSizes(1628).inspector > 0)
        // La publicación es la primera en plegarse: es contexto, no formulario.
        assertEquals(0, newsPaneSizes(1628).rail)
        assertTrue(newsPaneSizes(1900).rail > 0)
    }
    @Test fun localFileNameDoesNotExposeDirectories() { assertEquals("portada.png", newsAssetName("file://C:/Users/private/portada.png")) }
    @Test fun editsDoNotClaimSaved() { assertEquals("Cambios sin guardar", NewsState(isDirty = true, actionState = UiAsync.Success(Unit)).saveLabel()) }
}
