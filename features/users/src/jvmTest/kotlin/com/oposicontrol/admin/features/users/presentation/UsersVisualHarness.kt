package com.oposicontrol.admin.features.users.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
// `isRoot` es un matcher de nivel superior y se importa; `onAllNodes` NO: es un
// método del propio scope de `runDesktopComposeUiTest`, así que importarlo no
// resuelve. Mismo juego de imports que `ExamBasicInfoVisualHarness`.
import androidx.compose.ui.test.isRoot
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
 * Banco de pruebas VISUAL de Usuarios.
 *
 * Renderiza la pantalla real —el mismo [UsersContent] que corre en producción,
 * dentro del mismo `DesktopShell` y del mismo `AdminTheme`— contra un
 * `UsersState` fabricado, y escribe PNG. Sirve para revisar jerarquía, densidad,
 * tema y responsive **sin levantar Ktor ni Koin**.
 *
 * **NO valida datos.** Ni una línea toca un repositorio o un contrato. Que una
 * captura se vea bien no dice nada sobre si la pantalla lee bien de Supabase.
 *
 * Los estados salen de [UsersFixtures]. Se escriben en
 * `features/users/build/visual-harness/`.
 */
@OptIn(ExperimentalTestApi::class)
class UsersVisualHarness {

    private val fx = UsersFixtures

    @Test
    fun capturaCatalogoYFicha() {
        render(Capture("users-1920-light-list.png", 1920, 1080, dark = false), fx.listado())
        render(Capture("users-1920-light-detail.png", 1920, 1080, dark = false), fx.opositoraSeleccionada())
        render(Capture("users-1920-dark-detail.png", 1920, 1080, dark = true), fx.opositoraSeleccionada())
    }

    /**
     * El editor entero no cabe en 1080px de alto: identidad, roles, barra de
     * guardado, acciones, el bloque de lo que no se puede hacer y encima la ficha
     * por pestañas. Estas capturas usan una ventana alta para poder REVISAR esas
     * secciones; las de 1080 se quedan para comprobar el pliegue real.
     */
    @Test
    fun capturaLaFichaCompleta() {
        render(Capture("users-detail-completo.png", 1920, 2600, dark = false), fx.opositoraSeleccionada())
        render(Capture("users-detail-completo-dark.png", 1920, 2600, dark = true), fx.opositoraSeleccionada())
    }

    /**
     * Las dos poblaciones.
     *
     * Es la captura que motivó toda la vertical: una cuenta con acceso a este
     * mismo panel tiene que leerse distinta de un opositor, y el aviso de que
     * conviven en la misma tabla tiene que estar a la vista.
     */
    @Test
    fun capturaLasDosPoblaciones() {
        render(Capture("users-admin-seleccionada.png", 1920, 2600, dark = false), fx.administradoraSeleccionada())
        render(Capture("users-invitado.png", 1920, 2600, dark = false), fx.invitadoSeleccionado())
        render(Capture("users-sin-catalogo-roles.png", 1920, 1080, dark = false), fx.sinCatalogoDeRoles())
    }

    @Test
    fun capturaElGuardado() {
        render(Capture("users-cambios-sin-guardar.png", 1920, 2600, dark = false), fx.conCambiosSinGuardar())
        render(Capture("users-confirmar-descarte.png", 1920, 1080, dark = false), fx.confirmandoDescarte())
        render(Capture("users-acceso-administrativo.png", 1920, 2600, dark = false), fx.concediendoAccesoAdministrativo())
        render(Capture("users-guardado-rechazado.png", 1920, 2600, dark = false), fx.guardadoRechazado())
    }

    @Test
    fun capturaLosEstadosDelCatalogo() {
        render(Capture("users-1920-empty.png", 1920, 1080, dark = false), fx.listaVacia())
        // El caso que antes era indistinguible del anterior.
        render(Capture("users-1920-error.png", 1920, 1080, dark = false), fx.listaCaida())
        render(Capture("users-loading.png", 1920, 1080, dark = false), fx.cargando())
        render(Capture("users-sin-coincidencias.png", 1920, 1080, dark = false), fx.sinCoincidencias())
        render(Capture("users-pagina-llena.png", 1920, 1400, dark = false), fx.paginaLlena())
        render(Capture("users-aviso-alta.png", 1920, 1080, dark = false), fx.avisoDeAlta())
    }

    @Test
    fun capturaLosAnchos() {
        render(Capture("users-1600.png", 1600, 900, dark = false), fx.opositoraSeleccionada())
        render(Capture("users-1366.png", 1366, 768, dark = false), fx.opositoraSeleccionada())
        render(Capture("users-1080.png", 1080, 900, dark = false), fx.opositoraSeleccionada())
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

    private fun render(capture: Capture, state: UsersState) {
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

            // `onRoot()` no sirve aquí: un `Dialog` de escritorio abre una SEGUNDA
            // raíz de composición, así que en los estados con diálogo la aserción
            // de «exactamente un nodo» falla. Mismo patrón que
            // `ExamBasicInfoVisualHarness`: se enumeran las raíces, la primera es
            // la pantalla y la última el diálogo.
            val raices = onAllNodes(isRoot())
            val cuantas = raices.fetchSemanticsNodes().size

            val file = File(outputDir, capture.fileName)
            ImageIO.write(raices[0].captureToImage().toAwtImage(), "png", file)
            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")

            if (cuantas > 1) {
                val dialogo = File(outputDir, capture.fileName.removeSuffix(".png") + "-dialogo.png")
                ImageIO.write(raices[cuantas - 1].captureToImage().toAwtImage(), "png", dialogo)
                assertTrue(dialogo.exists() && dialogo.length() > 0L, "No se escribió ${dialogo.absolutePath}")
            }
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    @Composable
    private fun HarnessShell(state: UsersState, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "users",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno dev | vista frontend",
                title = "Usuarios",
                description = "Identidad, roles, rendimiento y competición por usuario.",
                breadcrumbs = listOf("Usuarios"),
                // Sin acción primaria, como la pantalla real: `primaryActionLabelFor`
                // devuelve null para `USERS_HUB` (`AppRoot.kt:358-370`), así que el
                // hub de escritorio no dibuja ningún botón de cabecera. Ponerlo aquí
                // hacía que las capturas documentaran un botón que no existe.
                // Sin buscador global: el catálogo tiene el suyo, que sí encuentra.
                globalSearch = {},
            ) {
                UsersContent(state = state, onIntent = {})
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
