package com.oposicontrol.admin.features.exams.presentation

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
 * Banco de pruebas VISUAL del workspace de Exámenes.
 *
 * **Qué es y qué no es.** Renderiza el workspace real —el mismo
 * [ExamWorkspaceContent] que corre en producción, dentro del mismo
 * `DesktopShell` y del mismo `AdminTheme`— contra un `ExamsState` fabricado, y
 * escribe PNG. Sirve para revisar cristal, fondo, jerarquía, densidad y
 * responsive **sin levantar Ktor**.
 *
 * **NO valida datos.** Ni una línea de este fichero toca un repositorio, una
 * ruta ni un contrato. Que una captura se vea bien no dice nada sobre si la
 * pantalla lee bien de Supabase; eso sigue sin verificar y hay que decirlo así.
 *
 * **Los estados salen de [ExamWorkspaceFixtures]**, un solo sitio para las cinco
 * pestañas que no son Información básica. Información básica tiene su propio
 * banco (`ExamBasicInfoVisualHarness`) y no se toca.
 *
 * **Nombres de captura**: `exams-<pestaña>-<ancho>-<tema>[-<estado>].png`, con el
 * tema SIEMPRE explícito. Se escriben en `features/exams/build/visual-harness/`.
 */
@OptIn(ExperimentalTestApi::class)
class ExamWorkspaceVisualHarness {

    private val fx = ExamWorkspaceFixtures

    // -----------------------------------------------------------------------
    // Vista general: las cuatro pestañas a 1920, claro
    // -----------------------------------------------------------------------

    @Test
    fun capturaLasCuatroPestanasA1920() {
        // Cada pestaña con el estado que de verdad la pone a prueba.
        render(Capture("exams-1920-basica.png", 1920, 1080, dark = false), fx.borradorConCambiosSinGuardar(), ExamWorkspaceTabId.BASICA)
        render(Capture("exams-1920-configuracion.png", 1920, 1080, dark = false), fx.soloRafagaPermitida(), ExamWorkspaceTabId.CONFIGURACION)
        render(Capture("exams-1920-acceso.png", 1920, 1080, dark = false), fx.premiumConPrecio(), ExamWorkspaceTabId.ACCESO)
        render(Capture("exams-1920-publicacion.png", 1920, 1080, dark = false), fx.publicadoConContenido(), ExamWorkspaceTabId.PUBLICACION)
        render(Capture("exams-1920-configuracion-nuevo.png", 1920, 1080, dark = false), fx.examenNuevo(), ExamWorkspaceTabId.CONFIGURACION)
        render(Capture("exams-1920-basica-nuevo.png", 1920, 1080, dark = false), fx.examenNuevo(), ExamWorkspaceTabId.BASICA)
    }

    // -----------------------------------------------------------------------
    // Configuración
    // -----------------------------------------------------------------------

    @Test
    fun capturaConfiguracion() {
        val tab = ExamWorkspaceTabId.CONFIGURACION
        // Especialidad que sólo permite Ráfaga: la única regla de negocio real de
        // la pestaña, con dos tarjetas prohibidas y su `mensaje_limitacion`.
        render(Capture("exams-configuracion-1920-light.png", 1920, 1080, dark = false), fx.soloRafagaPermitida(), tab)
        render(Capture("exams-configuracion-1920-dark.png", 1920, 1080, dark = true), fx.soloRafagaPermitida(), tab)
        render(Capture("exams-configuracion-1366-light.png", 1366, 768, dark = false), fx.soloRafagaPermitida(), tab)
        // Estado clave: las capacidades no se han podido comprobar.
        render(Capture("exams-configuracion-1920-light-capacidades-caidas.png", 1920, 1080, dark = false), fx.capacidadesCaidas(), tab)
        render(Capture("exams-configuracion-1920-light-tipo-no-permitido.png", 1920, 1080, dark = false), fx.tipoNoPermitido(), tab)
        render(Capture("exams-configuracion-1920-light-nuevo.png", 1920, 1080, dark = false), fx.examenNuevo(), tab)
    }

    // -----------------------------------------------------------------------
    // Acceso
    // -----------------------------------------------------------------------

    @Test
    fun capturaAcceso() {
        val tab = ExamWorkspaceTabId.ACCESO
        render(Capture("exams-acceso-1920-light.png", 1920, 1080, dark = false), fx.premiumConPrecio(), tab)
        render(Capture("exams-acceso-1920-dark.png", 1920, 1080, dark = true), fx.premiumConPrecio(), tab)
        render(Capture("exams-acceso-1366-light.png", 1366, 768, dark = false), fx.premiumConPrecio(), tab)
        // Estado clave: un precio guardado en un examen gratuito, que no hace nada.
        render(Capture("exams-acceso-1920-light-precio-sin-efecto.png", 1920, 1080, dark = false), fx.gratuitoConPrecioSinEfecto(), tab)
        render(Capture("exams-acceso-1920-light-gratuito.png", 1920, 1080, dark = false), fx.gratuito(), tab)
        render(Capture("exams-acceso-1920-dark-gratuito.png", 1920, 1080, dark = true), fx.gratuito(), tab)
        render(Capture("exams-acceso-1920-light-premium-sin-precio.png", 1920, 1080, dark = false), fx.premiumSinPrecio(), tab)
        // Los dos regímenes de la pestaña: 1600 reparte Acceso y Monetización en
        // dos columnas; 1440, 1366 y 1080 las apilan.
        render(Capture("exams-acceso-1600-light.png", 1600, 900, dark = false), fx.premiumConPrecio(), tab)
        render(Capture("exams-acceso-1440-light.png", 1440, 900, dark = false), fx.premiumConPrecio(), tab)
        render(Capture("exams-acceso-1080-light.png", 1080, 900, dark = false), fx.premiumConPrecio(), tab)
    }

    // -----------------------------------------------------------------------
    // Publicación
    // -----------------------------------------------------------------------

    @Test
    fun capturaPublicacion() {
        val tab = ExamWorkspaceTabId.PUBLICACION
        // Borrador con cambios sin guardar: estado actual, avisos (versión
        // guardada y «Antes de publicar»), acciones inmediatas.
        render(Capture("exams-publicacion-1920-light.png", 1920, 1080, dark = false), fx.borradorConCambiosSinGuardar(), tab)
        render(Capture("exams-publicacion-1920-dark.png", 1920, 1080, dark = true), fx.recienPublicado(), tab)
        render(Capture("exams-publicacion-1366-light.png", 1366, 768, dark = false), fx.borradorConCambiosSinGuardar(), tab)
        // Estado clave: publicar ha fallado.
        render(Capture("exams-publicacion-1920-light-error.png", 1920, 1080, dark = false), fx.errorAlPublicar(), tab)
        render(Capture("exams-publicacion-1920-light-archivado.png", 1920, 1080, dark = false), fx.archivado(), tab)
        render(Capture("exams-publicacion-1920-light-nuevo.png", 1920, 1080, dark = false), fx.examenNuevo(), tab)
        render(Capture("exams-publicacion-1920-light-publicado.png", 1920, 1080, dark = false), fx.recienPublicado(), tab)
        // «Antes de publicar» sólo se pinta dentro de la pestaña cuando el rail no
        // está: a 1920 lo lleva el rail, a 1366 (STANDARD) y 1080 (apilado) no hay
        // columna de resumen y la lista tiene que salir aquí.
        render(Capture("exams-publicacion-1366-light-nuevo.png", 1366, 768, dark = false), fx.examenNuevo(), tab)
        render(Capture("exams-publicacion-1080-light.png", 1080, 900, dark = false), fx.borradorConCambiosSinGuardar(), tab)
    }

    // -----------------------------------------------------------------------
    // Anchos del batch, una sola vez (el contenedor es compartido)
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosAnchosDelBatch() {
        // Configuración es la pestaña que más cambia con el ancho: tarjetas en
        // fila o apiladas, resumen en columna o plegado a franja, y en 1080 el
        // régimen COMPACT con la página desplazando.
        val tab = ExamWorkspaceTabId.CONFIGURACION
        render(Capture("exams-configuracion-2560-light.png", 2560, 1440, dark = false), fx.soloRafagaPermitida(), tab)
        render(Capture("exams-configuracion-1600-light.png", 1600, 900, dark = false), fx.soloRafagaPermitida(), tab)
        render(Capture("exams-configuracion-1440-light.png", 1440, 900, dark = false), fx.soloRafagaPermitida(), tab)
        render(Capture("exams-configuracion-1080-light.png", 1080, 900, dark = false), fx.soloRafagaPermitida(), tab)
    }

    // -----------------------------------------------------------------------
    // Estados vacíos: lo que se ve casi siempre en una instalación real
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosEstadosVacios() {
        render(
            Capture("exams-1920-catalogo-sin-resultados.png", 1920, 1080, dark = false),
            fx.catalogoSinResultados(),
            ExamWorkspaceTabId.BASICA,
        )
    }

    // -----------------------------------------------------------------------
    // Motor de captura
    // -----------------------------------------------------------------------

    /**
     * Una captura y su tema, SIEMPRE explícito.
     *
     * `dark` no tiene valor por defecto a propósito. `AdminTheme` sin parámetro
     * resuelve con `isSystemInDarkTheme()`: en una máquina con el sistema en
     * oscuro, las capturas «de referencia» salían oscuras sin que nada lo dijera.
     */
    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: ExamsState, tab: ExamWorkspaceTabId) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                // Densidad 1: así 1 dp es 1 px y el PNG mide exactamente lo que
                // dice su nombre.
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    HarnessShell(state = state, tab = tab, dark = capture.dark)
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` se cargan en una corrutina que el
            // reloj de test no rastrea: sin esta espera la primera captura sale
            // con la tipografía del sistema en vez de con Manrope.
            Thread.sleep(FONT_SETTLE_MILLIS)
            waitForIdle()

            val bitmap = onRoot().captureToImage()
            val file = File(outputDir, capture.fileName)
            ImageIO.write(bitmap.toAwtImage(), "png", file)

            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    /**
     * El workspace dentro de su marco: `DesktopShell` de verdad, porque el ancho
     * de contenido —ventana menos sidebar menos márgenes— es lo que decide el
     * reparto de columnas.
     */
    @Composable
    private fun HarnessShell(state: ExamsState, tab: ExamWorkspaceTabId, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "content.exams",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno dev | vista frontend",
                title = "Exámenes",
                description = "Catálogo, preguntas y publicación de exámenes.",
                breadcrumbs = listOf("Contenido", "Exámenes"),
                primaryActionLabel = "Nuevo examen",
                onPrimaryAction = {},
                globalSearch = {},
            ) {
                ExamWorkspaceContent(
                    state = state,
                    onIntent = {},
                    onOpenQuestionBank = {},
                    initialTab = tab,
                )
            }
        }
    }
}

private const val FONT_SETTLE_MILLIS = 2_000L

// ---------------------------------------------------------------------------
// Sidebar del harness
// ---------------------------------------------------------------------------

/**
 * Los items visibles de la sidebar, transcritos de `AppNavigationCatalog.kt`.
 *
 * Se transcriben porque el catálogo vive en `composeApp` y este módulo no puede
 * —ni debe— depender de él. **No es el catálogo en vivo**: si alguien renombra
 * una sección allí, la captura enseñará el nombre antiguo.
 */
// `internal` y no `private`: lo comparten los dos bancos de pruebas visuales del
// módulo. Duplicar la sidebar en cada uno garantizaría que se desincronizaran.
internal val HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
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
