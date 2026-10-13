package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemStatusViewModelTest {
    private val now = Instant.parse("2030-01-01T00:01:00Z")
    private val healthy = SystemStatus(
        overallStatus = SystemStatusLevel.OPERATIVE,
        ktorStatus = SystemStatusLevel.OPERATIVE,
        supabaseStatus = SystemStatusLevel.OPERATIVE,
        cpuPercent = 24.5,
        prometheusResponding = true,
    )
    private val superadmin = AdminSessionInfo("u-1", "Fran", listOf("admin", "superadmin"), isSuperAdmin = true, isAdmin = true)

    @Test
    fun itStartsLoadingAndNeverGreenBeforeTheFirstReading() = runTest {
        val viewModel = viewModelWith(FakeRepository(SystemStatusAvailability.Available(healthy)))

        // Antes de que conteste el servidor no hay nada que afirmar: ni «operativo» ni «sin conectar».
        assertIs<SystemStatusUiState.Loading>(viewModel.state.value.uiState)
        assertIs<UiAsync.Loading>(viewModel.state.value.session)
    }

    @Test
    fun aReadingBecomesContentStampedWithTheMomentThePanelReceivedIt() = runTest {
        val viewModel = viewModelWith(FakeRepository(SystemStatusAvailability.Available(healthy), AppResult.Success(superadmin)))

        advanceUntilIdle()

        val content = assertIs<SystemStatusUiState.Content>(viewModel.state.value.uiState)
        assertEquals(healthy, content.status, "el estado llega tal cual: aquí no se recalcula ningún nivel")
        assertEquals(now, content.readAt)
        assertEquals(UiAsync.Success(superadmin), viewModel.state.value.session)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals("Producción", viewModel.state.value.environmentLabel)
        assertEquals("1.2.3", viewModel.state.value.panelVersion)
    }

    @Test
    fun notConnectedDoesNotExposeInventedMetricsNorAskForASession() = runTest {
        val repository = FakeRepository(SystemStatusAvailability.NotConnected)
        val viewModel = viewModelWith(repository)

        advanceUntilIdle()

        assertIs<SystemStatusUiState.NotConnected>(viewModel.state.value.uiState)
        assertEquals(UiAsync.Empty, viewModel.state.value.session)
        assertEquals(0, repository.sessionCalls, "sin servidor no hay a quién preguntar por la sesión")
    }

    @Test
    fun aFailedReadingIsAnErrorWithItsCause() = runTest {
        val error = AppError.Network("No se pudo conectar al monitor.")
        val viewModel = viewModelWith(FakeRepository(SystemStatusAvailability.Failure(error)))

        advanceUntilIdle()

        assertEquals(SystemStatusUiState.Error(error), viewModel.state.value.uiState)
        assertFalse(viewModel.state.value.refreshing, "tras fallar se puede volver a pulsar «Actualizar»")
    }

    @Test
    fun aFailedSessionDoesNotHideTheStatus() = runTest {
        val forbidden = AppError.Authorization("No tienes permisos para realizar esta acción.")
        val viewModel = viewModelWith(FakeRepository(SystemStatusAvailability.Available(healthy), AppResult.Failure(forbidden)))

        advanceUntilIdle()

        assertIs<SystemStatusUiState.Content>(viewModel.state.value.uiState)
        assertEquals(UiAsync.Error(forbidden), viewModel.state.value.session)
    }

    @Test
    fun refreshingKeepsTheCurrentReadingOnScreenUntilTheNewOneArrives() = runTest {
        val repository = FakeRepository(SystemStatusAvailability.Available(healthy), AppResult.Success(superadmin))
        val viewModel = viewModelWith(repository)
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val degraded = healthy.copy(overallStatus = SystemStatusLevel.DEGRADED)
        repository.status = SystemStatusAvailability.Available(degraded)
        viewModel.dispatch(SystemStatusIntent.Refresh)
        testScheduler.runCurrent()

        // Con la nueva lectura en curso: la anterior sigue a la vista (sin volver al esqueleto) y el botón gira.
        assertEquals(healthy, assertIs<SystemStatusUiState.Content>(viewModel.state.value.uiState).status)
        assertEquals(UiAsync.Success(superadmin), viewModel.state.value.session)
        assertTrue(viewModel.state.value.refreshing)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(degraded, assertIs<SystemStatusUiState.Content>(viewModel.state.value.uiState).status)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun aSecondClickWhileReadingDoesNotStartAnotherReading() = runTest {
        val repository = FakeRepository(SystemStatusAvailability.Available(healthy))
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = viewModelWith(repository)
        testScheduler.runCurrent()

        viewModel.dispatch(SystemStatusIntent.Refresh)
        viewModel.dispatch(SystemStatusIntent.Refresh)
        testScheduler.runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.statusCalls)
    }

    @Test
    fun aRefreshThatFailsReplacesTheOldReadingInsteadOfLeavingItGreen() = runTest {
        val repository = FakeRepository(SystemStatusAvailability.Available(healthy))
        val viewModel = viewModelWith(repository)
        advanceUntilIdle()

        val error = AppError.Network("timeout")
        repository.status = SystemStatusAvailability.Failure(error)
        viewModel.dispatch(SystemStatusIntent.Refresh)
        advanceUntilIdle()

        // Un «todo funciona» de hace diez minutos junto a un «no he podido preguntar» sería verde por desconocimiento.
        assertEquals(SystemStatusUiState.Error(error), viewModel.state.value.uiState)
    }

    private class FakeRepository(
        var status: SystemStatusAvailability,
        var session: AppResult<AdminSessionInfo> = AppResult.Success(AdminSessionInfo("u-0")),
    ) : SystemStatusRepository {
        var gate: CompletableDeferred<Unit>? = null
        var statusCalls = 0
        var sessionCalls = 0

        override suspend fun loadStatus(): SystemStatusAvailability {
            statusCalls++
            gate?.await()
            return status
        }

        override suspend fun loadSession(): AppResult<AdminSessionInfo> {
            sessionCalls++
            return session
        }
    }

    private fun TestScope.viewModelWith(repository: SystemStatusRepository) = SystemStatusViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
        environmentLabel = "Producción",
        panelVersion = "1.2.3",
        clock = { now },
    )
}
