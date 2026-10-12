package com.oposicontrol.admin.features.tickets.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.TicketsOverview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas VISUAL de OposiTickets.
 *
 * Renderiza la pantalla real —el mismo [TicketsContent] de producción, dentro del mismo `DesktopShell` y `AdminTheme`—
 * contra estados fabricados y escribe PNG en `features/tickets/build/visual-harness/`. Sirve para revisar jerarquía,
 * densidad, tema y responsive sin levantar Ktor ni tocar producción. **No valida datos**: ninguna línea toca un repositorio.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
@OptIn(ExperimentalTestApi::class)
class TicketsVisualHarness {
    private val fx = TicketsFixtures

    @Test
    fun capturaMovimientosYAjuste() {
        render(Capture("tickets-1920-light-movimientos.png", 1920, 1080, dark = false), fx.carteraAbierta())
        render(Capture("tickets-1920-dark-movimientos.png", 1920, 1080, dark = true), fx.carteraAbierta())
        render(Capture("tickets-1920-light-ajuste.png", 1920, 1080, dark = false), fx.ajusteListo())
        render(Capture("tickets-1920-light-ajuste-invalido.png", 1920, 1080, dark = false), fx.ajusteInvalido())
        render(Capture("tickets-1920-light-confirmar.png", 1920, 1080, dark = false), fx.confirmando())
        render(Capture("tickets-1920-light-resultado-desconocido.png", 1920, 1080, dark = false), fx.resultadoDesconocido())
    }

    @Test
    fun capturaLosEstados() {
        render(Capture("tickets-1920-light-cargando.png", 1920, 1080, dark = false), fx.cargando())
        render(Capture("tickets-1920-light-vacio.png", 1920, 1080, dark = false), fx.sinCarteras())
        render(Capture("tickets-1920-light-error.png", 1920, 1080, dark = false), fx.caida())
        render(Capture("tickets-1920-light-sin-resultados.png", 1920, 1080, dark = false), fx.sinResultados())
        render(Capture("tickets-1920-light-sin-movimientos.png", 1920, 1080, dark = false), fx.sinMovimientos())
        render(Capture("tickets-1920-light-movimientos-caidos.png", 1920, 1080, dark = false), fx.movimientosCaidos())
        render(Capture("tickets-1920-light-limite.png", 1920, 2400, dark = false), fx.limiteDeMovimientos())
    }

    @Test
    fun capturaLosAnchos() {
        render(Capture("tickets-1600-light.png", 1600, 900, dark = false), fx.carteraAbierta())
        render(Capture("tickets-1366-light.png", 1366, 768, dark = false), fx.carteraAbierta())
        render(Capture("tickets-1080-light.png", 1080, 1400, dark = false), fx.carteraAbierta())
    }

    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: TicketsState) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                // Densidad 1: 1 dp es 1 px y el PNG mide exactamente lo que dice su nombre.
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    HarnessShell(state = state, dark = capture.dark)
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` cargan en una corrutina que el reloj de test no rastrea.
            Thread.sleep(FONT_SETTLE_MILLIS)
            waitForIdle()

            // Un `Dialog` de escritorio abre una segunda raíz: la primera es la pantalla y la última el diálogo.
            val roots = onAllNodes(isRoot())
            val count = roots.fetchSemanticsNodes().size
            val file = File(outputDir, capture.fileName)
            ImageIO.write(roots[0].captureToImage().toAwtImage(), "png", file)
            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
            if (count > 1) {
                val dialog = File(outputDir, capture.fileName.removeSuffix(".png") + "-dialogo.png")
                ImageIO.write(roots[count - 1].captureToImage().toAwtImage(), "png", dialog)
            }
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    @Composable
    private fun HarnessShell(state: TicketsState, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            // OposiTickets es una subpantalla de Usuarios: la misma cabecera y la misma fila de pestañas del hub.
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

private const val FONT_SETTLE_MILLIS = 2_000L

/** Estados fabricados. Los nombres y cifras son de ejemplo: nada de esto sale de un backend. */
internal object TicketsFixtures {
    private const val ANA = "11111111-1111-4111-8111-111111111111"

    private val wallets = listOf(
        WalletRow(ANA, "Ana García", "TCAE", 120),
        WalletRow("22222222-2222-4222-8222-222222222222", "Marta Ruiz Peña", "Enfermería", 2_600),
        WalletRow("33333333-3333-4333-8333-333333333333", "Javier Ortega", "TCAE", 0),
        WalletRow("44444444-4444-4444-8444-444444444444", "Lucía Fernández", "", 45),
        WalletRow("55555555-5555-4555-8555-555555555555", "Pablo Serrano", "Enfermería", 300),
        WalletRow("66666666-6666-4666-8666-666666666666", "Carmen Vidal", "TCAE", 10),
    )

    private val movements = listOf(
        TicketMovement("m1", "ajuste", 50, "Compensación por la incidencia del simulacro de abril", "2026-10-09T14:32:10Z", 70, 120, "admin"),
        TicketMovement("m2", "compra_examen", -30, "Solucionario Madrid 2027", "2026-10-07T09:12:44Z", 100, 70, "compra"),
        TicketMovement("m3", "reto_diario", 5, "Reto diario completado", "2026-10-06T07:01:02Z", 95, 100, "sistema"),
        TicketMovement("m4", "pack_tickets", 100, "Pack de 100 OposiTickets", "2026-10-01T18:45:00Z", null, 95, "compra"),
        TicketMovement("m5", "reporte_validado", 5, "", "2026-09-28T11:20:31Z", null, null, null),
    )

    private val overview = UiAsync.Success(TicketsOverview(wallets = 6, movements = 148, credits = 112, debits = 36))

    private fun base() = TicketsState(
        wallets = UiAsync.Success(wallets),
        allWallets = wallets,
        transactions = UiAsync.Success(movements),
        overview = overview,
        selectedUserId = ANA,
    )

    fun carteraAbierta() = base()

    fun ajusteListo() = base().copy(tab = TicketsTab.ADJUST, adjustmentAmount = "+50", adjustmentReason = "Compensación por la incidencia del simulacro de abril")

    fun ajusteInvalido() = base().copy(
        tab = TicketsTab.ADJUST,
        adjustmentAmount = "12,5",
        adjustmentReason = "",
        actionState = UiAsync.Error(AppError.Validation("La cantidad debe ser un número entero, por ejemplo 50 o -10.")),
    )

    fun confirmando() = ajusteListo().copy(
        pendingAdjustment = PendingAdjustment(ANA, "Ana García", -30, "Devolución de una compra duplicada", "adjust:demo:1"),
        adjustmentAmount = "-30",
        adjustmentReason = "Devolución de una compra duplicada",
    )

    fun resultadoDesconocido() = ajusteListo().copy(
        pendingAdjustment = PendingAdjustment(ANA, "Ana García", 50, "Compensación", "adjust:demo:2", awaitingConfirmation = false),
        actionState = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")),
    )

    fun cargando() = TicketsState(overview = UiAsync.Loading)

    fun sinCarteras() = TicketsState(wallets = UiAsync.Empty, overview = UiAsync.Success(TicketsOverview(0, 0, 0, 0)))

    fun caida() = TicketsState(
        wallets = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")),
        overview = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")),
    )

    fun sinResultados() = base().copy(wallets = UiAsync.Empty, searchQuery = "zaragoza", filter = WalletFilter.WITH_BALANCE)

    fun sinMovimientos() = base().copy(transactions = UiAsync.Empty)

    fun movimientosCaidos() = base().copy(transactions = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")))

    fun limiteDeMovimientos() = base().copy(
        transactions = UiAsync.Success(List(MOVEMENTS_SERVER_LIMIT) { index -> movements[index % movements.size].copy(id = "m$index") }),
    )
}

/** Pestañas del hub de Usuarios, transcritas de `composeApp` (`UsersHubTab`). */
internal val USERS_HUB_TABS: List<DesktopTab> = listOf(
    DesktopTab("users", "Usuarios", AppIcon.USERS),
    DesktopTab("tickets", "OposiTickets", AppIcon.TICKETS),
    DesktopTab("competition", "Competición", AppIcon.RANKINGS),
    DesktopTab("access", "Accesos", AppIcon.ACCESS),
)

/** Menú lateral real: OposiTickets no es una entrada, vive dentro de Usuarios. Transcrito: el catálogo vive en `composeApp`. */
internal val HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
    ShellNavigationGroup("home", "Inicio", listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key))),
    ShellNavigationGroup(
        "content",
        "Contenido",
        listOf(
            ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key),
            ShellNavigationItem("content.taxonomy", "Especialidades", AppIcon.LAYERS.key),
            ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key),
            ShellNavigationItem("content.imports", "Importación", AppIcon.IMPORT.key),
        ),
    ),
    ShellNavigationGroup(
        "users",
        "Usuarios",
        listOf(
            ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key),
        ),
    ),
    ShellNavigationGroup(
        "economy",
        "Monetización",
        listOf(
            ShellNavigationItem("commercial.catalog", "Catálogo", AppIcon.CATALOG.key),
            ShellNavigationItem("commercial.market", "OposiMarket", AppIcon.MARKET.key),
        ),
    ),
    ShellNavigationGroup(
        "community",
        "Comunidad",
        listOf(
            ShellNavigationItem("community.oposired", "OposiRed", AppIcon.FORUM.key),
            ShellNavigationItem("moderation", "Moderación", AppIcon.MODERATION.key),
            ShellNavigationItem("support", "Soporte", AppIcon.SUPPORT.key),
        ),
    ),
    ShellNavigationGroup("analytics", "Analítica", listOf(ShellNavigationItem("analytics", "Analytics", AppIcon.ANALYTICS.key))),
    ShellNavigationGroup("operations", "Publicación", listOf(ShellNavigationItem("publication.news", "Noticias", AppIcon.NEWS.key))),
    ShellNavigationGroup("system", "Sistema", listOf(ShellNavigationItem("system.settings", "Sistema", AppIcon.SETTINGS.key))),
)
