package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardMetricKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Lo que la Fase 2 añade a la pantalla, comprobado donde se ve: el atajo de examen
 * nuevo (PC-15), la marca de datos desactualizados (PC-10) y la cifra de una lectura
 * fallida (PC-04). Las reglas que hay detrás tienen sus pruebas puras; aquí se
 * comprueba que llegan a dibujarse.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardV2ContentUiTest {

    private val now = Instant.fromEpochSeconds(1_756_000_000L)

    @Test
    fun elAtajoNuevoExamenAbreElBorradorYNoElCatalogo() = runComposeUiTest {
        val opened = mutableListOf<DashboardV2Destination>()
        setContent { panel(DashboardV2State(), onOpen = { opened += it }) }

        onNodeWithText(NEW_EXAM_QUICK_ACTION_LABEL).performClick()

        assertEquals(listOf(DashboardV2Destination.NEW_EXAM), opened)
    }

    @Test
    fun unaFranjaConDatosDeAntesDeUnFalloLoDice() = runComposeUiTest {
        val state = DashboardV2State(
            blocks = mapOf(DashboardBlock.ECONOMY to UiAsync.Success(economyReport())),
            stale = setOf(DashboardBlock.ECONOMY),
        )
        setContent { panel(state) }

        assertEquals(1, onAllNodesWithText(DASHBOARD_STALE_LABEL).fetchSemanticsNodes().size)
    }

    @Test
    fun unaFranjaConDatosFrescosNoLlevaLaMarca() = runComposeUiTest {
        val state = DashboardV2State(blocks = mapOf(DashboardBlock.ECONOMY to UiAsync.Success(economyReport())))
        setContent { panel(state) }

        assertEquals(0, onAllNodesWithText(DASHBOARD_STALE_LABEL).fetchSemanticsNodes().size)
    }

    @Test
    fun unaLecturaFallidaSeDibujaComoTalYNoComoSinDato() = runComposeUiTest {
        val pulse = DashboardBlockReport(
            block = DashboardBlock.PULSE,
            metrics = listOf(
                AnalyticsMetric(
                    key = DashboardMetricKeys.ACTIVE_USERS_30D,
                    value = null,
                    status = AnalyticsMetricStatus.NO_DATA,
                    readFailed = true,
                ),
                AnalyticsMetric(DashboardMetricKeys.GLOBAL_ERROR_RATE, null, AnalyticsMetricStatus.NO_DATA),
            ),
            generatedAt = now,
        )
        setContent { panel(DashboardV2State(blocks = mapOf(DashboardBlock.PULSE to UiAsync.Success(pulse)))) }

        assertEquals(1, onAllNodesWithText(NO_SE_PUDO_LEER).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText(SIN_DATO).fetchSemanticsNodes().size, "la otra cifra, leída sin dato, sigue diciendo «Sin dato»")
    }

    // -----------------------------------------------------------------

    private fun economyReport() = DashboardBlockReport(
        block = DashboardBlock.ECONOMY,
        metrics = listOf(
            AnalyticsMetric(DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE, AnalyticsValue.Count(0), AnalyticsMetricStatus.AVAILABLE),
            AnalyticsMetric(DashboardMetricKeys.GROSS_REVENUE_CENTS, AnalyticsValue.MoneyCents(0), AnalyticsMetricStatus.AVAILABLE),
        ),
        generatedAt = now,
    )

    @androidx.compose.runtime.Composable
    private fun panel(state: DashboardV2State, onOpen: (DashboardV2Destination) -> Unit = {}) {
        Box(modifier = Modifier.size(width = 1148.dp, height = 1000.dp)) {
            AdminTheme {
                DashboardV2Content(
                    state = state,
                    now = now,
                    onRefreshBlock = {},
                    onOpen = onOpen,
                )
            }
        }
    }
}
