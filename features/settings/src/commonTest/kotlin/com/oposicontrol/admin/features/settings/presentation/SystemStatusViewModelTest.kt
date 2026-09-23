package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SystemStatusViewModelTest {
    @Test
    fun notConnectedDoesNotExposeInventedMetrics() = runTest {
        val viewModel = viewModelWith(SystemStatusAvailability.NotConnected)

        advanceUntilIdle()

        assertIs<SystemStatusUiState.NotConnected>(viewModel.state.value.uiState)
    }

    @Test
    fun healthyPayloadBecomesContentAndFormatsRelativeUpdate() = runTest {
        val now = Instant.parse("2030-01-01T00:01:00Z")
        val viewModel = viewModelWith(
            SystemStatusAvailability.Available(
                SystemStatus(
                    overallStatus = SystemStatusLevel.OPERATIVE,
                    ktorStatus = SystemStatusLevel.OPERATIVE,
                    supabaseStatus = SystemStatusLevel.OPERATIVE,
                    workerStatus = SystemStatusLevel.UNKNOWN,
                    cpuPercent = 24.5,
                    ramPercent = 41.25,
                    supabaseDiskPercent = 3.5632,
                    apiLatencyMs = 86,
                    errors24h = 3,
                    mobileCrashes24h = 0,
                    queueDepth = 2,
                    updatedAt = now.minus(20.seconds),
                ),
            ),
            currentTime = now,
        )

        advanceUntilIdle()

        val state = assertIs<SystemStatusUiState.Content>(viewModel.state.value.uiState)
        assertEquals("Hace 20s", state.value.updatedAtLabel)
        assertEquals(SystemStatusLevel.UNKNOWN, state.value.workerStatus)
        assertEquals(24.5, state.value.cpuPercent)
        assertEquals(41.25, state.value.ramPercent)
        assertEquals(3.5632, state.value.supabaseDiskPercent)
        assertEquals(3L, state.value.errors24h)
    }

    @Test
    fun anUnavailableSupabaseDiskIsPartialDataAndNeverAZero() = runTest {
        val now = Instant.parse("2030-01-01T00:01:00Z")
        val viewModel = viewModelWith(
            SystemStatusAvailability.Available(
                SystemStatus(
                    overallStatus = SystemStatusLevel.OPERATIVE,
                    ktorStatus = SystemStatusLevel.OPERATIVE,
                    supabaseStatus = SystemStatusLevel.OPERATIVE,
                    workerStatus = SystemStatusLevel.UNKNOWN,
                    cpuPercent = 24.5,
                    ramPercent = 41.25,
                    supabaseDiskPercent = null,
                    apiLatencyMs = 86,
                    errors24h = 3,
                    mobileCrashes24h = 0,
                    queueDepth = 2,
                    updatedAt = now,
                ),
            ),
            currentTime = now,
        )

        advanceUntilIdle()

        val state = assertIs<SystemStatusUiState.PartialData>(
            viewModel.state.value.uiState,
            "Un disco que no se ha podido medir es una métrica ausente, y la pantalla lo avisa.",
        )
        assertEquals(null, state.value.supabaseDiskPercent)
        assertEquals(null, state.value.supabaseDiskPercent.toPercentLabel())
    }

    @Test
    fun aMeasuredEmptyDiskIsShownAsZeroAndNotAsUnavailable() = runTest {
        val now = Instant.parse("2030-01-01T00:01:00Z")
        val viewModel = viewModelWith(
            SystemStatusAvailability.Available(
                SystemStatus(
                    overallStatus = SystemStatusLevel.OPERATIVE,
                    ktorStatus = SystemStatusLevel.OPERATIVE,
                    supabaseStatus = SystemStatusLevel.OPERATIVE,
                    workerStatus = SystemStatusLevel.UNKNOWN,
                    cpuPercent = 24.5,
                    ramPercent = 41.25,
                    supabaseDiskPercent = 0.0,
                    apiLatencyMs = 86,
                    errors24h = 3,
                    mobileCrashes24h = 0,
                    queueDepth = 2,
                    updatedAt = now,
                ),
            ),
            currentTime = now,
        )

        advanceUntilIdle()

        val state = assertIs<SystemStatusUiState.Content>(viewModel.state.value.uiState)
        assertEquals(0.0, state.value.supabaseDiskPercent)
        assertEquals("0 %", state.value.supabaseDiskPercent.toPercentLabel())
    }

    @Test
    fun theRealSupabaseReadingIsFormattedWithTheSharedPercentLabel() {
        // 100 * (1 - 8.05275648e9 / 8.350298112e9) = 3,5632…%
        assertEquals("3.6 %", 3.5632.toPercentLabel())
    }

    @Test
    fun degradedAndPartialPayloadsKeepExplicitUiStates() = runTest {
        val degraded = viewModelWith(
            SystemStatusAvailability.Available(
                SystemStatus(overallStatus = SystemStatusLevel.DEGRADED),
            ),
        )
        val partial = viewModelWith(
            SystemStatusAvailability.Available(
                SystemStatus(
                    overallStatus = SystemStatusLevel.OPERATIVE,
                    cpuPercent = 24.0,
                ),
            ),
        )

        advanceUntilIdle()

        assertIs<SystemStatusUiState.Degraded>(degraded.state.value.uiState)
        assertIs<SystemStatusUiState.PartialData>(partial.state.value.uiState)
    }

    @Test
    fun repositoryFailureDoesNotExposeInternalDetails() = runTest {
        val viewModel = viewModelWith(
            SystemStatusAvailability.Failure(AppError.Network("No se pudo conectar al monitor.")),
        )

        advanceUntilIdle()

        val state = assertIs<SystemStatusUiState.Error>(viewModel.state.value.uiState)
        assertEquals("No se pudo conectar al monitor.", state.message)
    }

    @Test
    fun zeroIsPresentedAsMeasuredAndNullAsUnavailable() {
        assertEquals("0 %", 0.0.toPercentLabel())
        assertEquals("12.5 %", 12.54.toPercentLabel())
        assertEquals(null, null.toPercentLabel())
    }

    private fun TestScope.viewModelWith(
        result: SystemStatusAvailability,
        currentTime: Instant? = null,
    ) = SystemStatusViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = object : SystemStatusRepository {
            override suspend fun loadStatus(): SystemStatusAvailability = result
        },
        analyticsTracker = NoOpAnalyticsTracker,
        clock = { currentTime ?: Clock.System.now() },
    )
}
