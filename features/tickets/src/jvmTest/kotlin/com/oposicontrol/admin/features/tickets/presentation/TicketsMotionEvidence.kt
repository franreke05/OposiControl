package com.oposicontrol.admin.features.tickets.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Evidencia del movimiento de OposiTickets: la pantalla real con el reloj parado, un cambio de estado y una tira de
 * fotogramas a tiempos conocidos. Cada tira se escribe en `features/tickets/build/motion-evidence/`.
 *
 * No valida datos ni mide rendimiento: enseña QUÉ se mueve, cuánto y durante cuánto, para poder revisarlo sin abrir la
 * aplicación. Se ejecuta con `--rerun`.
 */
@OptIn(ExperimentalTestApi::class)
class TicketsMotionEvidence {
    private val fx = TicketsFixtures

    @Test
    fun unAjusteAplicado() {
        // El saldo cuenta de 120 a 170, la cartera se resalta en el catalogo y el asiento nuevo nace resaltado.
        val before = fx.carteraAbierta()
        val wallets = (before.wallets as UiAsync.Success).value.map { if (it.userId == before.selectedUserId) it.copy(balance = 170) else it }
        val movements = (before.transactions as UiAsync.Success).value
        val after = before.copy(
            wallets = UiAsync.Success(wallets),
            allWallets = wallets,
            transactions = UiAsync.Success(
                listOf(TicketMovement("m0", "ajuste", 50, "Compensación por la caída del simulacro", "2026-10-12T09:00:00Z", 120, 170, "admin")) + movements,
            ),
        )
        filmstrip("tickets-ajuste-aplicado", before, after, offsets = listOf(16, 96, 208, 448, 960))
    }

    @Test
    fun otraCartera() {
        // Abrir otra cartera: el cuerpo entra; el saldo NO cuenta (es otro dato) y nada se resalta.
        val before = fx.carteraAbierta()
        val other = (before.wallets as UiAsync.Success).value[1]
        val after = before.copy(
            selectedUserId = other.userId,
            transactions = UiAsync.Success(
                listOf(TicketMovement("x1", "pack_tickets", 2_600, "Pack de 2.600 OposiTickets", "2026-10-02T10:00:00Z", 0, 2_600, "compra")),
            ),
        )
        filmstrip("tickets-otra-cartera", before, after)
    }

    @Test
    fun otraPestana() {
        val before = fx.carteraAbierta()
        filmstrip("tickets-pestana-ajuste", before, before.copy(tab = TicketsTab.ADJUST))
    }

    private fun filmstrip(name: String, before: TicketsState, after: TicketsState, offsets: List<Long> = listOf(16, 64, 128, 208, 320)) {
    // Evidencia y medidas a peticion: dependen de la carga de la maquina y no forman parte de la bateria normal.
    if (System.getenv("OPOSI_MOTION_EVIDENCE") != "1") return
        runDesktopComposeUiTest(width = FRAME_WIDTH, height = FRAME_HEIGHT) {
            var state by mutableStateOf(before)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) {
                        DesktopShell(
                            currentRouteId = "users",
                            groups = HARNESS_SIDEBAR,
                            onRouteSelected = {},
                            environmentLabel = "Entorno production",
                            title = "Usuarios",
                            description = "Identidad, perfil, rendimiento y competición por usuario.",
                            breadcrumbs = listOf("Usuarios"),
                            globalSearch = {},
                        ) {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                DesktopTabs(tabs = USERS_HUB_TABS, selectedId = "tickets", onSelect = {})
                                TicketsContent(state = state, onIntent = {}, onOpenUser = {})
                            }
                        }
                    }
                }
            }
            // Primero la pantalla asentada (fuentes incluidas); luego el reloj se para y el cambio se filma.
            waitForIdle()
            Thread.sleep(FONT_SETTLE_MILLIS)
            waitForIdle()
            mainClock.autoAdvance = false
            val frames = mutableListOf(0L to onAllNodes(isRoot())[0].captureToImage().toAwtImage())
            state = after
            var elapsed = 0L
            offsets.forEach { at ->
                mainClock.advanceTimeBy(at - elapsed)
                elapsed = at
                frames += at to onAllNodes(isRoot())[0].captureToImage().toAwtImage()
            }
            val file = File(outputDir, "$name.png")
            ImageIO.write(contactSheet(frames), "png", file)
            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    /** Los fotogramas en una fila, a mitad de tamaño, con su instante rotulado: «antes» y luego t = N ms tras el cambio. */
    private fun contactSheet(frames: List<Pair<Long, BufferedImage>>): BufferedImage {
        val cellWidth = FRAME_WIDTH / 2
        val cellHeight = FRAME_HEIGHT / 2
        val sheet = BufferedImage(cellWidth * frames.size, cellHeight + LABEL_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        frames.forEachIndexed { index, (at, image) ->
            val x = index * cellWidth
            g.drawImage(image, x, LABEL_HEIGHT, cellWidth, cellHeight, null)
            g.color = Color(0x22, 0x22, 0x22)
            g.drawString(if (index == 0) "antes" else "t = $at ms", x + 8, 18)
            g.color = Color(0xCC, 0xCC, 0xCC)
            g.drawLine(x, 0, x, sheet.height)
        }
        g.dispose()
        return sheet
    }

    private val outputDir: File by lazy { File("build/motion-evidence").also { it.mkdirs() } }
}

private const val FRAME_WIDTH = 1366
private const val FRAME_HEIGHT = 768
private const val LABEL_HEIGHT = 26
private const val FONT_SETTLE_MILLIS = 2_000L
