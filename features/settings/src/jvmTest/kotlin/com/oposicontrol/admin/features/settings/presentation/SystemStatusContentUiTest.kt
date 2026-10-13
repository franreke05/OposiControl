package com.oposicontrol.admin.features.settings.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.LocalDesktopBreakpoint
import com.oposicontrol.admin.core.ui.layout.desktopBreakpointFor
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lo que la pantalla de Estado garantiza en cualquier ancho y en cualquier estado, comprobado sobre el mismo
 * [SystemStatusContent] de producción (sin Koin ni red).
 */
@OptIn(ExperimentalTestApi::class)
class SystemStatusContentUiTest {
    private val fx = SystemStatusFixtures

    private fun withContent(
        width: Int,
        state: SystemStatusState,
        onIntent: (SystemStatusIntent) -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = 900) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1f),
                // El régimen de ventana lo pone `DesktopShell` en la aplicación; aquí se fija el del ancho probado.
                LocalDesktopBreakpoint provides desktopBreakpointFor(width.dp),
            ) {
                AdminTheme(darkTheme = false) {
                    SystemStatusContent(state = state, onIntent = onIntent, now = SystemStatusFixtures.NOW)
                }
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun theGuidanceIsShownExactlyOnceWhetherOrNotTheRailFits() {
        // 1920: cabe el rail. 1366: `WorkspaceLayout` lo oculta y el bloque baja a la columna central. 900: apilado.
        listOf(1920, 1366, 900).forEach { width ->
            withContent(width, fx.todoBien()) {
                onAllNodesWithText("Qué hacer si…").assertCountEquals(1)
            }
        }
        withContent(1920, fx.todoBien()) { onAllNodesWithTag(SYSTEM_STATUS_RAIL_TAG).assertCountEquals(1) }
        withContent(1366, fx.todoBien()) { onAllNodesWithTag(SYSTEM_STATUS_RAIL_TAG).assertCountEquals(0) }
    }

    @Test
    fun versionAndSessionAreShownOnceInEveryLayout() {
        listOf(1920, 1366, 900).forEach { width ->
            withContent(width, fx.todoBien()) {
                onAllNodesWithText("Versión desplegada").assertCountEquals(1)
                onAllNodesWithText("Esta sesión").assertCountEquals(1)
            }
        }
    }

    @Test
    fun refreshIsAlwaysOnScreenAndAsksForANewReading() {
        // Antes el botón solo existía en el estado de error.
        listOf(fx.todoBien(), fx.replicaCaidaConAlerta(), fx.prometheusNoResponde(), fx.errorDeRed()).forEach { state ->
            val intents = mutableListOf<SystemStatusIntent>()
            withContent(1920, state, intents::add) {
                onNodeWithTag(SYSTEM_STATUS_REFRESH_TAG).performClick()
                waitForIdle()
            }
            assertEquals(listOf<SystemStatusIntent>(SystemStatusIntent.Refresh), intents)
        }
    }

    @Test
    fun aCalmSystemSaysSoAndASilentPrometheusNeverDoes() {
        withContent(1920, fx.todoBien()) {
            onAllNodesWithText("Todo funciona con normalidad.").assertCountEquals(1)
            onAllNodesWithText("Ninguna alerta activa.").assertCountEquals(1)
        }
        withContent(1920, fx.prometheusNoResponde()) {
            onAllNodesWithText("Todo funciona con normalidad.").assertCountEquals(0)
            // Sin lectura de alertas no se afirma que no haya ninguna.
            onAllNodesWithText("Ninguna alerta activa.").assertCountEquals(0)
            onAllNodesWithText("No se puede comprobar el estado del servidor.").assertCountEquals(1)
        }
        withContent(1920, fx.errorDeRed()) {
            onAllNodesWithText("Todo funciona con normalidad.").assertCountEquals(0)
            onAllNodesWithText("No se ha podido leer el estado.").assertCountEquals(1)
        }
    }
}
