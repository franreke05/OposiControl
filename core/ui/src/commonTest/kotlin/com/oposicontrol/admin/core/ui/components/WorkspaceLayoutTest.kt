package com.oposicontrol.admin.core.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** El reparto del workspace compartido: mismas medidas que Contenido, en dp y píxeles exactos. */
class WorkspaceLayoutTest {
    @Test
    fun at1920TheThreeColumnsFitWithTheWideCatalogue() {
        // 1920 de ventana - 244 de sidebar - 2 x 32 de margen = 1612 de contenido.
        val layout = workspaceColumns(1612.dp, isWide = true, supportsMasterDetail = true)
        assertEquals(330.dp, layout.catalogWidth)
        assertEquals(300.dp, layout.railWidth)
        assertTrue(layout.showRail)
        assertFalse(layout.stacked)
        assertEquals(1612.dp - 330.dp - 300.dp - 20.dp * 2, layout.editorWidth)
    }

    @Test
    fun theRailFoldsBeforeTheEditorDropsBelowItsMinimum() {
        // Ancha, pero con el rail el editor no llegaría a 700: se pliega el rail, nunca el editor.
        val tight = workspaceColumns(1300.dp, isWide = true, supportsMasterDetail = true)
        assertFalse(tight.showRail)
        assertEquals(1300.dp - 330.dp - 20.dp, tight.editorWidth)

        val standard = workspaceColumns(1250.dp, isWide = false, supportsMasterDetail = true)
        assertEquals(310.dp, standard.catalogWidth)
        assertFalse(standard.showRail)
    }

    @Test
    fun withoutMasterDetailEverythingStacksAtFullWidth() {
        val layout = workspaceColumns(900.dp, isWide = false, supportsMasterDetail = false)
        assertTrue(layout.stacked)
        assertEquals(900.dp, layout.catalogWidth)
        assertEquals(900.dp, layout.editorWidth)
        assertFalse(layout.showRail)
    }

    @Test
    fun aScreenWithoutRailGivesItsWidthToTheEditor() {
        val layout = workspaceColumns(1612.dp, isWide = true, supportsMasterDetail = true, hasRail = false)
        assertFalse(layout.showRail)
        assertEquals(1612.dp - 330.dp - 20.dp, layout.editorWidth)
    }

    @Test
    fun equalCellsCoverTheWholeWidthToThePixel() {
        val cells = workspaceRowCells(totalWidth = 1001, gap = 12, count = 3)
        assertEquals(listOf(326, 326, 325), cells.map { it.width })
        assertEquals(0, cells.first().start)
        assertEquals(1001, cells.last().end)
        assertEquals(listOf(12, 12), cells.zipWithNext { a, b -> b.start - a.end })
        assertFailsWith<IllegalArgumentException> { workspaceRowCells(100, 8, 0) }
    }

    @Test
    fun theRangeTextSaysWhatIsShownOutOfWhat() {
        assertEquals("1–12 de 14 productos", workspaceRangeText(offset = 0, shown = 12, total = 14, noun = "productos"))
        assertEquals("13–14 de 14 productos", workspaceRangeText(offset = 12, shown = 2, total = 14, noun = "productos"))
        assertEquals("1–5 planes", workspaceRangeText(offset = 0, shown = 5, total = null, noun = "planes"))
        assertEquals("Sin productos", workspaceRangeText(offset = 0, shown = 0, total = 0, noun = "productos"))
    }
}
