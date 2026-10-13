package com.oposicontrol.admin.features.users.presentation

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
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccountStatus
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas VISUAL de Sistema > Administradores.
 *
 * Renderiza la pantalla real —el mismo [AdminsContent] de producción, dentro del mismo `DesktopShell` y `AdminTheme`, bajo
 * la fila de tres pestañas del hub de Sistema— contra estados fabricados y escribe PNG en
 * `features/users/build/visual-harness/`. **No valida datos**: ninguna línea toca un repositorio.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
@OptIn(ExperimentalTestApi::class)
class AdminsVisualHarness {
    private val fx = AdminsFixtures

    @Test
    fun capturaQuienPuedeEntrar() {
        render(Capture("administradores-1920-light-quien.png", 1920, 1080, dark = false), fx.conCuentas(), fx.superadmin())
        render(Capture("administradores-1920-dark-quien.png", 1920, 1080, dark = true), fx.conCuentas(), fx.superadmin())
        render(Capture("administradores-1366-light-quien.png", 1366, 768, dark = false), fx.conCuentas(), fx.superadmin())
        render(Capture("administradores-1920-light-lectura-antigua.png", 1920, 1080, dark = false), fx.relecturaFallida(), fx.superadmin())
    }

    @Test
    fun capturaLosEstados() {
        render(Capture("administradores-1920-light-error.png", 1920, 1080, dark = false), fx.caida(), fx.sinPermiso())
        render(Capture("administradores-1920-light-cargando.png", 1920, 1080, dark = false), fx.cargando(), fx.sinRespuesta())
        render(Capture("administradores-1920-dark-vacio.png", 1920, 1080, dark = true), fx.vacio(), fx.sinPermiso())
    }

    @Test
    fun capturaLasSolicitudes() {
        val requests = AdminsSection.REQUESTS
        render(Capture("administradores-1920-light-sin-permiso.png", 1920, 1080, dark = false), fx.conCuentas(), fx.sinPermiso(), requests)
        render(Capture("administradores-1920-dark-sin-permiso.png", 1920, 1080, dark = true), fx.conCuentas(), fx.sinPermiso(), requests)
        render(Capture("administradores-1920-light-solicitudes.png", 1920, 1080, dark = false), fx.conCuentas(), fx.superadmin(), requests)
        render(Capture("administradores-1920-light-solicitudes-error.png", 1920, 1080, dark = false), fx.conCuentas(), fx.sinRespuestaPorError(), requests)
    }

    @Test
    fun capturaLaTablaDeRoles() {
        val roles = AdminsSection.ROLES
        render(Capture("administradores-1920-light-roles.png", 1920, 1080, dark = false), fx.conCuentas(), fx.superadmin(), roles)
        render(Capture("administradores-1920-dark-roles.png", 1920, 1080, dark = true), fx.conCuentas(), fx.superadmin(), roles)
        render(Capture("administradores-1366-light-roles.png", 1366, 768, dark = false), fx.conCuentas(), fx.sinPermiso(), roles)
    }

    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(
        capture: Capture,
        state: AdminsState,
        accessState: AccessRequestsState,
        section: AdminsSection = AdminsSection.WHO,
    ) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                // Densidad 1: 1 dp es 1 px y el PNG mide exactamente lo que dice su nombre.
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    HarnessShell(state = state, accessState = accessState, section = section, dark = capture.dark)
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` cargan en una corrutina que el reloj de test no rastrea.
            Thread.sleep(FONT_SETTLE_MILLIS)
            waitForIdle()

            val file = File(outputDir, capture.fileName)
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", file)
            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    @Composable
    private fun HarnessShell(state: AdminsState, accessState: AccessRequestsState, section: AdminsSection, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "system.settings",
                groups = SYSTEM_HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Sistema",
                description = "Estado, administradores y auditoría del panel.",
                breadcrumbs = listOf("Sistema"),
                globalSearch = {},
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    // Las tres pestañas del hub, con el mismo aviso de pendientes que pinta `SystemHubScreen`.
                    DesktopTabs(tabs = systemHubTabsForHarness(accessState.pendingCount), selectedId = "admins", onSelect = {})
                    AdminsContent(
                        state = state,
                        accessState = accessState,
                        section = section,
                        onSectionChange = {},
                        onIntent = {},
                        onAccessIntent = {},
                        onOpenUser = {},
                    )
                }
            }
        }
    }
}

private const val FONT_SETTLE_MILLIS = 2_000L

/** Estados fabricados. Los nombres y las cuentas son de ejemplo: nada de esto sale de un backend. */
internal object AdminsFixtures {
    private val accounts = listOf(
        AdminAccount("u-5", "Lucía Fernández", "lucia.f", AdminLevel.SUPERADMIN, AccountStatus.ACTIVE),
        AdminAccount("u-2", "Marta Ruiz Peña", "mruiz", AdminLevel.ADMIN, AccountStatus.ACTIVE),
        AdminAccount("u-9", "Óscar Beltrán", "obeltran", AdminLevel.ADMIN, AccountStatus.BLOCKED),
        // Sin nombre y sin estado informado: se dice que faltan, no se rellenan.
        AdminAccount("u-11", USER_NAME_MISSING, "soporte_02", AdminLevel.ADMIN, AccountStatus.UNKNOWN),
    )

    fun conCuentas() = AdminsState(accounts = UiAsync.Success(accounts))
    fun relecturaFallida() = conCuentas().copy(refreshFailed = true)
    fun caida() = AdminsState(accounts = UiAsync.Error(AppError.Network("timeout")))
    fun cargando() = AdminsState()
    fun vacio() = AdminsState(accounts = UiAsync.Empty)

    private val requests = listOf(
        AccessRequest(
            id = "r-1",
            userId = "u-21",
            name = "Ada Lovelace",
            email = "ada@example.com",
            reason = "Coordino el equipo de contenido de Enfermería.",
            status = AccessRequestStatus.PENDING,
            createdAt = "2026-10-12T09:00:00Z",
        ),
        AccessRequest(
            id = "r-2",
            userId = "u-2",
            name = "Marta Ruiz Peña",
            email = "marta@example.com",
            reason = "Voy a encargarme de las noticias.",
            status = AccessRequestStatus.APPROVED,
            createdAt = "2026-10-01T09:00:00Z",
            resolvedAt = "2026-10-02T09:00:00Z",
        ),
    )

    /** El servidor dejó leer la lista: quien mira es superadministrador. */
    fun superadmin() = AccessRequestsState(
        availability = AccessReviewAvailability.ALLOWED,
        allRequests = requests,
        requests = UiAsync.Success(requests),
        selectedId = "r-1",
    )

    /** El servidor respondió 403 `SUPERADMIN_REQUIRED`. */
    fun sinPermiso() = AccessRequestsState(availability = AccessReviewAvailability.DENIED)

    /** Todavía no ha respondido. */
    fun sinRespuesta() = AccessRequestsState(requests = UiAsync.Loading)

    /** No se pudo preguntar: ni «sí» ni «no». */
    fun sinRespuestaPorError() = AccessRequestsState(requests = UiAsync.Error(AppError.Network("timeout")))
}

/** Espejo de `systemHubTabs` (vive en `composeApp`, del que este módulo no depende). */
internal fun systemHubTabsForHarness(pendingAccessRequests: Int): List<DesktopTab> = listOf(
    DesktopTab("status", "Estado", AppIcon.STATUS),
    DesktopTab("admins", "Administradores", AppIcon.ROLES, badge = pendingAccessRequests.takeIf { it > 0 }?.toString()),
    DesktopTab("audit", "Auditoría", AppIcon.AUDIT),
)

/** Menú lateral abreviado: basta para que la pantalla se vea dentro de su marco real. */
internal val SYSTEM_HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
    ShellNavigationGroup("home", "Inicio", listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key))),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
    ShellNavigationGroup("system", "Sistema", listOf(ShellNavigationItem("system.settings", "Sistema", AppIcon.SETTINGS.key))),
)
