package com.oposicontrol.admin.features.news.editor

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewsCanvasKeysTest {
    @Test
    fun arrowsNudgeOnePixelAndTenWithShift() {
        assertEquals(NewsCanvasKeyAction.Nudge(-1, 0), newsCanvasKeyAction(Key.DirectionLeft, shift = false, control = false))
        assertEquals(NewsCanvasKeyAction.Nudge(10, 0), newsCanvasKeyAction(Key.DirectionRight, shift = true, control = false))
        assertEquals(NewsCanvasKeyAction.Nudge(0, -10), newsCanvasKeyAction(Key.DirectionUp, shift = true, control = false))
        assertEquals(NewsCanvasKeyAction.Nudge(0, 1), newsCanvasKeyAction(Key.DirectionDown, shift = false, control = false))
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
