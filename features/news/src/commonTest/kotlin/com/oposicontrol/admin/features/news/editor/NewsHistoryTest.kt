package com.oposicontrol.admin.features.news.editor

import com.oposicontrol.admin.features.news.presentation.NewsLayerDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsHistoryTest {
    private val base = NewsDocument(title = "A")
    private fun layer(x: Int) = NewsLayerDraft("l", "Capa", "texto", "t", x, 0, 100, 40, 100, visible = true, sortOrder = 0)

    @Test
    fun undoRestoresThePreviousDocumentAndRedoBringsItBack() {
        val edited = base.copy(title = "B")
        val history = NewsHistory().record(base, NewsEditGroup("title"))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val (afterUndo, restored) = assertNotNull(history.undo(edited))
        assertEquals(base, restored)
        assertTrue(afterUndo.canRedo)
        assertFalse(afterUndo.canUndo)

        val (afterRedo, redone) = assertNotNull(afterUndo.redo(restored))
        assertEquals(edited, redone)
        assertTrue(afterRedo.canUndo)
        assertFalse(afterRedo.canRedo)
    }

    @Test
    fun consecutiveEditsOfTheSameGroupCollapseIntoOneStep() {
        // Veinte pulsaciones del titulo: un solo paso cuyo «antes» es el documento previo a la primera.
        var history = NewsHistory()
        var document = base
        repeat(20) { index ->
            history = history.record(document, NewsEditGroup("title"))
            document = document.copy(title = "A" + "b".repeat(index + 1))
        }
        assertEquals(1, history.past.size)
        val (_, restored) = assertNotNull(history.undo(document))
        assertEquals(base, restored)
    }

    @Test
    fun aDifferentGroupOrTargetOpensANewStep() {
        val history = NewsHistory()
            .record(base, NewsEditGroup("frame", "l1"))
            .record(base.copy(layers = listOf(layer(10))), NewsEditGroup("frame", "l2"))
            .record(base.copy(layers = listOf(layer(20))), NewsEditGroup("title"))
        assertEquals(3, history.past.size)
    }

    @Test
    fun closingTheGroupMakesTheNextEditOfTheSameKindANewStep() {
        val history = NewsHistory()
            .record(base, NewsEditGroup("gesture", "l1"))
            .closeGroup()
            .record(base.copy(layers = listOf(layer(5))), NewsEditGroup("gesture", "l1"))
        assertEquals(2, history.past.size)
    }

    @Test
    fun aNewEditClearsTheRedoStack() {
        val history = NewsHistory().record(base, NewsEditGroup("title"))
        val (afterUndo, _) = assertNotNull(history.undo(base.copy(title = "B")))
        assertTrue(afterUndo.canRedo)
        val afterEdit = afterUndo.record(base, NewsEditGroup("text"))
        assertFalse(afterEdit.canRedo)
        assertNull(afterEdit.redo(base))
    }

    @Test
    fun theHistoryIsBounded() {
        var history = NewsHistory(limit = 5)
        repeat(12) { index -> history = history.record(base.copy(title = "$index"), NewsEditGroup("step$index")) }
        assertEquals(5, history.past.size)
        assertEquals("7", history.past.first().document.title, "se conservan los ultimos cinco, no los primeros")
    }

    @Test
    fun undoOnAnEmptyHistoryIsNull() {
        assertNull(NewsHistory().undo(base))
        assertNull(NewsHistory().redo(base))
    }
}
