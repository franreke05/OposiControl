package com.oposicontrol.admin.features.rankings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.LeagueLevel
import com.oposicontrol.admin.domain.model.RankingEntry
import com.oposicontrol.admin.domain.model.RankingPage
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.model.UserStanding
import com.oposicontrol.admin.domain.repository.RankingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco de pruebas VISUAL de Competición: la pantalla real contra un repositorio fabricado, escrita a PNG en
 * `features/rankings/build/visual-harness/`. La captura «sin datos» es el estado real de producción hoy (ligas sembradas,
 * nadie clasificado): antes dos de sus bloques se quedaban pintando un esqueleto de carga para siempre.
 */
@OptIn(ExperimentalTestApi::class)
class RankingsVisualHarness {
    @Test
    fun capturaSinDatosYConDatos() {
        render("competicion-1920-dark-sin-datos.png", dark = true, FakeRankings(populated = false))
        render("competicion-1920-light-sin-datos.png", dark = false, FakeRankings(populated = false))
        render("competicion-1920-light-con-datos.png", dark = false, FakeRankings(populated = true))
    }

    private fun render(fileName: String, dark: Boolean, repository: RankingsRepository) {
        runDesktopComposeUiTest(width = 1920, height = 1200) {
            val viewModel = RankingsViewModel(ImmediateDispatchers, repository, NoOpAnalyticsTracker)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = dark) {
                        DesktopShell(
                            currentRouteId = "users",
                            groups = SIDEBAR,
                            onRouteSelected = {},
                            environmentLabel = "Entorno production",
                            title = "Usuarios",
                            description = "Identidad, perfil, rendimiento y competición por usuario.",
                            breadcrumbs = listOf("Usuarios"),
                            globalSearch = {},
                        ) {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                DesktopTabs(tabs = TABS, selectedId = "competition", onSelect = {})
                                RankingsScreen(viewModel)
                            }
                        }
                    }
                }
            }
            waitForIdle()
            Thread.sleep(2_000L)
            waitForIdle()
            val file = File(File("build/visual-harness").also { it.mkdirs() }, fileName)
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", file)
            assertTrue(file.exists() && file.length() > 0L)
        }
    }
}

private object ImmediateDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

/** Datos de ejemplo: nada de esto sale de un backend. */
private class FakeRankings(private val populated: Boolean) : RankingsRepository {
    private val leagues = listOf(
        LeagueLevel("l1", "liga_50", "Liga 50", 50, 50, position = 1),
        LeagueLevel("l2", "liga_200", "Liga 200", 200, 100, position = 2),
        LeagueLevel("l3", "liga_500", "Liga 500", 500, 100, position = 3),
    )
    private val entries = listOf(
        RankingEntry("u1", "Ana García", "anagarcia", "l2", "liga_200", "Liga 200", 100, 240, 8.4, 8.1, 0.3, 12, 1, 1, 99.0, "2026-10-12"),
        RankingEntry("u2", "Marta Ruiz", "martar", "l1", "liga_50", "Liga 50", 50, 80, 7.2, 7.5, -0.3, 4, 1, 2, 60.0, "2026-10-11"),
    )

    override suspend fun getLeagueLevels(): AppResult<List<LeagueLevel>> = AppResult.Success(leagues)
    override suspend fun getGlobalRanking(page: RankingPageRequest): AppResult<RankingPage> =
        AppResult.Success(if (populated) RankingPage(entries, total = entries.size) else RankingPage(total = 0))
    override suspend fun getLeagueRanking(leagueLevelId: String, page: RankingPageRequest): AppResult<RankingPage> = getGlobalRanking(page)
    override suspend fun getUserStanding(userId: String): AppResult<UserStanding> = AppResult.Success(UserStanding(userId))
    override suspend fun searchUsers(query: String, page: RankingPageRequest): AppResult<RankingPage> = AppResult.Success(RankingPage(total = 0))
}

private val TABS = listOf(
    DesktopTab("users", "Usuarios", AppIcon.USERS),
    DesktopTab("tickets", "OposiTickets", AppIcon.TICKETS),
    DesktopTab("competition", "Competición", AppIcon.RANKINGS),
    DesktopTab("access", "Accesos", AppIcon.ACCESS),
)

private val SIDEBAR = listOf(
    ShellNavigationGroup("home", "Inicio", listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key))),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
)
