package com.oposicontrol.admin.features.store.presentation

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
