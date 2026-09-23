package com.oposicontrol.admin.features.questions.presentation

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
 * Banco de pruebas VISUAL de Preguntas.
 *
 * Renderiza la pantalla real —el mismo [QuestionsContent] que corre en
 * producción, dentro del mismo `DesktopShell` y del mismo `AdminTheme`— contra un
 * `QuestionsState` fabricado, y escribe PNG. Sirve para revisar jerarquía,
 * densidad, tema y responsive **sin levantar Ktor ni Koin**.
 *
 * **NO valida datos.** Ni una línea toca un repositorio o un contrato. Que una
 * captura se vea bien no dice nada sobre si la pantalla lee bien de Supabase.
 *
 * Los estados salen de [QuestionsFixtures]. Se escriben en
 * `features/questions/build/visual-harness/`.
 */
@OptIn(ExperimentalTestApi::class)
class QuestionsVisualHarness {

    private val fx = QuestionsFixtures

    @Test
    fun capturaPreguntaSeleccionada() {
        render(Capture("questions-1920-light-selected.png", 1920, 1080, dark = false), fx.razonadaSeleccionada())
        render(Capture("questions-1920-dark-selected.png", 1920, 1080, dark = true), fx.razonadaSeleccionada())
        // Con cambios sin guardar: la barra de guardado tiene que decirlo.
        render(Capture("questions-1920-light-dirty.png", 1920, 1080, dark = false), fx.razonadaConCambiosSinGuardar())
        // Un rechazo del servidor no se pinta como éxito.
        render(Capture("questions-1920-light-save-error.png", 1920, 1080, dark = false), fx.guardadoRechazado())
    }

    @Test
    fun capturaAltaYTipos() {
        render(Capture("questions-1920-light-new.png", 1920, 1080, dark = false), fx.nueva())
        render(Capture("questions-1920-light-rafaga.png", 1920, 1080, dark = false), fx.rafagaSeleccionada())
        render(Capture("questions-1920-light-tipo-desconocido.png", 1920, 1080, dark = false), fx.tipoSinIdentificar())
    }

    /**
     * El editor entero no cabe en 1080px de alto: con Contenido y Respuestas
     * ocupando el pliegue, Explicación, Clasificación, Exámenes vinculados y
     * Recursos quedan debajo —la barra de desplazamiento lo dice, pero un PNG no
     * se desplaza—. Estas capturas usan una ventana alta para poder REVISAR esas
     * secciones; la de 1080 se queda para comprobar el pliegue real.
     */
    @Test
    fun capturaInmersivaConRecursos() {
        // 2400 y no 1600: con seis secciones, a 1600 la captura llegaba hasta
        // Clasificación y «Recursos» —lo que este fichero existe para revisar—
        // seguía por debajo. El editor real desplaza y lo dice con su barra; un
        // PNG no desplaza, así que aquí se le da la altura que hace falta.
        render(Capture("questions-immersive-resources.png", 1920, 2400, dark = false), fx.inmersivaConRecursos())
        render(Capture("questions-immersive-resources-dark.png", 1920, 2400, dark = true), fx.inmersivaConRecursos())
        render(Capture("questions-1920-light-selected-completo.png", 1920, 2400, dark = false), fx.razonadaSeleccionada())
        render(Capture("questions-1920-light-rafaga-completo.png", 1920, 2400, dark = false), fx.rafagaSeleccionada())
    }

    @Test
    fun capturaEstadosDelBanco() {
        render(Capture("questions-empty.png", 1920, 1080, dark = false), fx.bancoVacio())
        render(Capture("questions-error.png", 1920, 1080, dark = false), fx.bancoCaido())
        render(Capture("questions-loading.png", 1920, 1080, dark = false), fx.cargando())
        render(Capture("questions-no-matches.png", 1920, 1080, dark = false), fx.sinCoincidencias())
        render(Capture("questions-exam-filtered.png", 1920, 1080, dark = false), fx.filtradoPorExamen())
    }

    @Test
    fun capturaLosAnchos() {
        render(Capture("questions-1600-light.png", 1600, 900, dark = false), fx.razonadaSeleccionada())
        render(Capture("questions-1366-light.png", 1366, 768, dark = false), fx.razonadaSeleccionada())
        render(Capture("questions-1080-light.png", 1080, 900, dark = false), fx.razonadaSeleccionada())
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

    private fun render(capture: Capture, state: QuestionsState) {
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
    private fun HarnessShell(state: QuestionsState, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "content.questions",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno dev | vista frontend",
                title = "Preguntas",
                description = "Gestiona el banco de preguntas, sus respuestas, clasificación y recursos.",
                breadcrumbs = listOf("Contenido", "Preguntas"),
                primaryActionLabel = "Nueva pregunta",
                onPrimaryAction = {},
                // Sin buscador global: el banco tiene el suyo, que sí encuentra.
                globalSearch = {},
            ) {
                QuestionsContent(state = state, onIntent = {})
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
