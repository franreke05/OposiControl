package com.oposicontrol.admin.features.news.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewsCanvasGeometryTest {
    private val w = 800
    private val h = 500

    @Test
    fun clampKeepsTheOriginAndShrinksWhatOverflows() {
        assertEquals(NewsFrame(0, 0, 800, 500), clampFrame(NewsFrame(-30, -40, 900, 700), w, h))
        assertEquals(NewsFrame(700, 420, 100, 80), clampFrame(NewsFrame(700, 420, 240, 160), w, h))
        assertEquals(NewsFrame(776, 476, 24, 24), clampFrame(NewsFrame(5000, 5000, 10, 10), w, h), "nunca por debajo del lado minimo")
    }

    @Test
    fun nudgeStopsAtTheEdges() {
        val frame = NewsFrame(790, 10, 100, 40)
        assertEquals(NewsFrame(700, 10, 100, 40), nudgeFrame(frame, 50, 0, w, h))
        assertEquals(NewsFrame(0, 0, 100, 40), nudgeFrame(NewsFrame(3, 3, 100, 40), -10, -10, w, h))
        assertEquals(NewsFrame(13, 14, 100, 40), nudgeFrame(NewsFrame(3, 4, 100, 40), 10, 10, w, h))
    }

    @Test
    fun alignmentsLandExactly() {
        val frame = NewsFrame(10, 10, 200, 100)
        assertEquals(0, alignFrame(frame, NewsAlignment.LEFT, w, h).x)
        assertEquals(300, alignFrame(frame, NewsAlignment.CENTER_H, w, h).x)
        assertEquals(600, alignFrame(frame, NewsAlignment.RIGHT, w, h).x)
        assertEquals(0, alignFrame(frame, NewsAlignment.TOP, w, h).y)
        assertEquals(200, alignFrame(frame, NewsAlignment.CENTER_V, w, h).y)
        assertEquals(400, alignFrame(frame, NewsAlignment.BOTTOM, w, h).y)
    }

    @Test
    fun resizingFromACornerPinsTheOppositeOne() {
        val start = NewsFrame(100, 100, 200, 100)
        assertEquals(NewsFrame(100, 100, 250, 130), resizeFrame(start, NewsResizeCorner.BOTTOM_END, 50f, 30f, w, h))
        assertEquals(NewsFrame(80, 90, 220, 110), resizeFrame(start, NewsResizeCorner.TOP_START, -20f, -10f, w, h))
        // Encoger por debajo del minimo deja el lado en 24 y la esquina opuesta donde estaba.
        assertEquals(NewsFrame(276, 100, 24, 100), resizeFrame(start, NewsResizeCorner.TOP_START, 500f, 0f, w, h))
        // Crecer mas alla del lienzo se recorta al borde.
        assertEquals(NewsFrame(100, 100, 700, 400), resizeFrame(start, NewsResizeCorner.BOTTOM_END, 5000f, 5000f, w, h))
    }

    @Test
    fun snapPullsEdgesAndCentresWithinTheThreshold() {
        // Borde izquierdo a 4 px del borde del lienzo: se imanta a 0 y se pinta la guia vertical en 0.
        val left = snapFrame(NewsFrame(4, 100, 100, 50), w, h)
        assertEquals(0, left.frame.x)
        assertEquals(listOf(NewsGuide(vertical = true, position = 0)), left.guides)

        // Una capa ya alineada tambien ensena su guia: el borde inferior en el centro del lienzo (250).
        val aligned = snapFrame(NewsFrame(100, 200, 100, 50), w, h)
        assertEquals(NewsFrame(100, 200, 100, 50), aligned.frame)
        assertEquals(listOf(NewsGuide(vertical = false, position = 250)), aligned.guides)

        // Centro vertical a 3 px del centro del lienzo (250): y pasa a 225.
        val centre = snapFrame(NewsFrame(300, 228, 100, 50), w, h)
        assertEquals(225, centre.frame.y)
        assertTrue(centre.guides.any { !it.vertical && it.position == 250 })

        // A 20 px de cualquier guia no pasa nada y no hay guias.
        val free = snapFrame(NewsFrame(60, 60, 100, 50), w, h)
        assertEquals(NewsFrame(60, 60, 100, 50), free.frame)
        assertTrue(free.guides.isEmpty())
    }

    @Test
    fun snapAlsoUsesTheOtherLayersEdges() {
        val other = NewsFrame(300, 100, 200, 60)
        // Borde derecho de la capa (305) a 5 px del borde izquierdo de la otra (300).
        val snapped = snapFrame(NewsFrame(205, 300, 100, 50), w, h, others = listOf(other))
        assertEquals(200, snapped.frame.x)
        assertEquals(NewsGuide(vertical = true, position = 300), snapped.guides.single())
    }

    @Test
    fun movingPastTheEdgePushesBackAndNeverShrinks() {
        // Arrastre mas alla del borde derecho e inferior: la capa se detiene en el borde con su tamano intacto.
        val snapped = snapFrame(NewsFrame(797, 497, 100, 50), w, h)
        assertEquals(NewsFrame(700, 450, 100, 50), snapped.frame)
        assertEquals(NewsFrame(700, 450, 100, 50), fitFrame(NewsFrame(797, 497, 100, 50), w, h))
        // Solo una capa MAYOR que el lienzo se recorta, y al tamano del lienzo.
        assertEquals(NewsFrame(0, 0, 800, 500), fitFrame(NewsFrame(10, 10, 900, 600), w, h))
    }
}
