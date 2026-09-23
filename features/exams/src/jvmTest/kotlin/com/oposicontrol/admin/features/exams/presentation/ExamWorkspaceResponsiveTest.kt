package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La columna del editor en las ventanas donde se rompía (P0-2, P1-1), MEDIDA.
 *
 * Una captura se mira; esto se comprueba. Monta el workspace real dentro del
 * mismo `DesktopShell` que el banco visual —el ancho de contenido y el alto de la
 * columna salen de ahí— y lee de la semántica dónde han quedado la cabecera, la
 * franja, el formulario y la barra de guardar, en px (densidad 1).
 *
 * Cada caso corresponde a una medida del revisor:
 *
 *  - En 1366×768 un examen nuevo dejaba el formulario a 0px y «Crear examen»
 *    asomando 13 de sus 40px.
 *  - La primera tecla hacía crecer la franja de 40 a 130px y le quitaba 101px al
 *    formulario (181 → 80 en 1366; 304 → 203 en 1600).
 *  - En 1440 y 1600 el formulario de un examen nuevo medía 92 y 84px.
 */
@OptIn(ExperimentalTestApi::class)
class ExamWorkspaceResponsiveTest {

    /**
     * Una caja vertical en px.
     *
     * [top]/[bottom] son lo VISIBLE —recortado por los contenedores, que es lo que
     * ve quien usa la pantalla—; [arriba] y [altoReal], dónde está y cuánto mide de
     * verdad. Una barra de guardar cortada tiene `alto < altoReal`; una cabecera que
     * se ha ido por arriba tiene `arriba` por encima de su sitio aunque su parte
     * visible empiece en el borde de la columna.
     */
    private data class Caja(val top: Int, val bottom: Int, val arriba: Int, val altoReal: Int) {
        val alto: Int get() = bottom - top
    }

    private data class Medida(val cabecera: Caja, val franja: Caja?, val formulario: Caja, val barra: Caja)

    @Test
    fun laFranjaMideSiempreLoMismoYLaPrimeraTeclaNoLeQuitaAltoAlFormulario() {
        val franja = ExamWorkspaceSizing.summaryStripHeight.value.roundToInt()
        val limpio = medirEn(1366, 768, existenteCompleto())
        val sucio = medirEn(1366, 768, conCambiosSinGuardar())
        val nuevo = medirEn(1366, 768, examenNuevoVacio())
        listOf("limpio" to limpio, "sucio" to sucio, "nuevo" to nuevo).forEach { (nombre, m) ->
            assertEquals(franja, m.franja?.alto, "[1366 $nombre] La franja no mide una línea: $m")
        }
        assertEquals(limpio.formulario, sucio.formulario, "La primera tecla ha movido o encogido el formulario")
        assertEquals(limpio.barra, sucio.barra, "La primera tecla ha movido la barra de guardar")
    }

    @Test
    fun en1366LaBarraDeGuardarSeVeEnteraYAlBajarElFormularioLlegaASuMinimo() {
        val minimo = ExamWorkspaceSizing.editorFormMinHeight.value.roundToInt()
        conPantalla(1366, 768, examenNuevoVacio()) {
            val reposo = medida()
            assertDentroDeLaVentana(reposo.barra, 768, "[1366 nuevo, en reposo] Barra de guardar")
            assertTrue(reposo.formulario.alto > 0, "[1366 nuevo] El formulario vuelve a medir 0px: $reposo")

            // La rueda sobre el formulario, como la gira quien lo usa: el puntero
            // encima y un giro hacia abajo.
            onNodeWithTag(EXAM_EDITOR_FORM_TAG).performMouseInput {
                moveTo(center)
                scroll(HARNESS_WHEEL_DELTA)
            }
            waitForIdle()
            val abajo = medida()
            assertTrue(abajo.cabecera.arriba < reposo.cabecera.arriba, "Al bajar la cabecera no se ha desplazado: $abajo")
            assertTrue(abajo.formulario.alto >= minimo, "Al bajar el formulario mide ${abajo.formulario.alto}px, menos de $minimo")
            assertEquals(reposo.barra, abajo.barra, "La barra de guardar se ha movido al bajar")
            assertEquals(abajo.barra.altoReal, abajo.barra.alto, "La barra de guardar ha quedado cortada")

            onNodeWithTag(EXAM_EDITOR_FORM_TAG).performMouseInput {
                moveTo(center)
                scroll(-HARNESS_WHEEL_DELTA * 3)
            }
            waitForIdle()
            assertEquals(reposo, medida(), "Al volver arriba la columna no ha vuelto a su sitio")
        }
    }

    @Test
    fun en1440Y1600ElFormularioTieneSuMinimoSinTocarNada() {
        val minimo = ExamWorkspaceSizing.editorFormMinHeight.value.roundToInt()
        listOf(1440 to 900, 1600 to 900).forEach { (ancho, alto) ->
            listOf(
                "nuevo" to examenNuevoVacio(),
                "limpio" to existenteCompleto(),
                "sucio" to conCambiosSinGuardar(),
            ).forEach { (nombre, estado) ->
                val m = medirEn(ancho, alto, estado)
                assertTrue(m.formulario.alto >= minimo, "[$ancho $nombre] El formulario mide ${m.formulario.alto}px: $m")
                assertDentroDeLaVentana(m.barra, alto, "[$ancho $nombre] Barra de guardar")
            }
        }
    }

    @Test
    fun elDetalleDeLaFranjaSeAbreEncimaSinEmpujarElFormulario() {
        conPantalla(1366, 768, examenNuevoVacio()) {
            val cerrado = medida()
            onNodeWithText(EXAM_STRIP_DETAIL_LABEL, substring = false).performClick()
            waitForIdle()
            assertTrue(onAllNodes(isRoot()).fetchSemanticsNodes().size >= 2, "«Ver detalle» no ha abierto ninguna capa")
            // En 1366 no hay columna de resumen: este detalle sólo puede estar en la capa.
            assertTrue(
                onAllNodesWithText("Sin título no se puede guardar.").fetchSemanticsNodes().isNotEmpty(),
                "La capa no enseña el detalle de lo pendiente",
            )
            assertEquals(cerrado, medida(), "Abrir el detalle ha movido la columna del editor")
        }
    }

    // -----------------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------------

    private fun medirEn(ancho: Int, alto: Int, estado: ExamsState): Medida {
        var resultado: Medida? = null
        conPantalla(ancho, alto, estado) { resultado = medida() }
        return checkNotNull(resultado)
    }

    private fun conPantalla(ancho: Int, alto: Int, estado: ExamsState, bloque: ComposeUiTest.() -> Unit) {
        runDesktopComposeUiTest(width = ancho, height = alto) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    ExamBasicInfoHarnessShell(state = estado, dark = false)
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` cargan en una corrutina que el reloj
            // de test no rastrea; sin ellas la cabecera mide otra cosa.
            Thread.sleep(2_000)
            waitForIdle()
            bloque()
        }
    }

    private fun ComposeUiTest.caja(tag: String): Caja? =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { nodo ->
            Caja(
                top = nodo.boundsInRoot.top.roundToInt(),
                bottom = nodo.boundsInRoot.bottom.roundToInt(),
                arriba = nodo.positionInRoot.y.roundToInt(),
                altoReal = nodo.size.height,
            )
        }

    private fun ComposeUiTest.medida(): Medida = Medida(
        cabecera = checkNotNull(caja(EXAM_HEADER_TAG)) { "Sin cabecera" },
        franja = caja(EXAM_SUMMARY_STRIP_TAG),
        formulario = checkNotNull(caja(EXAM_EDITOR_FORM_TAG)) { "Sin formulario" },
        barra = checkNotNull(caja(EXAM_SAVE_BAR_TAG)) { "Sin barra de guardar" },
    )

    private fun assertDentroDeLaVentana(caja: Caja, alto: Int, que: String) {
        assertTrue(caja.top >= 0 && caja.bottom <= alto, "$que fuera de la ventana de $alto px: $caja")
    }
}
