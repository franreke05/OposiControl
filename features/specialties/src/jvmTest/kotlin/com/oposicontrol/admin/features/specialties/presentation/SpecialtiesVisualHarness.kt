package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
 * Banco de pruebas VISUAL del workspace de Especialidades.
 *
 * **Qué es y qué no es.** Renderiza la pantalla real —el mismo
 * [SpecialtiesContent] que corre en producción, dentro del mismo `DesktopShell`
 * y del mismo `AdminTheme`— contra un `SpecialtiesState` fabricado, y escribe
 * PNG. Sirve para revisar cristal, fondo, jerarquía, densidad y responsive **sin
 * levantar Ktor**.
 *
 * **NO valida datos.** Ni una línea de este fichero toca un repositorio, una
 * ruta ni un contrato. Que una captura se vea bien no dice nada sobre si la
 * pantalla lee bien de Supabase; eso sigue sin verificar y hay que decirlo así.
 *
 * **Nombres de captura**: `specialties-<pestaña o estado>-<ancho>-<tema>.png`,
 * con el tema SIEMPRE explícito. Se escriben en
 * `features/specialties/build/visual-harness/`.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por UP-TO-DATE y no
 * reescribe ni un PNG.
 */
@OptIn(ExperimentalTestApi::class)
class SpecialtiesVisualHarness {

    private val fx = SpecialtiesFixtures

    // -----------------------------------------------------------------------
    // Los cuatro anchos canónicos, en los dos temas
    //
    // 1920 y 1600 son los dos monitores reales del objetivo; 1366 es el portátil
    // y 1080 el régimen apilado, donde la página desplaza y el rail deja de ser
    // columna.
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosCuatroAnchosEnLosDosTemas() {
        ANCHOS.forEach { (width, height) ->
            render(Capture("specialties-identidad-$width-light.png", width, height, dark = false), fx.especialidadAbierta(), SpecialtyTabId.IDENTIDAD)
            render(Capture("specialties-identidad-$width-dark.png", width, height, dark = true), fx.especialidadAbierta(), SpecialtyTabId.IDENTIDAD)
            render(Capture("specialties-tipos-$width-light.png", width, height, dark = false), fx.especialidadAbierta(), SpecialtyTabId.TIPOS)
            render(Capture("specialties-tipos-$width-dark.png", width, height, dark = true), fx.especialidadAbierta(), SpecialtyTabId.TIPOS)
            render(Capture("specialties-impacto-$width-light.png", width, height, dark = false), fx.especialidadAbierta(), SpecialtyTabId.IMPACTO)
            render(Capture("specialties-impacto-$width-dark.png", width, height, dark = true), fx.especialidadAbierta(), SpecialtyTabId.IMPACTO)
        }
    }

    // -----------------------------------------------------------------------
    // Los estados que hay que poder mirar
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosEstadosDeCarga() {
        render(Capture("specialties-carga-1920-light.png", 1920, 1080, dark = false), fx.cargando(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-carga-1920-dark.png", 1920, 1080, dark = true), fx.cargando(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-vacio-1920-light.png", 1920, 1080, dark = false), fx.vacio(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-vacio-1920-dark.png", 1920, 1080, dark = true), fx.vacio(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-error-1920-light.png", 1920, 1080, dark = false), fx.error(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-error-1920-dark.png", 1920, 1080, dark = true), fx.error(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-sin-seleccion-1920-light.png", 1920, 1080, dark = false), fx.sinSeleccion(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-sin-seleccion-1920-dark.png", 1920, 1080, dark = true), fx.sinSeleccion(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-sin-resultados-1920-light.png", 1920, 1080, dark = false), fx.catalogoSinResultados(), SpecialtyTabId.IDENTIDAD)
    }

    @Test
    fun capturaLosCambiosSinGuardar() {
        // El estado que justifica la barra prominente y la enumeración de las dos
        // escrituras, en los cuatro anchos: es lo que más cambia de forma al
        // estrecharse la ventana.
        ANCHOS.forEach { (width, height) ->
            render(Capture("specialties-cambios-sin-guardar-$width-light.png", width, height, dark = false), fx.cambiosSinGuardar(), SpecialtyTabId.IDENTIDAD)
            render(Capture("specialties-cambios-sin-guardar-$width-dark.png", width, height, dark = true), fx.cambiosSinGuardar(), SpecialtyTabId.IDENTIDAD)
        }
        render(Capture("specialties-cambios-sin-guardar-tipos-1920-light.png", 1920, 1080, dark = false), fx.cambiosSinGuardar(), SpecialtyTabId.TIPOS)
        render(Capture("specialties-cambios-sin-guardar-impacto-1920-light.png", 1920, 1080, dark = false), fx.cambiosSinGuardar(), SpecialtyTabId.IMPACTO)
    }

    @Test
    fun capturaLosEstadosDeTipos() {
        val tab = SpecialtyTabId.TIPOS
        render(Capture("specialties-tipos-sin-abiertos-1920-light.png", 1920, 1080, dark = false), fx.sinTiposAbiertos(), tab)
        render(Capture("specialties-tipos-sin-abiertos-1920-dark.png", 1920, 1080, dark = true), fx.sinTiposAbiertos(), tab)
        render(Capture("specialties-tipos-apagado-con-examenes-1920-light.png", 1920, 1080, dark = false), fx.tipoApagadoConExamenes(), tab)
        render(Capture("specialties-tipos-sin-configurar-1920-light.png", 1920, 1080, dark = false), fx.sinConfigurar(), tab)
        render(Capture("specialties-tipos-capacidades-caidas-1920-light.png", 1920, 1080, dark = false), fx.capacidadesCaidas(), tab)
        render(Capture("specialties-tipos-nueva-1920-light.png", 1920, 1080, dark = false), fx.nuevaEspecialidad(), tab)
        render(Capture("specialties-tipos-1366-light.png", 1366, 768, dark = false), fx.sinTiposAbiertos(), tab)
    }

    @Test
    fun capturaLosEstadosDeImpacto() {
        val tab = SpecialtyTabId.IMPACTO
        render(Capture("specialties-impacto-apagado-con-examenes-1920-light.png", 1920, 1080, dark = false), fx.tipoApagadoConExamenes(), tab)
        render(Capture("specialties-impacto-apagado-con-examenes-1920-dark.png", 1920, 1080, dark = true), fx.tipoApagadoConExamenes(), tab)
        render(Capture("specialties-impacto-examenes-caidos-1920-light.png", 1920, 1080, dark = false), fx.examenesCaidos(), tab)
        render(Capture("specialties-impacto-1080-light.png", 1080, 900, dark = false), fx.tipoApagadoConExamenes(), tab)
    }

    @Test
    fun capturaLaIdentidadYSuZonaDePeligro() {
        val tab = SpecialtyTabId.IDENTIDAD
        render(Capture("specialties-identidad-nueva-1920-light.png", 1920, 1080, dark = false), fx.nuevaEspecialidad(), tab)
        render(Capture("specialties-identidad-archivada-1920-light.png", 1920, 1080, dark = false), fx.archivada(), tab)
        render(Capture("specialties-identidad-archivada-1920-dark.png", 1920, 1080, dark = true), fx.archivada(), tab)
        render(Capture("specialties-solo-archivadas-1920-light.png", 1920, 1080, dark = false), fx.soloArchivadas(), tab)
    }

    // -----------------------------------------------------------------------
    // Diálogos
    //
    // Un `Dialog` abre una SEGUNDA raíz de composición, así que la captura tiene
    // que tomar el último índice y no `onRoot()`, que devolvería la página de
    // detrás sin el diálogo encima.
    // -----------------------------------------------------------------------

    @Test
    fun capturaLosDialogos() {
        render(Capture("specialties-dialogo-archivar-1920-light.png", 1920, 1080, dark = false), fx.dialogoArchivar(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-dialogo-archivar-1920-dark.png", 1920, 1080, dark = true), fx.dialogoArchivar(), SpecialtyTabId.IDENTIDAD)
        render(Capture("specialties-dialogo-cambiar-de-fila-1920-light.png", 1920, 1080, dark = false), fx.dialogoCambiarDeFila(), SpecialtyTabId.IDENTIDAD)
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

    private fun render(capture: Capture, state: SpecialtiesState, tab: SpecialtyTabId) {
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

            // El ÚLTIMO nodo raíz: con un diálogo abierto hay dos, y el de arriba
            // es el que lleva el diálogo.
            val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
            val bitmap = onAllNodes(isRoot())[roots.lastIndex].captureToImage()
            val file = File(outputDir, capture.fileName)
            ImageIO.write(bitmap.toAwtImage(), "png", file)

            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    /**
     * La pantalla dentro de su marco: `DesktopShell` de verdad, porque el ancho
     * de contenido —ventana menos sidebar menos márgenes— es lo que decide el
     * reparto de columnas.
     */
    @Composable
    private fun HarnessShell(state: SpecialtiesState, tab: SpecialtyTabId, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "content.taxonomy",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno dev | vista frontend",
                title = "Especialidades",
                description = "Especialidades, comunidades, etiquetas y modos disponibles.",
                breadcrumbs = listOf("Contenido", "Especialidades"),
                primaryActionLabel = "Nueva especialidad",
                onPrimaryAction = {},
                globalSearch = {},
            ) {
                SpecialtiesContent(
                    state = state,
                    onIntent = {},
                    initialTab = tab,
                )
            }
        }
    }

    private companion object {
        /** Los cuatro anchos canónicos del proyecto, con su alto real. */
        val ANCHOS: List<Pair<Int, Int>> = listOf(
            1920 to 1080,
            1600 to 900,
            1366 to 768,
            1080 to 900,
        )
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
 *
 * Es la misma transcripción que usa el banco de Exámenes, con «Especialidades»
 * (`content.taxonomy`) añadida, que es la ruta que esta pantalla ocupa y que
 * aquélla no necesitaba.
 */
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
            ShellNavigationItem("content.taxonomy", "Especialidades", AppIcon.LAYERS.key),
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
