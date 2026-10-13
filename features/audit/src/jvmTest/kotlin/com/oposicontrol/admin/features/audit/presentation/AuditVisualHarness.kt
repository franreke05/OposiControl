package com.oposicontrol.admin.features.audit.presentation

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
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditField
import com.oposicontrol.admin.domain.model.AuditMovement
import kotlinx.datetime.TimeZone
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas VISUAL de Auditoría.
 *
 * Renderiza la pantalla real —el mismo [AuditContent] de producción, dentro del mismo `DesktopShell` y `AdminTheme`—
 * contra estados fabricados y escribe PNG en `features/audit/build/visual-harness/`. **No valida datos**: ninguna línea
 * toca un repositorio.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
@OptIn(ExperimentalTestApi::class)
class AuditVisualHarness {
    private val fx = AuditFixtures

    @Test
    fun capturaElCatalogoYLaFicha() {
        render(Capture("auditoria-1920-light-cambios.png", 1920, 1080, dark = false), fx.conCambios())
        render(Capture("auditoria-1920-dark-cambios.png", 1920, 1080, dark = true), fx.conCambios())
        render(Capture("auditoria-1920-light-sin-cargar.png", 1920, 1080, dark = false), fx.cambiosSinCargar())
        render(Capture("auditoria-1920-light-sistema-sin-cambios.png", 1920, 1080, dark = false), fx.sistemaSinCambios())
        render(Capture("auditoria-1920-light-cambios-error.png", 1920, 1080, dark = false), fx.cambiosCaidos())
    }

    @Test
    fun capturaLosEstados() {
        render(Capture("auditoria-1920-light-filtros-sin-resultados.png", 1920, 1080, dark = false), fx.filtrosSinResultados())
        render(Capture("auditoria-1920-dark-vacio.png", 1920, 1080, dark = true), fx.vacio())
        render(Capture("auditoria-1920-light-cargando.png", 1920, 1080, dark = false), fx.cargando())
        render(Capture("auditoria-1920-light-error.png", 1920, 1080, dark = false), fx.caida())
        render(Capture("auditoria-1366-light-cambios.png", 1366, 768, dark = false), fx.conCambios())
    }

    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: AuditState) {
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
    private fun HarnessShell(state: AuditState, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "system.settings",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Sistema",
                description = "Estado, administradores y auditoría del panel.",
                breadcrumbs = listOf("Sistema"),
                globalSearch = {},
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    DesktopTabs(tabs = SYSTEM_HUB_TABS, selectedId = "audit", onSelect = {})
                    // Zona fija: las horas de las capturas no dependen de dónde se ejecute el banco.
                    AuditContent(state = state, onIntent = {}, zone = AuditFixtures.ZONE)
                }
            }
        }
    }
}

private const val FONT_SETTLE_MILLIS = 2_000L

/** Estados fabricados. Los nombres, identificadores y motivos son de ejemplo: nada de esto sale de un backend. */
internal object AuditFixtures {
    val ZONE: TimeZone = TimeZone.of("Europe/Madrid")

    private const val ANA = "5a5a5a5a-0000-4000-8000-000000000001"
    private const val MARTA = "5a5a5a5a-0000-4000-8000-000000000002"
    private const val SIN_PERFIL = "5a5a5a5a-0000-4000-8000-000000000003"

    private fun record(n: Int) = "7b7b7b7b-0000-4000-8000-%012d".format(n)

    private val rows = listOf(
        AuditMovement("m1", ANA, "Ana García", "cuenta_dada_de_baja", "perfiles_usuario", record(1), "2026-10-13T12:34:56.120000+00:00"),
        AuditMovement("m2", ANA, "Ana García", "roles_sincronizados", "roles_usuario", record(2), "2026-10-13T11:02:10+00:00"),
        AuditMovement("m3", MARTA, "Marta Ruiz Peña", "acceso_examen_concedido", "contenido_desbloqueado_usuario", record(3), "2026-10-13T09:15:00+00:00"),
        AuditMovement("m4", null, null, "lote_importado", "lotes_importacion_preguntas", record(4), "2026-10-12T22:40:31+00:00"),
        AuditMovement("m5", SIN_PERFIL, null, "producto_comercial_eliminado", "productos_comerciales", record(5), "2026-10-12T17:05:00+00:00"),
        AuditMovement("m6", MARTA, "Marta Ruiz Peña", "noticia_publicada", "noticia", record(6), "2026-10-11T08:00:00+00:00"),
        // Un código que este panel no conoce: se enseña tal cual.
        AuditMovement("m7", ANA, "Ana García", "accion_de_una_version_futura", "tabla_nueva", null, "2026-10-10T16:20:00+00:00"),
    )

    private val changes = AuditDetail(
        id = "m1",
        before = listOf(AuditField("estado_cuenta", "activa")),
        after = listOf(
            AuditField("estado_cuenta", "bloqueada"),
            AuditField("motivo", "Compartía su cuenta con varias personas. Se le avisó dos veces por soporte y no cambió la contraseña."),
        ),
    )

    private fun base(selected: String? = "m1") = AuditState(
        movements = UiAsync.Success(rows),
        allMovements = rows,
        nextCursor = "siguiente",
        actorOptions = auditActorOptions(rows),
        selectedId = selected,
    )

    fun conCambios() = base().copy(detail = UiAsync.Success(changes))
    fun cambiosSinCargar() = base("m3")
    fun sistemaSinCambios() = base("m4").copy(detail = UiAsync.Success(AuditDetail("m4", before = null, after = null)))
    fun cambiosCaidos() = base("m5").copy(detail = UiAsync.Error(AppError.Network("timeout")))
    fun filtrosSinResultados() = AuditState(
        movements = UiAsync.Empty,
        actionFilter = "cuenta_reactivada",
        actorFilter = ANA,
        fromDate = "2026-10-01",
        untilDate = "2026-10-13",
        actorOptions = auditActorOptions(rows),
    )
    fun vacio() = AuditState(movements = UiAsync.Empty)
    fun cargando() = AuditState()
    fun caida() = AuditState(movements = UiAsync.Error(AppError.Network("timeout")))
}

internal val SYSTEM_HUB_TABS: List<DesktopTab> = listOf(
    DesktopTab("status", "Estado", AppIcon.STATUS),
    DesktopTab("admins", "Administradores", AppIcon.ROLES),
    DesktopTab("audit", "Auditoría", AppIcon.AUDIT),
)

/** Menú lateral abreviado: basta para que la pantalla se vea dentro de su marco real. */
internal val HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
    ShellNavigationGroup("home", "Inicio", listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key))),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
    ShellNavigationGroup("system", "Sistema", listOf(ShellNavigationItem("system.settings", "Sistema", AppIcon.SETTINGS.key))),
)
