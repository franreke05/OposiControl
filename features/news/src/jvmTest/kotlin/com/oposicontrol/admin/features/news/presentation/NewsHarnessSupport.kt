package com.oposicontrol.admin.features.news.presentation

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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Lo que comparten las capturas de Noticias: el mismo `DesktopShell`, el mismo `AdminTheme` y la fila de pestanas del
 * hub de Publicacion que en la aplicacion, para que el PNG sea la pantalla y no una pieza suelta. **No valida datos**:
 * ninguna linea toca un repositorio ni la red.
 */
internal data class NewsCapture(val fileName: String, val width: Int, val height: Int, val dark: Boolean = false)

internal val NEWS_HUB_TABS: List<DesktopTab> = listOf(
    DesktopTab("news", "Noticias", AppIcon.NEWS),
    DesktopTab("types", "Tipos", AppIcon.NEWS_TYPES),
)

@OptIn(ExperimentalTestApi::class)
internal fun renderNewsCapture(capture: NewsCapture, content: @Composable () -> Unit) {
    runDesktopComposeUiTest(width = capture.width, height = capture.height) {
        setContent {
            // Densidad 1: 1 dp es 1 px y el PNG mide exactamente lo que dice su nombre.
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = capture.dark) {
                    DesktopShell(
                        currentRouteId = "publication.news",
                        groups = NEWS_HARNESS_SIDEBAR,
                        onRouteSelected = {},
                        environmentLabel = "Entorno production",
                        title = "Noticias",
                        description = "Gestiona, prepara y publica las noticias que aparecen en OposiBot.",
                        breadcrumbs = listOf("Publicación", "Noticias"),
                        globalSearch = {},
                    ) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            DesktopTabs(tabs = NEWS_HUB_TABS, selectedId = "news", onSelect = {})
                            content()
                        }
                    }
                }
            }
        }
        waitForIdle()
        // Las fuentes de `compose-resources` y las imagenes locales cargan fuera del reloj de test.
        Thread.sleep(FONT_SETTLE_MILLIS)
        waitForIdle()
        // Un `Dialog` de escritorio abre una segunda raiz: la primera es la pantalla y la ultima el dialogo.
        val roots = onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        val file = File(newsHarnessOutputDir, capture.fileName)
        ImageIO.write(roots[0].captureToImage().toAwtImage(), "png", file)
        assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        if (count > 1) {
            val dialog = File(newsHarnessOutputDir, capture.fileName.removeSuffix(".png") + "-dialogo.png")
            ImageIO.write(roots[count - 1].captureToImage().toAwtImage(), "png", dialog)
        }
    }
}

internal val newsHarnessOutputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

/** Una imagen de fondo FABRICADA (formas planas) para que las capturas tengan algo que pintar sin salir a la red. */
internal val newsFixtureCover: String by lazy {
    val file = File(newsHarnessOutputDir, "fixtures/fixture-cover.png")
    file.parentFile.mkdirs()
    val bitmap = BufferedImage(1432, 592, BufferedImage.TYPE_INT_RGB)
    bitmap.createGraphics().apply {
        color = Color(26, 51, 67); fillRect(0, 0, 1432, 592)
        color = Color(57, 115, 140); fillOval(900, -120, 560, 560)
        color = Color(103, 168, 183); fillRoundRect(1130, 150, 170, 442, 32, 32)
        color = Color(38, 75, 94); fillRoundRect(980, 300, 130, 292, 28, 28)
        dispose()
    }
    ImageIO.write(bitmap, "png", file)
    "file://" + file.absolutePath.replace('\\', '/')
}

private const val FONT_SETTLE_MILLIS = 2_000L

/** Sidebar transcrita del catalogo de navegacion de `composeApp` (Noticias vive en el bloque Publicacion). */
internal val NEWS_HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
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
