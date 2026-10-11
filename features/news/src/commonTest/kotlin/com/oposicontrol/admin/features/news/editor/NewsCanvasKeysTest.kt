package com.oposicontrol.admin.features.news.editor

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewsCanvasKeysTest {
    @Test
    fun arrowsNudgeOnePhoneDpAndTenWithShift() {
        // El lienzo va a 4 px por dp de movil: una flecha mueve lo minimo que la app puede distinguir.
        assertEquals(4, NEWS_NUDGE_STEP)
        assertEquals(40, NEWS_NUDGE_STEP_LARGE)
        assertEquals(NewsCanvasKeyAction.Nudge(-4, 0), newsCanvasKeyAction(Key.DirectionLeft, shift = false, control = false))
        assertEquals(NewsCanvasKeyAction.Nudge(40, 0), newsCanvasKeyAction(Key.DirectionRight, shift = true, control = false))
        assertEquals(NewsCanvasKeyAction.Nudge(0, -40), newsCanvasKeyAction(Key.DirectionUp, shift = true, control = false))
        assertEquals(NewsCanvasKeyAction.Nudge(0, 4), newsCanvasKeyAction(Key.DirectionDown, shift = false, control = false))
    }

    @Test
    fun theCanvasIsTheOposiBotHomeBannerAtFourPixelsPerDp() {
        // 358 x 148 dp es el banner de Inicio de OposiBot en movil; 704 dp de ancho en tableta, misma proporcion.
        assertEquals(1432, NEWS_CANVAS_DEFAULT_WIDTH)
        assertEquals(592, NEWS_CANVAS_DEFAULT_HEIGHT)
        assertEquals(NEWS_BANNER_PHONE_WIDTH_DP * NEWS_CANVAS_SCALE, NEWS_CANVAS_DEFAULT_WIDTH)
        assertEquals(NEWS_BANNER_PHONE_HEIGHT_DP * NEWS_CANVAS_SCALE, NEWS_CANVAS_DEFAULT_HEIGHT)
        // El editor movil (features/exams) trata 800 x 500 como minimo: el lienzo tiene que superarlo en los dos ejes.
        kotlin.test.assertTrue(NEWS_CANVAS_DEFAULT_WIDTH >= 800 && NEWS_CANVAS_DEFAULT_HEIGHT >= 500)
        // Letra derivada del tipo de capa: 18 dp el titulo y 12 dp el resto, en px del lienzo.
        assertEquals(72, NEWS_DERIVED_TITLE_SIZE)
        assertEquals(48, NEWS_DERIVED_TEXT_SIZE)
    }

    @Test
    fun editingShortcutsMatchTheDesktopConvention() {
        assertEquals(NewsCanvasKeyAction.Undo, newsCanvasKeyAction(Key.Z, shift = false, control = true))
        assertEquals(NewsCanvasKeyAction.Redo, newsCanvasKeyAction(Key.Z, shift = true, control = true))
        assertEquals(NewsCanvasKeyAction.Redo, newsCanvasKeyAction(Key.Y, shift = false, control = true))
        assertEquals(NewsCanvasKeyAction.Save, newsCanvasKeyAction(Key.S, shift = false, control = true))
        assertEquals(NewsCanvasKeyAction.Duplicate, newsCanvasKeyAction(Key.D, shift = false, control = true))
        assertEquals(NewsCanvasKeyAction.DeleteLayer, newsCanvasKeyAction(Key.Delete, shift = false, control = false))
        assertEquals(NewsCanvasKeyAction.DeleteLayer, newsCanvasKeyAction(Key.Backspace, shift = false, control = false))
        assertEquals(NewsCanvasKeyAction.Deselect, newsCanvasKeyAction(Key.Escape, shift = false, control = false))
        assertEquals(NewsCanvasKeyAction.NextLayer, newsCanvasKeyAction(Key.Tab, shift = false, control = false))
        assertEquals(NewsCanvasKeyAction.PreviousLayer, newsCanvasKeyAction(Key.Tab, shift = true, control = false))
    }

    @Test
    fun unknownKeysAreNotConsumed() {
        assertNull(newsCanvasKeyAction(Key.A, shift = false, control = false))
        assertNull(newsCanvasKeyAction(Key.A, shift = false, control = true), "Ctrl+A no es nuestro: sigue su camino")
        assertNull(newsCanvasKeyAction(Key.Enter, shift = false, control = false))
    }
}
