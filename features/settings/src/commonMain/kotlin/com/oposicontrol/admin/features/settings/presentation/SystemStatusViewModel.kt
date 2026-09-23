package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import kotlin.time.Clock
import kotlin.time.Instant

class SystemStatusViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: SystemStatusRepository,
    private val analyticsTracker: AnalyticsTracker,
    private val clock: () -> Instant = { Clock.System.now() },
) : MviViewModel<SystemStatusState, SystemStatusIntent>(
    initialState = SystemStatusState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(SystemStatusIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SystemStatusIntent) {
        when (intent) {
            SystemStatusIntent.Refresh -> refresh()
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("system_status")
        updateState { it.copy(uiState = SystemStatusUiState.Loading) }
        when (val result = repository.loadStatus()) {
            is SystemStatusAvailability.Available -> updateState {
                it.copy(uiState = result.value.toUiState(clock()))
            }
            SystemStatusAvailability.NotConnected -> updateState {
                it.copy(uiState = SystemStatusUiState.NotConnected)
            }
            is SystemStatusAvailability.Failure -> updateState {
                it.copy(uiState = SystemStatusUiState.Error(result.error.message))
            }
        }
    }
}

private fun SystemStatus.toUiState(now: Instant): SystemStatusUiState {
    val ui = SystemStatusUi(
        overallStatus = overallStatus,
        ktorStatus = ktorStatus,
        supabaseStatus = supabaseStatus,
        workerStatus = workerStatus,
        cpuPercent = cpuPercent,
        ramPercent = ramPercent,
        supabaseDiskPercent = supabaseDiskPercent,
        apiLatencyMs = apiLatencyMs,
        errors24h = errors24h,
        mobileCrashes24h = mobileCrashes24h,
        queueDepth = queueDepth,
        updatedAtLabel = updatedAt.toRelativeLabel(now),
    )
    return when {
        overallStatus == SystemStatusLevel.DEGRADED || overallStatus == SystemStatusLevel.DOWN -> {
            SystemStatusUiState.Degraded(ui)
        }
        listOf(
            cpuPercent,
            ramPercent,
            supabaseDiskPercent,
            apiLatencyMs,
            errors24h,
            mobileCrashes24h,
            queueDepth,
            updatedAt,
        ).any { it == null } -> SystemStatusUiState.PartialData(ui)
        else -> SystemStatusUiState.Content(ui)
    }
}

internal fun Instant?.toRelativeLabel(now: Instant = Clock.System.now()): String {
    if (this == null) return "No disponible"
    val elapsedSeconds = (now - this).inWholeSeconds.coerceAtLeast(0)
    return when {
        elapsedSeconds < 60 -> "Hace ${elapsedSeconds}s"
        elapsedSeconds < 3_600 -> "Hace ${elapsedSeconds / 60} min"
        elapsedSeconds < 86_400 -> "Hace ${elapsedSeconds / 3_600} h"
        else -> "Hace ${elapsedSeconds / 86_400} d"
    }
}
