package com.oposicontrol.admin.features.users.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Tira de fotogramas de un cambio de estado: la pantalla asentada, el reloj parado, el paso 0 -> 1 y una captura en
 * cada instante de [offsets] (ms tras el cambio). Se escribe en `build/motion-evidence/<name>.png`.
 * Evidencia para revisar el movimiento sin abrir la aplicacion; no valida datos ni mide rendimiento.
 */
@OptIn(ExperimentalTestApi::class)
internal fun motionFilmstrip(
    name: String,
    offsets: List<Long> = listOf(16, 64, 128, 208, 320),
    width: Int = 1366,
    height: Int = 768,
    content: @Composable (step: Int) -> Unit,
) {
    runDesktopComposeUiTest(width = width, height = height) {
        var step by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = false) { content(step) }
            }
        }
        waitForIdle()
        Thread.sleep(2_000) // las fuentes de compose-resources cargan fuera del reloj de test
        waitForIdle()
        mainClock.autoAdvance = false
        fun shot(): BufferedImage = onAllNodes(isRoot()).let { roots -> roots[roots.fetchSemanticsNodes().size - 1].captureToImage().toAwtImage() }
        val frames = mutableListOf(-1L to shot())
        step = 1
        var elapsed = 0L
        offsets.forEach { at ->
            mainClock.advanceTimeBy(at - elapsed)
            elapsed = at
            frames += at to shot()
        }
        val cellWidth = frames.maxOf { it.second.width } / 2
        val cellHeight = frames.maxOf { it.second.height } / 2
        val sheet = BufferedImage(cellWidth * frames.size, cellHeight + 26, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        frames.forEachIndexed { index, (at, image) ->
            val x = index * cellWidth
            g.drawImage(image, x, 26, image.width / 2, image.height / 2, null)
            g.color = Color(0x22, 0x22, 0x22)
            g.drawString(if (at < 0) "antes" else "t = $at ms", x + 8, 18)
            g.color = Color(0xCC, 0xCC, 0xCC)
            g.drawLine(x, 0, x, sheet.height)
        }
        g.dispose()
        val file = File(File("build/motion-evidence").also { it.mkdirs() }, "$name.png")
        ImageIO.write(sheet, "png", file)
        check(file.length() > 0L) { "No se escribio ${file.absolutePath}" }
    }
}


/**
 * Comprobacion PRACTICA de rendimiento del movimiento, no un benchmark: repite [cycles] veces un cambio de estado real
 * (otro registro, otra pestana, abrir y cerrar un dialogo) dejando que cada transicion termine.
 *
 * Se mide dos veces: con el movimiento apagado (lo que cuesta el cambio de pantalla en si) y encendido. El banco pinta
 * por software la ventana entera en cada fotograma, bastante mas lento que la aplicacion real, asi que lo que se
 * compara es el coste POR FOTOGRAMA animado contra el coste de un cambio sin animar: un fotograma de animacion (una
 * capa con alfa y traslacion) tiene que costar menos que recomponer la pantalla. Falla tambien si en reposo el reloj
 * sigue avanzando (algo se anima sin que nadie haga nada) o si el monton crece mas de [maxHeapGrowthMb].
 */
@OptIn(ExperimentalTestApi::class)
internal fun motionPerformance(
    name: String,
    states: Int,
    cycles: Int = 120,
    maxHeapGrowthMb: Long = 96,
    content: @Composable (step: Int) -> Unit,
) {
    val lines = mutableListOf<String>()
    var staticCost = 0.0
    for (motion in listOf(false, true)) {
        runDesktopComposeUiTest(width = 1366, height = 768) {
            var step by mutableStateOf(0)
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 1f),
                    com.oposicontrol.admin.core.ui.theme.LocalMotionEnabled provides motion,
                ) {
                    AdminTheme(darkTheme = false) { content(step) }
                }
            }
            waitForIdle()
            Thread.sleep(2_000)
            waitForIdle()

            // Reposo: con todo asentado, esperar no debe hacer avanzar el reloj (no hay animacion perpetua).
            val idleStart = mainClock.currentTime
            repeat(5) { waitForIdle() }
            val idleAdvance = mainClock.currentTime - idleStart

            fun usedMb(): Long {
                System.gc()
                Thread.sleep(60)
                val runtime = Runtime.getRuntime()
                return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            }
            repeat(10) { step = (step + 1) % states; waitForIdle() } // calentamiento
            val heapBefore = usedMb()
            val millis = DoubleArray(cycles)
            val clockStart = mainClock.currentTime
            repeat(cycles) { index ->
                val start = System.nanoTime()
                step = (step + 1) % states
                waitForIdle()
                millis[index] = (System.nanoTime() - start) / 1_000_000.0
            }
            val heapGrowth = usedMb() - heapBefore
            val average = millis.average()
            val sorted = millis.sorted()
            val frames = (mainClock.currentTime - clockStart) / 16.0 / cycles
            val perFrame = if (frames >= 1.0) average / frames else average
            if (!motion) staticCost = average
            val report = ("$name motion=" + (if (motion) "on" else "off") + ": transition avg=%.1f ms p95=%.1f ms max=%.1f ms; frames per transition=%.1f; " +
                "avg per frame=%.1f ms; idleClockAdvance=$idleAdvance ms; heapGrowth=$heapGrowth MB")
                .format(java.util.Locale.ROOT, average, sorted[(cycles * 95) / 100 - 1], sorted.last(), frames, perFrame)
            lines += report
            println(report)
            check(idleAdvance == 0L) { "Algo sigue animandose en reposo: $report" }
            check(heapGrowth <= maxHeapGrowthMb) { "La memoria crece con las transiciones: $report" }
            if (motion) check(perFrame <= staticCost) { "Un fotograma animado cuesta mas que recomponer la pantalla: $report" }
        }
    }
    File(File("build/motion-evidence").also { it.mkdirs() }, "performance-$name.txt").writeText(lines.joinToString("\n") + "\n")
}
