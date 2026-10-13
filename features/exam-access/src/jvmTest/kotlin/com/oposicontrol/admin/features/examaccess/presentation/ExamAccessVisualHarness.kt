package com.oposicontrol.admin.features.examaccess.presentation

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
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas VISUAL de Accesos.
 *
 * Renderiza la pantalla real —el mismo [ExamAccessContent] de producción, dentro del mismo `DesktopShell` y `AdminTheme`—
 * contra estados fabricados y escribe PNG en `features/exam-access/build/visual-harness/`. **No valida datos**: ninguna
 * línea toca un repositorio.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
@OptIn(ExperimentalTestApi::class)
class ExamAccessVisualHarness {
    private val fx = ExamAccessFixtures

    @Test
    fun capturaElCatalogoYLaFicha() {
        render(Capture("accesos-1920-light-detalle.png", 1920, 1080, dark = false), fx.detalleVigente())
        render(Capture("accesos-1920-dark-detalle.png", 1920, 1080, dark = true), fx.detalleVigente())
        render(Capture("accesos-1920-light-retirado.png", 1920, 1080, dark = false), fx.detalleRetirado())
        render(Capture("accesos-1920-light-compra.png", 1920, 1080, dark = false), fx.detalleCompra())
        render(Capture("accesos-1920-light-confirmar-retirada.png", 1920, 1080, dark = false), fx.confirmandoRetirada())
    }

    @Test
    fun capturaConceder() {
        render(Capture("accesos-1920-light-conceder-vacio.png", 1920, 1200, dark = false), fx.concederEnBlanco())
        render(Capture("accesos-1920-light-conceder-buscando.png", 1920, 1400, dark = false), fx.concederConResultados())
        render(Capture("accesos-1920-light-conceder-listo.png", 1920, 1080, dark = false), fx.concederListo())
        render(Capture("accesos-1920-dark-conceder-listo.png", 1920, 1080, dark = true), fx.concederListo())
    }

    @Test
    fun capturaLosEstados() {
        render(Capture("accesos-1920-dark-vacio.png", 1920, 1080, dark = true), fx.sinAccesos())
        render(Capture("accesos-1920-light-cargando.png", 1920, 1080, dark = false), fx.cargando())
        render(Capture("accesos-1920-light-error.png", 1920, 1080, dark = false), fx.caida())
        render(Capture("accesos-1366-light-detalle.png", 1366, 768, dark = false), fx.detalleVigente())
    }

    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: ExamAccessState) {
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
    private fun HarnessShell(state: ExamAccessState, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
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
                    DesktopTabs(tabs = USERS_HUB_TABS, selectedId = "access", onSelect = {})
                    ExamAccessContent(state = state, onIntent = {})
                }
            }
        }
    }
}

private const val FONT_SETTLE_MILLIS = 2_000L

/** Estados fabricados. Los nombres son de ejemplo: nada de esto sale de un backend. */
internal object ExamAccessFixtures {
    private val rows = listOf(
        ExamAccessItem("a1", "Ana García", "Simulacro 3 · TCAE", "admin", "active", "u1", "e1", "2026-10-01T09:00:00+00:00"),
        ExamAccessItem("a2", "Marta Ruiz Peña", "Examen oficial SAS 2023", "regalo", "active", "u2", "e2", "2026-09-22T09:00:00+00:00"),
        ExamAccessItem(
            "a3", "Javier Ortega", "Simulacro 3 · TCAE", "promocion", "inactive", "u3", "e1",
            "2026-08-02T09:00:00+00:00", "2026-09-30T18:00:00+00:00",
        ),
        ExamAccessItem("a4", "Lucía Fernández", "Ráfaga de farmacología", "compra", "active", "u4", "e3", "2026-09-10T09:00:00+00:00"),
    )
    private val people = listOf(
        AccessUserOption("u1", "Ana García", "@anagarcia"),
        AccessUserOption("u5", "Ana Belén Soto", "@anabelen"),
        AccessUserOption("u6", "Anabel Ríos", "@anabel.rios"),
    )
    private val exams = (1..11).map { AccessExamOption("e$it", "Simulacro $it · TCAE", "TCAE · premium") } +
        AccessExamOption("e20", "Examen oficial SAS 2023", "Enfermería · premium")

    private fun base(selected: String? = "a1") = ExamAccessState(accesses = UiAsync.Success(rows), allAccesses = rows, selectedId = selected)

    fun detalleVigente() = base()
    fun detalleRetirado() = base("a3")
    fun detalleCompra() = base("a4")
    fun confirmandoRetirada() = base().copy(confirmingRevoke = true)
    fun concederEnBlanco() = base(null).copy(creating = true, exams = UiAsync.Success(exams))
    fun concederConResultados() = concederEnBlanco().copy(userQuery = "ana", userResults = UiAsync.Success(people), examQuery = "simulacro")
    fun concederListo() = concederEnBlanco().copy(pickedUser = people[0], pickedExam = exams.last(), reason = "regalo")
    fun sinAccesos() = ExamAccessState(accesses = UiAsync.Empty)
    fun cargando() = ExamAccessState()
    fun caida() = ExamAccessState(accesses = UiAsync.Error(AppError.Network("timeout")))
}

internal val USERS_HUB_TABS: List<DesktopTab> = listOf(
    DesktopTab("users", "Usuarios", AppIcon.USERS),
    DesktopTab("tickets", "OposiTickets", AppIcon.TICKETS),
    DesktopTab("competition", "Competición", AppIcon.RANKINGS),
    DesktopTab("access", "Accesos", AppIcon.ACCESS),
)

/** Menú lateral abreviado: basta para que la pantalla se vea dentro de su marco real. */
internal val HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
    ShellNavigationGroup("home", "Inicio", listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key))),
    ShellNavigationGroup(
        "content",
        "Contenido",
        listOf(
            ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key),
            ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key),
        ),
    ),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
)
