package com.oposicontrol.admin.features.settings.presentation

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
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.DeployedVersion
import com.oposicontrol.admin.domain.model.SupabaseProjectionBasis
import com.oposicontrol.admin.domain.model.SupabaseQuota
import com.oposicontrol.admin.domain.model.SupabaseQuotaTarget
import com.oposicontrol.admin.domain.model.SystemAlert
import com.oposicontrol.admin.domain.model.SystemMetric
import com.oposicontrol.admin.domain.model.SystemReplica
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import kotlinx.datetime.Instant
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Banco de pruebas VISUAL de la pestaña Estado.
 *
 * Renderiza la pantalla real —el mismo [SystemStatusContent] de producción, dentro del mismo `DesktopShell` y
 * `AdminTheme`— contra estados fabricados y escribe PNG en `features/settings/build/visual-harness/`. **No valida
 * datos**: ninguna línea toca un repositorio.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
@OptIn(ExperimentalTestApi::class)
class SystemStatusVisualHarness {
    private val fx = SystemStatusFixtures

    @Test
    fun capturaTodoBien() {
        render(Capture("estado-1920-light-todo-bien.png", 1920, 1080, dark = false), fx.todoBien())
        render(Capture("estado-1920-dark-todo-bien.png", 1920, 1080, dark = true), fx.todoBien())
        render(Capture("estado-1366-light-todo-bien.png", 1366, 1500, dark = false), fx.todoBien())
    }

    @Test
    fun capturaLasIncidencias() {
        render(Capture("estado-1920-light-replica-caida.png", 1920, 1200, dark = false), fx.replicaCaidaConAlerta())
        render(Capture("estado-1920-light-prometheus-mudo.png", 1920, 1080, dark = false), fx.prometheusNoResponde())
        render(Capture("estado-1920-light-cuota-80.png", 1920, 1200, dark = false), fx.cuotaAlOchenta())
        render(Capture("estado-1920-dark-cuota-80.png", 1920, 1200, dark = true), fx.cuotaAlOchenta())
    }

    @Test
    fun capturaLosEstados() {
        render(Capture("estado-1920-light-error-red.png", 1920, 1080, dark = false), fx.errorDeRed())
        render(Capture("estado-1920-light-cargando.png", 1920, 1080, dark = false), fx.cargando())
    }

    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: SystemStatusState) {
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

            val file = File(outputDir, capture.fileName)
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", file)
            assertTrue(file.exists() && file.length() > 0L, "No se escribió ${file.absolutePath}")
        }
    }

    private val outputDir: File by lazy { File("build/visual-harness").also { it.mkdirs() } }

    @Composable
    private fun HarnessShell(state: SystemStatusState, dark: Boolean) {
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
                    DesktopTabs(tabs = SYSTEM_HUB_TABS, selectedId = "status", onSelect = {})
                    // Reloj fijo: los «hace…» de las capturas no dependen de cuándo se ejecute el banco.
                    SystemStatusContent(state = state, onIntent = {}, now = SystemStatusFixtures.NOW)
                }
            }
        }
    }
}

private const val FONT_SETTLE_MILLIS = 2_000L

/** Estados fabricados. Los nombres, versiones y cifras son de ejemplo: nada de esto sale de un backend. */
internal object SystemStatusFixtures {
    val NOW: Instant = Instant.parse("2026-10-13T12:00:00Z")

    private const val SHA = "886dac044b6e"

    private val replicas = listOf(
        SystemReplica("bot-1", "bot", up = true, seesSupabase = true, version = SHA, startedAt = NOW - 3.days - 2.hours),
        SystemReplica("bot-2", "bot", up = true, seesSupabase = true, version = SHA, startedAt = NOW - 3.days - 2.hours),
        SystemReplica("control-1", "control", up = true, seesSupabase = true, version = SHA, startedAt = NOW - 5.hours),
    )

    private val quota = SupabaseQuota(
        bytes24h = 41_234_568,
        bytes7d = 252_000_000,
        projectedMonthlyBytes = 1_080_000_000,
        projectionBasis = SupabaseProjectionBasis.SEVEN_DAYS,
        monthlyLimitBytes = 5_000_000_000,
        byTarget = listOf(
            SupabaseQuotaTarget("auth", 1_234_568, 9_000_000),
            SupabaseQuotaTarget("rest", 38_000_000, 231_000_000),
            SupabaseQuotaTarget("storage", 2_000_000, 12_000_000),
        ),
    )

    private val healthy = SystemStatus(
        overallStatus = SystemStatusLevel.OPERATIVE,
        ktorStatus = SystemStatusLevel.OPERATIVE,
        supabaseStatus = SystemStatusLevel.OPERATIVE,
        cpuPercent = 12.4,
        ramPercent = 41.25,
        supabaseDiskPercent = 3.5632,
        apiLatencyMs = 86,
        errors24h = 0,
        updatedAt = NOW - 20.seconds,
        replicas = replicas,
        alerts = emptyList(),
        supabaseQuota = quota,
        deployedVersion = DeployedVersion(sha = SHA, replicasDiffer = false),
        prometheusResponding = true,
    )

    private val session = AdminSessionInfo("u-1", "Fran Rekena", listOf("admin", "superadmin"), isSuperAdmin = true, isAdmin = true)

    private fun stateOf(status: SystemStatus, readAgo: kotlin.time.Duration = 20.seconds) = SystemStatusState(
        uiState = SystemStatusUiState.Content(status, readAt = NOW - readAgo),
        session = UiAsync.Success(session),
        environmentLabel = "Producción",
        panelVersion = "1.0.0",
    )

    fun todoBien() = stateOf(healthy)

    fun replicaCaidaConAlerta() = stateOf(
        healthy.copy(
            overallStatus = SystemStatusLevel.DEGRADED,
            ktorStatus = SystemStatusLevel.DEGRADED,
            replicas = listOf(
                replicas[0],
                SystemReplica("bot-2", "bot", up = false),
                // En pie con otra versión: además de la caída, un despliegue a medias.
                replicas[2].copy(version = "0123456789ab", startedAt = NOW - 12.minutes),
            ),
            alerts = listOf(
                SystemAlert("KtorInstanceDown", severity = "critical", replica = "bot-2"),
                SystemAlert("KtorBotPoolDegraded", severity = "warning"),
            ),
            deployedVersion = DeployedVersion(sha = SHA, replicasDiffer = true),
        ),
        readAgo = 4.minutes,
    )

    fun prometheusNoResponde() = stateOf(
        SystemStatus(
            overallStatus = SystemStatusLevel.UNKNOWN,
            ktorStatus = SystemStatusLevel.UNKNOWN,
            supabaseStatus = SystemStatusLevel.OPERATIVE,
            supabaseDiskPercent = 3.5632,
            updatedAt = NOW,
            prometheusResponding = false,
            notInstrumented = setOf(SystemMetric.CPU, SystemMetric.MEMORY, SystemMetric.API_LATENCY, SystemMetric.API_ERRORS),
        ),
    )

    fun cuotaAlOchenta() = stateOf(
        healthy.copy(
            alerts = listOf(SystemAlert("SupabaseEgressDailyBudgetExceeded", severity = "critical")),
            supabaseQuota = quota.copy(
                bytes24h = 171_000_000,
                bytes7d = 933_333_333,
                projectedMonthlyBytes = 4_000_000_000,
                byTarget = listOf(
                    SupabaseQuotaTarget("auth", 3_000_000, 21_000_000),
                    SupabaseQuotaTarget("rest", 160_000_000, 870_333_333),
                    // Sin una semana de serie para este destino: se dice, no se pinta un cero.
                    SupabaseQuotaTarget("storage", 8_000_000, null),
                ),
            ),
        ),
    )

    fun errorDeRed() = SystemStatusState(
        uiState = SystemStatusUiState.Error(AppError.Network("Unable to reach the server")),
        session = UiAsync.Error(AppError.Network("Unable to reach the server")),
        environmentLabel = "Producción",
        panelVersion = "1.0.0",
    )

    fun cargando() = SystemStatusState(refreshing = true, environmentLabel = "Producción", panelVersion = "1.0.0")
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
