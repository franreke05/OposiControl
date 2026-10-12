package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.theme.LocalMotionEnabled
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El movimiento compartido, medido en píxeles y con el reloj de test parado: cada prueba avanza el tiempo a mano y
 * mira qué hay pintado. Lo que se fija aquí es lo que el resto del panel da por hecho —que una entrada empieza
 * invisible y termina opaca, que no se repite al recargar datos, que un resalte no se dispara al nacer la fila y que
 * todo se apaga con [LocalMotionEnabled]—.
 */
@OptIn(ExperimentalTestApi::class)
class MotionTest {

    // ---------------------------------------------------------------------------------------------- entrada

    @Test
    fun `an entrance starts invisible, ends opaque and replays only when its key changes`() = motionTest {
        var key by mutableStateOf("ficha-1")
        var data by mutableStateOf(0)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxSize().motionEntrance(key = key).background(Color.Black)) { Text("dato $data") }
            }
        }
        assertTrue(cornerLuminance() > 0.9f, "en el primer frame el contenido todavia no se ve")

        mainClock.advanceTimeBy(OposiMotion.Emphasis + 64L)
        assertTrue(cornerLuminance() < 0.05f, "al terminar es opaco")

        // Datos nuevos con la misma clave: nada vuelve a entrar.
        data = 1
        mainClock.advanceTimeByFrame()
        assertTrue(cornerLuminance() < 0.05f, "recargar datos no repite la entrada")

        key = "ficha-2"
        mainClock.advanceTimeByFrame()
        assertTrue(cornerLuminance() > 0.5f, "otra ficha entra desde invisible, sin un frame previo a opacidad completa")
        mainClock.advanceTimeBy(OposiMotion.Emphasis + 64L)
        assertTrue(cornerLuminance() < 0.05f)
    }

    @Test
    fun `a later step of the same entrance waits its stagger`() = motionTest {
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxSize().motionEntrance(key = "pagina", order = 1).background(Color.Black))
            }
        }
        mainClock.advanceTimeBy(OposiMotion.Stagger - 8L)
        assertTrue(cornerLuminance() > 0.9f, "durante el desfase sigue invisible")
        mainClock.advanceTimeBy(OposiMotion.Emphasis + 64L)
        assertTrue(cornerLuminance() < 0.05f)
    }

    @Test
    fun `with motion disabled the content is there from the first frame`() = motionTest {
        setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxSize().motionEntrance(key = "ficha").background(Color.Black))
                }
            }
        }
        assertTrue(cornerLuminance() < 0.05f)
    }

    // ---------------------------------------------------------------------------------------------- resalte

    @Test
    fun `a highlight does not fire when the row is born, fires when its trigger changes and fades by itself`() = motionTest {
        var balance by mutableStateOf(100)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White).motionHighlight(trigger = balance, color = Color.Red))
        }
        mainClock.advanceTimeByFrame()
        assertEquals(Color.White, cornerColor(), "una lista recien cargada no se resalta")

        balance = 150
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val lit = cornerColor()
        assertTrue(lit.red > lit.green + 0.05f, "el cambio enciende el velo: $lit")

        mainClock.advanceTimeBy(OposiMotion.Highlight + 64L)
        assertEquals(Color.White, cornerColor(), "y se apaga solo")
    }

    @Test
    fun `a row that arrives later can be born highlighted`() = motionTest {
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White).motionHighlight(trigger = "mov-9", color = Color.Red, onAppear = true))
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val lit = cornerColor()
        assertTrue(lit.red > lit.green + 0.05f, "el asiento nuevo nace resaltado: $lit")
        mainClock.advanceTimeBy(OposiMotion.Highlight + 64L)
        assertEquals(Color.White, cornerColor())
    }

    // ---------------------------------------------------------------------------------------------- contador

    @Test
    fun `a count runs from the old value to the new one only for the same owner`() = motionTest {
        var owner by mutableStateOf("cartera-a")
        var balance by mutableStateOf(100)
        val seen = mutableListOf<Int>()
        setContent {
            val shown = animatedCount(target = balance, owner = owner)
            seen += shown
            Text("$shown")
        }
        mainClock.advanceTimeByFrame()
        assertEquals(listOf(100), seen.distinct(), "el saldo con el que se abre la cartera no se cuenta")

        seen.clear()
        balance = 200
        mainClock.advanceTimeBy(OposiMotion.Emphasis * 2 + 64L)
        assertEquals(200, seen.last())
        assertTrue(seen.any { it in 101..199 }, "un ajuste se cuenta por el camino: $seen")
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "y siempre hacia el valor nuevo: $seen")

        // Otra cartera: su saldo aparece tal cual, sin contar desde el de la anterior.
        seen.clear()
        owner = "cartera-b"
        balance = 5
        mainClock.advanceTimeBy(OposiMotion.Emphasis * 2 + 64L)
        assertEquals(listOf(5), seen.distinct())
    }

    // ---------------------------------------------------------------------------------------------- AsyncStatePane

    @Test
    fun `new data is new content by default and updates in place when the pane is stable`() {
        fun bornAndNow(stable: Boolean): String {
            var text = ""
            runDesktopComposeUiTest(width = 240, height = 120) {
                var state by mutableStateOf<UiAsync<Int>>(UiAsync.Success(1))
                setContent {
                    AsyncStatePane(state = state, stableContent = stable) { value ->
                        val born = remember { value }
                        Text("nacio=$born ahora=$value")
                    }
                }
                waitForIdle()
                state = UiAsync.Success(2)
                waitForIdle()
                text = if (stable) "nacio=1 ahora=2" else "nacio=2 ahora=2"
                onNodeWithText(text).assertExists()
            }
            return text
        }
        // Por defecto, como siempre: lo que hay dentro se reinicia con los datos (asi lo esperan las pantallas existentes).
        assertEquals("nacio=2 ahora=2", bornAndNow(stable = false))
        // Estable: el contenido sigue vivo y solo cambian sus datos (scroll y resaltes sobreviven).
        assertEquals("nacio=1 ahora=2", bornAndNow(stable = true))
    }

    // ---------------------------------------------------------------------------------------------- apoyo

    private fun motionTest(block: SkikoComposeUiTest.() -> Unit) {
        runDesktopComposeUiTest(width = 64, height = 64) {
            mainClock.autoAdvance = false
            block()
        }
    }

    /** Un pixel lejos del texto de prueba (que va arriba a la izquierda). */
    private fun SkikoComposeUiTest.cornerColor(): Color {
        val pixels = onRoot().captureToImage().toPixelMap()
        return pixels[pixels.width - 4, pixels.height - 4]
    }

    private fun SkikoComposeUiTest.cornerLuminance(): Float = cornerColor().let { (it.red + it.green + it.blue) / 3f }
}
