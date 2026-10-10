package com.oposicontrol.admin.features.store.presentation

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
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Lo que comparten los bancos visuales de Monetización (Catálogo y OposiMarket): el mismo `DesktopShell`, el mismo
 * `AdminTheme` y la misma fila de pestañas del hub que en la aplicación, para que el PNG sea la pantalla y no una pieza
 * suelta. **No valida datos**: ninguna línea toca un repositorio.
 */
internal data class StoreCapture(val fileName: String, val width: Int, val height: Int, val dark: Boolean = false)

/** Pestañas del hub que envuelve a la pantalla, transcritas de `composeApp` (allí viven `CatalogHubTab` y compañía). */
internal data class HarnessHub(
    val routeId: String,
    val title: String,
    val description: String,
    val tabs: List<DesktopTab>,
    val selectedTab: String,
)

internal val CATALOG_HUB_TABS: List<DesktopTab> = listOf(
    DesktopTab("products", "Productos", AppIcon.CATALOG),
    DesktopTab("plans", "Planes", AppIcon.PLANS),
)

internal fun catalogHub(selectedTab: String) = HarnessHub(
    routeId = "commercial.catalog",
    title = "Catálogo",
    description = "Productos, tarifas y planes que OposiBot enseña a los usuarios.",
    tabs = CATALOG_HUB_TABS,
    selectedTab = selectedTab,
)

@OptIn(ExperimentalTestApi::class)
internal fun renderStoreCapture(capture: StoreCapture, hub: HarnessHub, content: @Composable () -> Unit) {
    runDesktopComposeUiTest(width = capture.width, height = capture.height) {
        setContent {
            // Densidad 1: 1 dp es 1 px y el PNG mide exactamente lo que dice su nombre.
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = capture.dark) {
                    DesktopShell(
                        currentRouteId = hub.routeId,
                        groups = STORE_HARNESS_SIDEBAR,
                        onRouteSelected = {},
                        environmentLabel = "Entorno dev | vista frontend",
                        title = hub.title,
                        description = hub.description,
                        breadcrumbs = listOf("Monetización", hub.title),
                        globalSearch = {},
                    ) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            if (hub.tabs.isNotEmpty()) {
                                DesktopTabs(tabs = hub.tabs, selectedId = hub.selectedTab, onSelect = {})
                            }
                            content()
                        }
                    }
                }
            }
        }
        waitForIdle()
        // Las fuentes de `compose-resources` cargan en una corrutina que el reloj de test no rastrea.
        Thread.sleep(FONT_SETTLE_MILLIS)
        waitForIdle()
        // Un `Dialog` de escritorio abre una segunda raíz: la primera es la pantalla y la última el diálogo.
        val roots = onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        val file = File(storeHarnessOutputDir, capture.fileName)
        ImageIO.write(roots[0].captureToImage().toAwtImage(), "png", file)
        assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        if (count > 1) {
            val dialog = File(storeHarnessOutputDir, capture.fileName.removeSuffix(".png") + "-dialogo.png")
            ImageIO.write(roots[count - 1].captureToImage().toAwtImage(), "png", dialog)
        }
    }
}

internal val storeHarnessOutputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

private const val FONT_SETTLE_MILLIS = 2_000L

/** Sidebar visible tras el cambio de arquitectura de información. Transcrita: el catálogo de navegación vive en `composeApp`. */
internal val STORE_HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
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
            ShellNavigationItem("commercial.tickets", "OposiTickets", AppIcon.TICKETS.key),
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
