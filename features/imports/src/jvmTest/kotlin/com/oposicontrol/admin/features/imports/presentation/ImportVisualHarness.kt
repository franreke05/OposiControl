package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas VISUAL de Importación.
 *
 * Renderiza la pantalla real —el mismo [ImportContent] que corre en producción,
 * dentro del mismo `DesktopShell` y del mismo `AdminTheme`— contra un
 * `ImportState` fabricado. Sirve para revisar jerarquía, densidad, tema y
 * responsive **sin levantar Ktor ni Koin**.
 *
 * **NO valida datos.** Ni una línea toca un repositorio o un contrato.
 */
@OptIn(ExperimentalTestApi::class)
class ImportVisualHarness {

    private val fx = ImportFixtures

    @Test
    fun capturaInicio() {
        render(Capture("imports-1920-light-start.png", 1920, 1080, dark = false), fx.inicio())
        render(Capture("imports-1920-dark-start.png", 1920, 1080, dark = true), fx.inicio())
    }

    @Test
    fun capturaLosTresTipos() {
        render(Capture("imports-1920-light-rafaga.png", 1920, 1080, dark = false), fx.rafagaElegida())
        render(Capture("imports-1920-light-razonada.png", 1920, 1080, dark = false), fx.razonadaElegida())
        render(Capture("imports-1920-light-inmersiva.png", 1920, 1080, dark = false), fx.inmersivaElegida())
    }

    @Test
    fun capturaArchivoYProcesado() {
        render(Capture("imports-file-selected.png", 1920, 1080, dark = false), fx.archivoElegido())
        render(Capture("imports-processing.png", 1920, 1080, dark = false), fx.procesando())
        render(Capture("imports-upload-error.png", 1920, 1080, dark = false), fx.subidaFallida())
    }

    @Test
    fun capturaRevision() {
        render(Capture("imports-validation-ok.png", 1920, 1080, dark = false), fx.revisionSinErrores())
        render(Capture("imports-validation-errors.png", 1920, 1080, dark = false), fx.revisionConErrores())
        render(Capture("imports-review.png", 1920, 1400, dark = false), fx.revisionConDetalle())
        render(Capture("imports-review-dark.png", 1920, 1400, dark = true), fx.revisionConDetalle())
        render(Capture("imports-duplicate.png", 1920, 1080, dark = false), fx.revisionConDuplicado())
        render(Capture("imports-partial.png", 1920, 1080, dark = false), fx.publicadoParcial())
    }

    @Test
    fun capturaHistorial() {
        render(Capture("imports-history-empty.png", 1920, 1080, dark = false), fx.historialVacio())
        render(Capture("imports-history-error.png", 1920, 1080, dark = false), fx.historialCaido())
    }

    @Test
    fun capturaLosAnchos() {
        render(Capture("imports-1366.png", 1366, 768, dark = false), fx.rafagaElegida())
        render(Capture("imports-1080.png", 1080, 900, dark = false), fx.rafagaElegida())
    }

    // -----------------------------------------------------------------------
    // Motor de captura
    // -----------------------------------------------------------------------

    /**
     * Una captura y su tema, SIEMPRE explícito.
     *
     * `dark` no tiene valor por defecto a propósito: `AdminTheme` sin parámetro
     * resuelve con `isSystemInDarkTheme()`, y en una máquina con el sistema en
     * oscuro las capturas «de referencia» saldrían oscuras sin que nada lo dijera.
     */
    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: ImportState) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                // Densidad 1: así 1 dp es 1 px y el PNG mide exactamente lo que
                // dice su nombre.
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    HarnessShell(state = state, dark = capture.dark)
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` se cargan en una corrutina que el
            // reloj de test no rastrea: sin esta espera la primera captura sale con
            // la tipografía del sistema en vez de con Manrope.
            Thread.sleep(FONT_SETTLE_MILLIS)
            waitForIdle()

            val bitmap = onRoot().captureToImage()
            val file = File(outputDir, capture.fileName)
            ImageIO.write(bitmap.toAwtImage(), "png", file)

            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    @Composable
    private fun HarnessShell(state: ImportState, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "content.imports",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno dev | vista frontend",
                title = "Importación",
                description = "Importa preguntas desde PDF, revisa el resultado y publícalo cuando esté listo.",
                breadcrumbs = listOf("Contenido", "Importación"),
                globalSearch = {},
            ) {
                ImportContent(state = state, onIntent = {}, onPickPdf = {})
            }
        }
    }
}

private const val FONT_SETTLE_MILLIS = 2_000L

/**
 * Los items visibles de la sidebar, transcritos de `AppNavigationCatalog.kt`.
 *
 * Se transcriben porque el catálogo vive en `composeApp` y este módulo no puede
 * —ni debe— depender de él. **No es el catálogo en vivo**: si alguien renombra una
 * sección allí, la captura enseñará el nombre antiguo.
 */
private val HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
    ShellNavigationGroup(
        id = "home",
        label = "Inicio",
        items = listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key)),
    ),
    ShellNavigationGroup(
        id = "content",
        label = "Contenido",
        items = listOf(
            ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key),
            ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key),
            ShellNavigationItem("content.imports", "Importación", AppIcon.IMPORT.key),
        ),
    ),
    ShellNavigationGroup(
        id = "users",
        label = "Usuarios",
        items = listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key)),
    ),
    ShellNavigationGroup(
        id = "economy",
        label = "Monetización",
        items = listOf(
            ShellNavigationItem("commercial.catalog", "Catálogo", AppIcon.CATALOG.key),
            ShellNavigationItem("commercial.market", "OposiMarket", AppIcon.MARKET.key),
            ShellNavigationItem("commercial.tickets", "OposiTickets", AppIcon.TICKETS.key),
        ),
    ),
    ShellNavigationGroup(
        id = "community",
        label = "Comunidad",
        items = listOf(
            ShellNavigationItem("community.oposired", "OposiRed", AppIcon.FORUM.key),
            ShellNavigationItem("moderation", "Moderación", AppIcon.MODERATION.key),
            ShellNavigationItem("support", "Soporte", AppIcon.SUPPORT.key),
        ),
    ),
    ShellNavigationGroup(
        id = "analytics",
        label = "Analítica",
        items = listOf(ShellNavigationItem("analytics", "Analytics", AppIcon.ANALYTICS.key)),
    ),
    ShellNavigationGroup(
        id = "operations",
        label = "Publicación",
        items = listOf(ShellNavigationItem("publication.news", "Noticias", AppIcon.NEWS.key)),
    ),
    ShellNavigationGroup(
        id = "system",
        label = "Sistema",
        items = listOf(ShellNavigationItem("system.settings", "Sistema", AppIcon.SETTINGS.key)),
    ),
)
